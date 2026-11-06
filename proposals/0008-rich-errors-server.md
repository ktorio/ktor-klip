|             |                                                                                                                 |
|-------------|-----------------------------------------------------------------------------------------------------------------|
| Feature     | Rich errors on the server: error handling and validation                                                        |
| Submitted   | 2026-11-06                                                                                                      |
| Accepted    | No                                                                                                              |
| Issue       | https://youtrack.jetbrains.com/issue/KTOR-9977/Exception-Handling-Request-Validation-and-Status-Pages-Workgroup |
| Preceded by |                                                                                                                 |
| Followed by |                                                                                                                 |

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
- query, path, header, cookie and form parameters decode into classes
- validation is an explicit step that returns typed violations

It replaces StatusPages and RequestValidation.

# Motivation

[motivation]: #motivation

- **No type checking:** exceptions are the only error channel, so the compiler can't tell a route which failures it must
  handle.
- **StatusPages is application-wide and matched at runtime:** a missing mapping is a 500, and matching differs between
  JVM and other platforms.
- **RequestValidation is limited:**
    - it is untyped
    - it runs only inside `receive`
    - it has no field paths or codes
    - its exception becomes a 500 unless StatusPages maps it
- **Parameters can't decode into classes outside Resources:** they are parsed one at a time.
- **Failures in application coroutines go to stderr:** engine-rooted coroutines also drop `IOException` silently.

# Current Solutions

[current-solutions]: #current-solutions

```kotlin
// errors: exceptions + StatusPages, application-wide, matched by class at runtime
install(StatusPages) {
    exception<NotFoundException> { call, e -> call.respond(HttpStatusCode.NotFound) }
}
// matching: nearest superclass on JVM (StatusPagesUtilsJvm.kt), elsewhere the exact class or keys.last() of a
// HashMap, an arbitrary match (StatusPages.kt:40-54, StatusPagesUtils.nonJvm.kt)
// unmapped: core's handleFailure sends error.message for every exception (DefaultEnginePipeline.kt:106-115)
```

```kotlin
// validation: only inside receive; throws RequestValidationException (an IllegalArgumentException)
install(RequestValidation) {
    validate<User> { user ->
        if (user.name.isBlank()) ValidationResult.Invalid("name must not be blank") else ValidationResult.Valid
    }
}
// reasons: List<String>; Validator.validate(value: Any) is untyped; every matching validator runs and the results merge
```

```kotlin
// parameters: one at a time, or Resources for a whole class
val page = call.queryParameters["page"]?.toIntOrNull() ?: 1
get<Users.ById> { /* ... */ } // a bad ?page=x throws ResourceSerializationException
```

```kotlin
// background failures
application.launch { error("boom") }
// Application.kt:133: SupervisorJob, no handler -> the kotlinx global handler (thread's uncaught handler, stderr)
// engine-rooted coroutines (Netty, CIO, Jetty, servlet) -> DefaultUncaughtExceptionHandler:
//   logs, drops CancellationException and IOException silently
```

# Design Overview

[design-overview]: #design-overview

One concept at a time. All examples use these errors:

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
        val user = users.find(id) ?: return@get handleError(UserNotFound(id)) // calls userErrors directly
        call.respond(user)
    }
}
// outside every errorBoundary, handleError(UserNotFound(id)) doesn't compile
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
errorBoundary(userErrors, adminErrors) { /* ... */ } // two contexts: UserError and AdminError errors both resolve
errorBoundary(userErrors) {
    errorBoundary(adminErrors) { /* ... */ }         // nested: the same, the inner one is closer
}
exceptionBoundary(dbFailures) {
    errorBoundary(userErrors) { /* ... */ }          // errors and exceptions: two nested scopes
}
```

### 6. Ktor exports its own handlers

```kotlin
exceptionBoundary(defaultExceptions) {
    errorBoundary(defaultErrors) {
        errorBoundary(userErrors) { /* ... */ } // the closer boundary wins, so yours go inside
    }
}
```

### 7. One global boundary

```kotlin
globalExceptionBoundary {
    on<SQLException> { call.respond(HttpStatusCode.InternalServerError) } // any call
    onCoroutineFailure<SQLException> { e, context -> alerts.databaseDown(e) } // application.launch and plugin coroutines
}
```

### 8. Parameters decode into classes

```kotlin
@Serializable
data class Search(val q: String, val page: Int = 1)

