|             |                                                                                                                 |
|-------------|-----------------------------------------------------------------------------------------------------------------|
| Feature     | Rich errors on the server: error and exception handling                                                         |
| Submitted   | 2026-11-12                                                                                                      |
| Accepted    | No                                                                                                              |
| Issue       | https://youtrack.jetbrains.com/issue/KTOR-9977/Exception-Handling-Request-Validation-and-Status-Pages-Workgroup |
| Preceded by |                                                                                                                 |
| Followed by | 0012 Parameter decoding; 0013 Rich errors: validation                                                           |

### Contents

1. [Summary](#summary)
2. [Motivation](#motivation)
3. [Current Solutions](#current-solutions)
4. [Design Overview](#design-overview)
5. [Design Details](#design-details)
6. [Technical Details](#technical-details)
7. [Drawbacks](#drawbacks)
8. [Advantages](#advantages)
9. [Open Questions](#open-questions)
10. [Future Directions](#future-directions)

<hr />

# Summary

[summary]: #summary

Kotlin rich errors ([KEEP-0462](https://github.com/Kotlin/KEEP/blob/main/proposals/KEEP-0462-rich-errors.md)) let a
function return `T | E`, where `E` is a union of error classes checked by the compiler.
This proposal uses them for server-side error handling in Ktor:

- error handlers and exception handlers are values that route scopes (boundaries) apply
- a route sends a typed error to the error handler in scope; the compiler picks the handler through context parameters
  ([KEEP-0367](https://github.com/Kotlin/KEEP/blob/main/proposals/KEEP-0367-context-parameters.md))
- one global boundary covers calls and application coroutines

# Motivation

[motivation]: #motivation

- **No type checking:** exceptions are the only error channel, ADTs are not used much in Ktor.
- **StatusPages is application-wide and matched at runtime:** a missing mapping is a 500, and matching differs between
  JVM and other platforms.
- **Failures in application coroutines go to stderr:** engine-rooted coroutines also drop `IOException` silently.

# Current Solutions

[current-solutions]: #current-solutions

```kotlin
// errors: exceptions + StatusPages, application-wide, matched by class at runtime
install(StatusPages) {
    exception<NotFoundException> { call, e -> call.respond(HttpStatusCode.NotFound) }
}
```

```kotlin
// background failures
application.launch { error("boom") }
// logged only by DefaultUncaughtExceptionHandler, though drops CancellationException and IOException silently
```

# Design Overview

[design-overview]: #design-overview

All examples use these errors:

```kotlin
error class UserNotFound(val id: Long)
error class EmailTaken(val email: String)
typealias UserError = UserNotFound | EmailTaken
```

### 1. An error handler is a value

```kotlin
val userErrors = errorHandler<UserError> { error ->
    when (error) { // exhaustive: a new error class is a compile error here
        is UserNotFound -> call.respond(HttpStatusCode.NotFound)
        is EmailTaken -> call.respond(HttpStatusCode.Conflict, "${error.email} is taken")
    }
}
```

### 2. An error boundary brings it into scope

```kotlin
routing {
    errorBoundary(userErrors) {         // a context parameter: ErrorHandler<UserError>
        get("/users/{id}") { /* ... */ }
    }
}
```

### 3. A route sends errors to the handler in scope

```kotlin
errorBoundary(userErrors) {
    get("/users/{id}") {
        val user = users.find(id) ?: return@get respondError(UserNotFound(id)) // calls userErrors directly
        call.respond(user)
    }
}
// outside every errorBoundary, respondError(UserNotFound(id)) doesn't compile
```

### 4. Exceptions get the same pair

```kotlin
val dbFailures = exceptionHandler {
    on<SQLTransientConnectionException> { call.respond(HttpStatusCode.ServiceUnavailable) }
}

exceptionBoundary(dbFailures) { /* routes */ }
```

### 5. Handlers combine and nest

```kotlin
// both handlers are in scope: a UserError goes to userErrors, an AdminError to adminErrors
errorBoundary(userErrors, adminErrors) { /* ... */ }

// nesting does the same; the inner boundary is searched first
errorBoundary(userErrors) {
    errorBoundary(adminErrors) { /* ... */ }
}

// an error boundary inside an exception boundary
exceptionBoundary(dbFailures) {
    errorBoundary(userErrors) { /* ... */ }
}
```

### 6. One global boundary

```kotlin
globalExceptionBoundary {
    on<SQLException> { call.respond(HttpStatusCode.InternalServerError) } // any call
    onCoroutineFailure<SQLException> { e, context -> alerts.databaseDown(e) } // application.launch and plugin coroutines
}

routing { /* ... */ }
```

# Design Details

[design-details]: #design-details

## Route handlers

An error handler is an `ErrorHandler<E>` value. A boundary passes it to its routes as a context parameter, and
`respondError` needs one that accepts its argument, so the compiler binds every call to one handler.

```kotlin
public fun interface ErrorHandler<E : Error> {
    public suspend fun RoutingContext.handle(error: E)
}

context(handler: ErrorHandler<E>)
public suspend fun <E : Error> RoutingContext.respondError(error: E): Unit = with(handler) { handle(error) }
```

```kotlin
errorBoundary(userErrors) {
    get("/users/{id}") {
        val id = call.pathParameters.getOrError<Long>("id")
            .ifError { return@get respondError(it) } // compile error: no boundary accepts MissingParameter | InvalidParameter
        val user = users.find(id) ?: return@get respondError(UserNotFound(id))
        call.respond(user)
    }
}
```

- **No runtime search:** `respondError` calls the bound handler directly. There is no lookup through call attributes, no
  `is` checks to pick a handler and no chain.
- **A union is passed as one value:** the boundary has to accept the whole union. If its members are handled by
  different boundaries, split it with `when`, or use one handler written for the whole union (see Error handlers).
- **`RoutingHandler` doesn't change:** routes still return `Unit`.

### Alternative: `raise(error): Nothing`

```kotlin
context(handler: ErrorHandler<E>)
public suspend fun <E : Error> RoutingContext.raise(error: E): Nothing {
    with(handler) { handle(error) }   // the handler runs first
    throw _errorRaised               // then a control-flow signal leaves the route
}

internal class ErrorRaised : Throwable("error handled") // core routing catches it

internal val _errorRaised = ErrorRaised() // reuse it to reduce allocations

val user = users.find(id) ?: raise(UserNotFound(id)) // no return@get
```

This reads better, and a route can't keep running after an error, as with Arrow's `Raise`. But it throws:

- **The signal can be swallowed:** `catch (e: Throwable)` or `runCatching` in a route catches it, and the route
  continues after the error was handled.
- **Other coroutines don't carry it:** a `raise` inside `launch` or `async` doesn't end the route.

`respondError` is plain control flow, and `return@get` makes the exit visible.

### Alternative: routes return `Unit | E`

Inside an `errorBoundary<E>`, member `get` / `post` / `route` overloads would take
`suspend RoutingContext.() -> Unit | E`,
and the boundary would handle the returned value:

```kotlin
errorBoundary(userErrors) {
    get("/users/{id}") {
        val user = users.find(id) ?: return@get UserNotFound(id) // the route's type is Unit | UserError
        call.respond(user)
    }
}
```

This works for a single boundary: `Unit | E` has only one error type parameter. It also has two advantages: an
ignored error doesn't compile, and OpenAPI can read the errors from the route's type.

It can't combine or nest:

```kotlin
// combining two handlers, or nesting a boundary inside another, needs the union of two error type parameters
public fun <E : Error, A : Error> ErrorBoundaryRoute<E>.errorBoundary(
    vararg handlers: ErrorHandler<A>,
    // doesn't compile: KEEP-0462 allows only one error type parameter in a union
    build: ErrorBoundaryRoute<E | A>.() -> Unit,
): Route

// doesn't compile, for the same reason
public operator fun <A : Error, B : Error> ErrorHandler<A>.plus(other: ErrorHandler<B>): ErrorHandler<A | B>
```

## Error handlers

```kotlin
public fun <E : Error> errorHandler(
    skipSentCalls: Boolean = true,
    handle: suspend RoutingContext.(E) -> Unit, // the body of ErrorHandler.handle
): ErrorHandler<E>

// a route that responded, then sent an error
val auditErrors = errorHandler<UserError>(skipSentCalls = false) { error ->
    if (call.response.isSent) audit.log(error) else call.respond(HttpStatusCode.BadRequest)
}
```

- **`skipSentCalls`** is applied by the handler that `errorHandler` builds: when the call was already sent, it logs the
  error and doesn't run `handle`. There might be race conditions unless we provide `call.respondUnlessSent(...)`.
- **Joining by hand:** for a concrete union, one handler can delegate to others. `is` on a type alias union is part of
  KEEP-0462:
  ```kotlin
  val apiErrors = errorHandler<UserError | AdminError> { error ->
      when (error) {
          is UserError -> with(userErrors) { handle(error) }   // smart cast: UserNotFound | EmailTaken
          is AdminError -> with(adminErrors) { handle(error) }
      }
  }
  ```
- **Headers:** the error carries the headers its response needs, for example `RateLimited(retryAfter)` → `Retry-After`.
  The handler may override them.

## Error boundaries

```kotlin
public fun <A : Error> Route.errorBoundary(
    a: ErrorHandler<A>,
    build: context(ErrorHandler<A>) Route.() -> Unit,
): Route

public fun <A : Error, B : Error> Route.errorBoundary(
    // each handler becomes its own context parameter, so no union type is needed
    a: ErrorHandler<A>,
    b: ErrorHandler<B>,
    build: context(ErrorHandler<A>, ErrorHandler<B>) Route.() -> Unit,
): Route
// ... overloads up to a fixed arity
```

```kotlin
errorBoundary(userErrors) {
    errorBoundary(adminErrors) {
        get("/admin/users/{id}") {
            if (!isAdmin) return@get respondError(AdminOnly)            // resolves to adminErrors
            users.find(id) ?: return@get respondError(UserNotFound(id)) // resolves to userErrors
        }
    }
}
```

Context resolution (KEEP-0367 §7.5) gives the rules:

- **Nesting:** scopes are searched from the innermost outward. If both boundaries accept `UserNotFound`, the inner one
  wins.
- **The same level:** if two handlers of one `errorBoundary(a, b)` both accept the error, the call is a compile error
  (context ambiguity). Nest them to choose.
- **Reuse:** a group of handlers is a plain function, not a combined value:
  ```kotlin
  fun Route.apiBoundary(build: context(ErrorHandler<UserError>, ErrorHandler<AdminError>) Route.() -> Unit) =
      errorBoundary(userErrors, adminErrors, build)
  ```

## Exception handlers and boundaries

```kotlin
val dbFailures = exceptionHandler {
    // the specific type goes first, the general type after it
    on<SQLTransientConnectionException> { e -> call.respond(HttpStatusCode.ServiceUnavailable) }
    on<SQLException> { call.respond(HttpStatusCode.InternalServerError) }
}

// thrown after the response was sent (mid-stream): skipped by default
val streamFailures = exceptionHandler(skipSentCalls = false) {
    on<IOException> { e ->
        if (call.response.isSent) log.debug("client left", e) else call.respond(HttpStatusCode.BadGateway)
    }
}

exceptionBoundary(dbFailures) {
    errorBoundary(userErrors) { /* ... */ }
}
```

Exceptions are matched at runtime:

- **Matching:** `on<T>` matches `T` and its subtypes, interfaces included. The handlers are checked in declaration
  order, and the first match wins, so specific types go first, as with `catch` blocks.
- **Cached:** the result is remembered for each exception class, so the handlers are walked only the first time a
  class is thrown.
- **Every platform:** matching uses only `isInstance`, so it behaves the same everywhere.
- **Unlisted exceptions** go to the outer `exceptionBoundary`, then `globalExceptionBoundary`, then a 500 with no
  message.
- **When a handler is skipped because the call was already sent,** the outer boundaries are skipped too, and the
  exception is logged.
- **Causes aren't unwrapped:** an exception is matched by its own class, never by its `cause`.

## Combining

```kotlin
// errors: each handler is a separate context parameter, and the compiler picks one (see Error boundaries)
errorBoundary(userErrors, adminErrors) { /* ... */ }

// exceptions: the handlers of one boundary are joined in the order given.
// The first match still wins, so a handler given earlier takes priority over a later one.
exceptionBoundary(dbFailures, httpFailures) { /* ... */ }

// to override another handler, nest the boundaries: the inner one runs first
exceptionBoundary(commonFailures) {
    exceptionBoundary(dbFailures) { /* ... */ }
}

// errors and exceptions: nest the two boundaries.
// One function for both would need an overload for every number of error handlers and exception handlers.
exceptionBoundary(dbFailures) {
    errorBoundary(userErrors) { /* ... */ }
}
```

## Default responses (To be discussed)

Ktor and its plugins have errors of their own, such as a failed `receive`. `respondDefaultError` answers them without a
handler written by the application. It works in every `RoutingContext` and needs no boundary.

```kotlin
public suspend fun RoutingContext.respondDefaultError(error: Error)

// the module that declares an error registers its default response, usually when its plugin is installed
public inline fun <reified E : Error> Application.defaultErrorResponse(
    noinline respond: suspend RoutingContext.(E) -> Unit,
)

application.defaultErrorResponse<ValidationFailed> { call.respond(HttpStatusCode.UnprocessableEntity) }
```

```kotlin
// in a route, with no boundary
post("/users") {
    val user = call.receiveValidated<User>().ifError { return@post respondDefaultError(it) }
    /* ... */
}

// in a handler, for the errors the application doesn't customize
val apiErrors = errorHandler<UserError | ReceiveFailure | ValidationFailed> { error ->
    when (error) {
        is UserError -> with(userErrors) { handle(error) }
        else -> respondDefaultError(error) // Ktor's errors
    }
}
```

- **Looked up at runtime:** the responses are stored in the application, keyed by the error's class.
- **The last registration wins:** an application can replace a default by registering its own response for the class.
- **Not a fallback for `respondError`:** it is always called explicitly.
- **An unregistered error** gets a 500 and is logged. Alternative: `respondDefaultError` returns `Boolean`.

## Global exception boundary

This boundary works at the application level. Calls reach it from plugins before routing, from routing, and from routes
without an `exceptionBoundary`. Coroutines reach it from `application.launch`, from plugin launches on the application
scope, and from coroutines the engine roots.

Two shapes are proposed:

```kotlin
// a. a prefixed handler
globalExceptionBoundary {
    on<SQLTransientConnectionException> { call.respond(HttpStatusCode.ServiceUnavailable) }
    onCoroutineFailure<SQLException> { e, context -> alerts.databaseDown(context[CoroutineName], e) }
    onCoroutineFailure<TimeoutException> { e, context -> metrics.jobTimedOut(context[CoroutineName]) }
}

// b. a nested scope
globalExceptionBoundary {
    on<SQLTransientConnectionException> { call.respond(HttpStatusCode.ServiceUnavailable) }
    coroutines {
        on<SQLException> { e, context -> alerts.databaseDown(context[CoroutineName], e) }
        on<TimeoutException> { e, context -> metrics.jobTimedOut(context[CoroutineName]) }
    }
}
```

- **Call handlers** get a `RoutingContext` that wraps the call, even before routing, so they read like route code.
- **Unlisted call exceptions:** a 500 with no message.
- **Unlisted coroutine exceptions:** logged with the coroutine name. `CancellationException` is skipped.
- **Specific types only:** the global boundary has no `Throwable` catch-all.

# Technical Details

[technical-details]: #technical-details

## Boundaries

- **`errorBoundary` installs nothing:** it passes its `ErrorHandler` values to `build` as context arguments.
- **`exceptionBoundary`** installs a route-scoped plugin. The plugin stores its handlers in the call's attributes, and
  the inner boundaries run first.

## Global boundary plumbing

- The application's coroutine scope gets a `CoroutineExceptionHandler` that passes failures to the coroutine handlers.
- The engines (Netty, CIO, Jetty and servlet) report their own coroutine failures to
  `DefaultUncaughtExceptionHandler`. It calls the same handlers first and logs only what they didn't handle.
- How the engines find the boundary is an implementation detail.

## Migration

`StatusPages` is deprecated in 4.0 in favour of error and exception boundaries.

# Drawbacks

[drawbacks]: #drawbacks

- **It depends on KEEP-0462 and on context parameters (KEEP-0367)**, and on the Kotlin version that ships both. Until
  then, the design can't be released.
- **Errors are sent, not returned:** a route that builds an error and forgets `respondError` compiles. Only the
  KEEP-0462 traceability and must-use warnings catch it, and the route falls through to today's 404. A `respondError`
  without `return@get` keeps the route running after the error was handled.
- **OpenAPI can't read a route's errors from its type:** they are in the contexts in scope, which a compiler plugin can
  read and runtime introspection can't.
- **Fixed arity:** `errorBoundary(a, b, c)` exists only up to the largest overload.
- **There are two failure channels to learn:** errors for expected outcomes, exceptions for everything else.
- **Exception order is easy to get wrong:** a general `on<T>` before a specific one takes its matches.

# Advantages

[advantages]: #advantages

- **Compiler-checked errors:** a route can't send an error that no boundary handles, and a handler can't miss a case.
- **No runtime dispatch for errors:** the compiler binds each `respondError` to one handler.
- **Reusable handlers:** they are values scoped to route subtrees, like `authenticate`, instead of one application-wide
  table.

# Open Questions

[open-questions]: #open-questions

- `respondDefaultError` for an unregistered error: a 500 and a log line, or a `Boolean` result?
- Global boundary: `onCoroutineFailure<T>` or a `coroutines { }` scope?
- How many handlers should the `errorBoundary` overloads take?
- Names: `respondError` (the handler may also only log), `respondDefaultError`, `defaultErrorResponse`.

# Future Directions

[future-directions]: #future-directions

- **Plugins that reject calls:** RateLimit, CORS, CSRF and CallId would report typed errors to their own hooks, like
  auth's `onUnauthorized`. A route scope could send them to the error handler in scope:
  ```kotlin
  install(RateLimit) { onRateLimited { e -> call.respond(HttpStatusCode.TooManyRequests, "Retry in ${e.retryAfter}") } }

  errorBoundary(apiErrors) {
      // compiles only if apiErrors accepts RateLimited
      rateLimit(RateLimitName("api"), onRateLimited = { respondError(it) }) { /* ... */ }
  }
  ```
- **Problem details:** RFC 9457 responses as a separate proposal. Ktor's default responses would use them when they
  are installed.
- **Server core, auth and sessions:** `receiveOrError`, typed authentication and session errors.