val search = call.queryParameters.decodeOrError<Search>().ifError { return@get handleError(it) } // InvalidParameters
```

### 9. Validators are values too

```kotlin
val userValidator = validator<User> { user ->
    ensure(user.name.isNotBlank(), at = "name") { "must not be blank" }
    user.copy(name = user.name.trim()) // the returned value is the normalized one
}

errorBoundary(defaultErrors) {
    validationBoundary(userValidator) {
        post("/users") {
            val user = call.receiveValidated<User>()
                .ifError { return@post handleError(it) } // ReceiveFailure | ValidationFailed
        }
    }
}
```

# Design Details

[design-details]: #design-details

## Route handlers

An error handler is an `ErrorHandler<E>` value. A boundary passes it to its routes as a context parameter, and
`handleError` needs one that accepts its argument, so the compiler binds every call to one handler.

```kotlin
public fun interface ErrorHandler<E : Error> {
    public suspend fun RoutingContext.handle(error: E)
}

context(handler: ErrorHandler<E>)
public suspend fun <E : Error> RoutingContext.handleError(error: E): Unit = with(handler) { handle(error) }
```

```kotlin
errorBoundary(userErrors) {
    get("/users/{id}") {
        val id = call.pathParameters.getOrError<Long>("id")
            .ifError { return@get handleError(it) } // compile error: no boundary accepts MissingParameter | InvalidParameter
        val user = users.find(id) ?: return@get handleError(UserNotFound(id))
        call.respond(user)
    }
}
```

- **Compile time:** an error that no boundary in scope accepts doesn't compile.
- **No runtime search:** `handleError` calls the bound handler directly. There is no lookup through call attributes, no
  `is` checks to pick a handler and no chain.
- **A union is passed as one value:** the boundary has to accept the whole union. If its members are handled by
  different boundaries, split it with `when`.
- **`RoutingHandler` doesn't change:** routes still return `Unit`, and `get` / `post` are today's functions. The
  boundary's context is captured by the route lambda like any other closure.
- **Not always a response:** the handler may respond, log or ignore the error, so the function is `handleError`, not
  `respondError`.

### Alternative: `raise(error): Nothing`

```kotlin
context(handler: ErrorHandler<E>)
public suspend fun <E : Error> RoutingContext.raise(error: E): Nothing {
    with(handler) { handle(error) }   // the handler runs first
    throw ErrorRaised                  // then a control-flow signal leaves the route
}

internal object ErrorRaised : CancellationException("error handled") // no stack trace; core routing catches it

val user = users.find(id) ?: raise(UserNotFound(id)) // no return@get
```

This reads better, and a route can't keep running after an error, as with Arrow's `Raise`. But it throws:

- **The signal can be swallowed:** `catch (e: Exception)` or `runCatching` in a route catches it, and the route
  continues after the error was handled.
- **Other coroutines don't carry it:** a `raise` inside `launch` or `async` doesn't end the route.
- **Routing changes:** core routing has to catch the signal.

`handleError` is plain control flow, and `return@get` makes the exit visible.

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
    build: ErrorBoundaryRoute<E | A>.() -> Unit, // compilation error in KEEP-0462
): Route
public operator fun <A : Error, B : Error> ErrorHandler<A>.plus(other: ErrorHandler<B>): ErrorHandler<A | B> // same
```

Each route would then have to be handled by exactly one handler, written for its whole union. Default handlers and
reusable handlers couldn't be mixed in. Even inside one boundary, dispatching a returned union to its handler needs
runtime `is` checks.

## Error handlers

```kotlin
public fun <E : Error> errorHandler(
    skipSentCalls: Boolean = true,
    handle: suspend RoutingContext.(E) -> Unit, // becomes ErrorHandler.handle; the handler responds itself
): ErrorHandler<E>

// a route that responded, then sent an error
val auditErrors = errorHandler<UserError>(skipSentCalls = false) { error ->
    if (call.response.isSent) audit.log(error) else call.respond(HttpStatusCode.BadRequest)
}
```

- **How errors arrive:** an error reaches a handler only through `handleError` in a boundary's scope.
- **`skipSentCalls`** is applied by the handler that `errorHandler` builds: when the call was already sent, it logs the
  error and doesn't run `handle`.
- **`E` is concrete:** it is one error class or a union written out, like `UserError`. `ErrorHandler<A | B>` built from
  two type parameters isn't valid, so there's no `plus`.
- **Headers:** the error carries the headers its response needs, for example `RateLimited(retryAfter)` → `Retry-After`.
  The handler may override them.
- **Routing misses** (404, 405) aren't errors.

## Error boundaries

```kotlin
public fun <A : Error> Route.errorBoundary(
    a: ErrorHandler<A>,
    build: context(ErrorHandler<A>) Route.() -> Unit,
): Route

public fun <A : Error, B : Error> Route.errorBoundary(
    // no union: two separate contexts
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
            if (!isAdmin) return@get handleError(AdminOnly)            // resolves to adminErrors
            users.find(id) ?: return@get handleError(UserNotFound(id)) // resolves to userErrors
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
    on<SQLTransientConnectionException> { e -> call.respond(HttpStatusCode.ServiceUnavailable) } // specific first
    on<SQLException> { call.respond(HttpStatusCode.InternalServerError) }                       // then the hierarchy
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

Exceptions are classes thrown at runtime, so their boundaries match at runtime:

- **Matching:** `on<T>` matches `T` and its subtypes, interfaces included.
    - Fast path: a table lookup by the exception's exact class.
    - Slow path: the handlers are checked one by one with `isInstance`, in declaration order. The first match wins, so
      specific types go first, as with `catch` blocks.
    - Both paths use only `KClass` equality and `isInstance`, so they behave the same on every platform.
- **Unlisted exceptions** go to the outer `exceptionBoundary`, then `globalExceptionBoundary`, then a 500 with no
  message.
- **Skipped because the call was already sent:** the outer boundaries are skipped too, and the exception is logged.
- **Causes** aren't unwrapped.

## Combining

```kotlin
// errors: separate contexts, resolved at compile time (see Error boundaries)
errorBoundary(userErrors, adminErrors) { /* ... */ }

// exceptions: several handlers in one boundary are joined in order; the same T in two handlers: the last wins
// a subtype match (slow path) still takes the first in that joined order
exceptionBoundary(dbFailures, httpFailures) { /* ... */ }

// to override another handler, nest: the inner boundary runs first
exceptionBoundary(defaultExceptions) {
    exceptionBoundary(dbFailures) { /* ... */ }
}

// errors and exceptions: nest the two boundaries; a combined scope would need an overload for every mix of counts
exceptionBoundary(dbFailures) {
    errorBoundary(userErrors) { /* ... */ }
}
```

## Framework defaults

Ktor exports handlers that apply its own status codes and required headers. Put the defaults in outer boundaries, so
your handlers inside override them.

```kotlin
public typealias KtorError = ReceiveFailure | InvalidParameters | MissingParameter | InvalidParameter | ValidationFailed
public val defaultErrors: ErrorHandler<KtorError>        // one handler: covers receiveValidated's whole union
public val Validation.Companion.defaultErrors: ErrorHandler<ValidationFailed> // 422, the violations as the body
public val defaultExceptions: ExceptionHandler           // BadRequestException -> 400, NotFoundException -> 404,
// UnsupportedMediaTypeException -> 415, PayloadTooLargeException -> 413, ...

exceptionBoundary(defaultExceptions) {
    exceptionBoundary(dbFailures) {                      // inner: runs first, so it overrides the defaults
        errorBoundary(defaultErrors) {
            errorBoundary(appErrors) { /* ... */ }
        }
    }
}
```

- **One handler for Ktor's errors:** `receiveValidated` returns `ReceiveFailure | ValidationFailed`, and `handleError`
  passes it as one value. Split across `defaultErrors` and `Validation.defaultErrors`, no single context would accept
  it.
- **Nesting, not argument order:** an inner `exceptionBoundary` runs before the outer one, so a default's general
  `on<T>` can't take matches from your more specific one.

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
- **Unlisted coroutine exceptions:** logged with the coroutine name, `IOException` included. Only`CancellationException`
  is skipped.
- **Specific types only:** the global boundary has no `Throwable` catch-all.

## Decoding parameters

```kotlin
@Serializable
data class Search(val q: String, val page: Int = 1, val tags: List<String> = emptyList()) // repeated keys -> lists

val search = call.queryParameters.decodeOrError<Search>().ifError { return@get handleError(it) }

call.pathParameters.decodeOrError<UserPath>()
call.request.headers.decodeOrError<ClientHeaders>()   // @SerialName("X-Request-Id") val requestId: String
call.request.cookies.decodeOrError<Prefs>()
call.receiveFormOrError<SignUp>()                     // urlencoded body: ReceiveFailure | InvalidParameters
get<Users.ById> { resource -> /* ... */ }             // Resources: the same decoder and errors

call.response.headers(RateLimitHeaders(remaining = 10)) // encoding throws only on bugs, so there's no OrError twin
```

```kotlin
// every problem at once
public enum class ParameterLocation { Path, Query, Header, Cookie, Form }
public class ParameterProblem(
    public val name: String,
    public val location: ParameterLocation,
    public val value: String?,                     // null: missing
    public val cause: Throwable?,                  // the parse failure
)
public error class InvalidParameters(public val problems: List<ParameterProblem>)

// one element stays getOrError<T>(name): MissingParameter | InvalidParameter

// throwing twins: the exception carries the error
call.queryParameters.decodeOrThrow<Search>()   // InvalidParametersException(error) : BadRequestException
call.receiveFormOrThrow<SignUp>()              // receive's exceptions, or InvalidParametersException
call.queryParameters.getOrFail<Int>("page")    // one element: already exists (Parameters.kt:56)
```

## Validation

```kotlin
public sealed interface ViolationLocation {
    public class Body(public val pointer: String) : ViolationLocation  // "#/address/city"
    public class Parameter(public val name: String, public val location: ParameterLocation) : ViolationLocation
}
public class Violation(
    public val location: ViolationLocation?,
    public val message: String,
    public val code: String? = null
)
public error class ValidationFailed(public val violations: List<Violation>)

public fun interface Validator<T> {
    public suspend fun validate(value: T): T | ValidationFailed // the normalized value, or every violation
}
```

The validator's lambda returns the normalized value. If it ends in `Unit`, the input passes through unchanged. The two
`validator` overloads are told apart by the lambda's return type:

```kotlin
@OverloadResolutionByLambdaReturnType
public fun <T> validator(
    check: suspend ValidationScope.(T) -> Unit,  // checks only: the input is returned as is
): Validator<T>

@OverloadResolutionByLambdaReturnType
@JvmName("normalizingValidator")
public fun <T> validator(
    normalize: suspend ValidationScope.(T) -> T, // checks, then the value to return
): Validator<T>
```

- **Recorded violations:** the returned value is discarded, and the caller gets `ValidationFailed`.
- **Running order:** the code that builds the returned value runs after the checks above it, even when they recorded
  violations. It can't assume the input is valid.

```kotlin
val tagValidator =
    validator<Tag> { tag -> ensure(tag.name.length <= 20, at = "name") { "too long" } } // Unit: passes through

val addressValidator = validator<Address> { address ->
    ensure(address.city.isNotBlank(), at = "city") { "must not be blank" }
    address.copy(city = address.city.trim())
}

val userValidator = validator<User> { user ->
    ensure(user.name.isNotBlank(), at = "name", code = "user.name.blank") { "must not be blank" } // records, continues
    if (user.email.isBlank()) {
        violation(at = "email") { "must not be blank" }
        return@validator user                              // dependent checks: plain code; the value is discarded
    }
    ensure(isEmail(user.email), at = "email") { "invalid format" }

    // nested values are explicit: a block, or a validator passed by name;
    val address =
        nested("address", user.address, addressValidator) // pointers become #/address/...; returns the normalized value
    each("tags", user.tags) { tag ->                          // any Iterable: #/tags/2/... (eachValue for maps)
        ensure(tag.length <= 20) { "too long" }
    }

    user.copy(name = user.name.trim(), address = address)
}
```

A `validationBoundary` provides each validator as a context parameter, like `errorBoundary`. `validated()` and its
variants need a `Validator<T>` context for their type:

```kotlin
public fun <A> Route.validationBoundary(a: Validator<A>, build: context(Validator<A>) Route.() -> Unit): Route
public fun <A, B> Route.validationBoundary(
    a: Validator<A>,
    b: Validator<B>,
    build: context(Validator<A>, Validator<B>) Route.() -> Unit,
): Route
// ... overloads up to a fixed arity

context(validator: Validator<T>)
public suspend fun <T> T.validated(): T | ValidationFailed = validator.validate(this)

context(validator: Validator<T>)
public suspend inline fun <reified T> ApplicationCall.receiveValidated(): T | ReceiveFailure | ValidationFailed
```

```kotlin
errorBoundary(defaultErrors) {
    validationBoundary(userValidator, addressValidator) {
        post("/users") {
            val user = call.receiveValidated<User>()
                .ifError { return@post handleError(it) } // ReceiveFailure | ValidationFailed
            call.respond(users.create(user))                                // user.name is already trimmed
        }
    }
}

val user = call.receiveOrError<User>()|.validated()                // the same, in two steps
val search = call.queryParameters.decodeValidated<Search>()        // InvalidParameters | ValidationFailed
val to =
    call.queryParameters.getValidated<Email>("to")            // MissingParameter | InvalidParameter | ValidationFailed
// parameter violations get ViolationLocation.Parameter automatically: { "parameter": "page", "in": "query" }

user.validated(userValidator)                                      // an explicit validator: no boundary needed

// throwing twins: ValidationException(error), 422 through defaultExceptions
call.receive<User>().validatedOrThrow()
call.receiveValidatedOrThrow<User>()
call.queryParameters.decodeValidatedOrThrow<Search>()
call.queryParameters.getValidatedOrThrow<Email>("to")
```

- **Resolved at compile time:** `receiveValidated<User>()` needs a `Validator<User>` in scope. A missing validator
  is a compile error, not a runtime failure. Nothing is looked up or matched at runtime.
- **Nesting and overlap:** the closest boundary wins. Two validators for the same type in one `validationBoundary` are a
  context ambiguity error.
- **Subtypes:** `Validator<T>` is invariant, so a `Validator<Shape>` doesn't validate a `Circle` implicitly. Use
  `circle.validated(shapeValidator)` or `circle.validated<Shape>()`, which returns a `Shape`.
- **Infrastructure failures** (for example, the database is down) stay exceptions and go to an exception handler.

# Technical Details

[technical-details]: #technical-details

## Boundaries

- **`errorBoundary` installs nothing:** it passes its `ErrorHandler` values to `build` as context arguments.
- **`validationBoundary` installs nothing either:** it passes the validators as context arguments.
- **`exceptionBoundary`** installs a route-scoped plugin. The plugin stores its handlers in the call's attributes, and
  the inner boundaries run first.

## Context resolution with generic errors

`handleError` is generic in `E`, and its context `ErrorHandler<E>` is resolved while `E` is still being inferred.
This was checked on the current Kotlin compiler with context parameters enabled. Sealed interfaces stood in for error
unions, and the boundary was invariant:

```kotlin
boundary(userErrors) {
    boundary(adminErrors) {
        handleError(UserNotFound(2))  // resolves to the outer userErrors
    }
}
boundary(userErrors, adminErrors) {
    handleError(UserNotFound(3))      // resolves to userErrors: no ambiguity
    handleError(AdminOnly)            // resolves to adminErrors
}
boundary(userErrors, moreUserErrors) {
    handleError(UserNotFound(5))      // compile error: Multiple potential context arguments for 'boundary: Boundary<E>'
}
```

The compiler checks each candidate's constraints during the scope walk. A boundary that doesn't accept the error is
skipped, both when nested and at the same level. It still needs checking with real error unions once KEEP-0462 lands.

## Global boundary plumbing

- `Application`'s `SupervisorJob` (Application.kt:133) gets a `CoroutineExceptionHandler` that dispatches to the
  coroutine handlers.
- `DefaultUncaughtExceptionHandler` is used by Netty (`:321`), CIO (`HttpServer.kt:54`), Jetty and servlet. It delegates
  to the same handlers before its default logging.
- How the engines find the boundary is an implementation detail.

## Decoder location

- `ResourcesFormat.decodeFromParameters` moves to `ktor-http`, which already depends on `kotlinx-serialization-core`.
- Resources then delegates to it and maps `ResourceSerializationException` to `InvalidParameters`.

## Migration

```kotlin
install(StatusPages) { /* ... */ }       // 4.0: a deprecated adapter; exception<T> maps to on<T>;
// status(code) handlers survive only inside the adapter
install(RequestValidation) { /* ... */ } // ValidationResult.Invalid(reasons) -> ValidationFailed(reasons.map { Violation(null, it) });
// validateOnReceive = true keeps today's automatic, throwing behaviour for one major
```

# Drawbacks

[drawbacks]: #drawbacks

- **It depends on KEEP-0462 and on context parameters (KEEP-0367)**, and on the Kotlin version that ships both. Until
  then, the design can't be released.
- **Errors are sent, not returned:** a route that builds an error and forgets `handleError` compiles. Only the
  KEEP-0462 traceability and must-use warnings catch it, and the route falls through to today's 404. A `handleError`
  without `return@get` keeps the route running after the error was handled.
- **OpenAPI can't read a route's errors from its type:** they are in the contexts in scope, which a compiler plugin can
  read and runtime introspection can't.
- **Fixed arity:** `errorBoundary(a, b, c)` exists only up to the largest overload.
- **There are two failure channels to learn:** errors for expected outcomes, exceptions for everything else.
- **Exception order is easy to get wrong:** a general `on<T>` before a specific one takes its matches.

# Advantages

[advantages]: #advantages

- **Compiler-checked errors:** a route can't send an error that no boundary handles, and a handler can't miss a case.
- **No runtime dispatch for errors:** the compiler binds each `handleError` to one handler.
- **Reusable handlers and validators:** they are values scoped to route subtrees, like `authenticate`, instead of one
  application-wide table.
- **One boundary covers everything:** calls and coroutines both go to the global boundary, and nothing is dropped
  silently.
- **One decoder for all parameters:** query, path, header, cookie, form and Resources share it and report every problem
  at once.
- **Explicit validation:** it works on bodies and parameters alike, returns typed violations with locations, and
  normalizes in the same step.
- **If we don't do this:** StatusPages and RequestValidation keep their runtime matching and untyped results, and every
  user rebuilds parameter decoding by hand.

# Open Questions

[open-questions]: #open-questions

- Global boundary: `onCoroutineFailure<T>` or a `coroutines { }` scope?
- Exception matching: a subtype match takes the first handler in declaration order, while the same `on<T>` in two
  combined handlers takes the last. Is that mix clear enough, or should combined handlers be searched from the last
  one back?
- How many handlers should the `errorBoundary` and `validationBoundary` overloads take?
- Names: `defaultErrors` / `ktorErrors` / `standardErrors`, and `handleError`.
- `ParameterProblem.cause`: a `Throwable`, or a typed reason (`Missing`, `Malformed(type)`)?

# Future Directions

[future-directions]: #future-directions

- **Plugins that reject calls:** RateLimit, CORS, CSRF and CallId would report typed errors to their own hooks, like
  auth's `onUnauthorized`. A route scope could send them to the error handler in scope. The hook wraps its call in a
  `RoutingContext`, so `handleError` works there too:
  ```kotlin
  install(RateLimit) { onRateLimited { e -> call.respond(HttpStatusCode.TooManyRequests, "Retry in ${e.retryAfter}") } }

  errorBoundary(apiErrors) {
      rateLimit(RateLimitName("api"), onRateLimited = { handleError(it) }) { /* compiles only if ApiError covers RateLimited */ }
  }
  ```
- **Problem details:** RFC 9457 responses as a separate proposal. The default handlers would use them when they are
  installed.
- **Server core, auth and sessions:** `receiveOrError`, typed authentication and session errors (follow-up KLIP).
