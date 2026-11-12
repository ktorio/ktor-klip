|             |                                                                                                                 |
|-------------|-----------------------------------------------------------------------------------------------------------------|
| Feature     | Typed parameters: query, path, header, cookie and form parameters as classes, on the server and the client      |
| Submitted   | 2026-11-12                                                                                                      |
| Accepted    | No                                                                                                              |
| Issue       | https://youtrack.jetbrains.com/issue/KTOR-9977/Exception-Handling-Request-Validation-and-Status-Pages-Workgroup |
| Preceded by | 0011 Rich errors on the server: error and exception handling                                                    |
| Followed by | 0013 Rich errors: validation                                                                                    |

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

The format that Resources uses to read and write parameters moves to `ktor-http`, so the server and the client share
it. A `@Serializable` class can then be read from, or written to, the places that hold parameters: the query, the
path, headers and urlencoded forms. Cookies can be read into a class, but not written from one.

- **The server decodes requests** into classes and encodes response headers from them.
- **The client encodes requests** from the same classes and decodes response headers and cookies into them.
- **Decoding reports every problem at once** as one rich error, `InvalidParameters`
  ([KEEP-0462](https://github.com/Kotlin/KEEP/blob/main/proposals/KEEP-0462-rich-errors.md)), and has a throwing
  twin. On the server, the errors go to handlers as described in 0011.

# Motivation

[motivation]: #motivation

- **Parameters are parsed one at a time:** each one needs its own lookup, parsing and error handling.
- **Only Resources decodes a class:** and only for path and query, with an exception (`ResourceSerializationException`)
  that carries no per-parameter detail.
- **Headers, cookies and forms have no class decoding at all.**
- **The client writes parameters one at a time too,** and turns every value into text with `toString()`. Nothing
  checks that the client sends what the server expects.

# Current Solutions

[current-solutions]: #current-solutions

```kotlin
// server: today each parameter is read and parsed separately
val page = call.queryParameters["page"]?.toIntOrNull() ?: 1
val requestId = call.request.headers["X-Request-Id"] ?: throw BadRequestException("X-Request-Id is missing")

// server: a form body can only be read as a raw Parameters map; call.receive<SignUp>() has no converter for forms
val form = call.receiveParameters()

// server: the Resources plugin decodes a class, but only from path and query parameters
get<Users.ById> { /* ... */ } // a bad ?page=x throws ResourceSerializationException
```

```kotlin
// client: one value at a time
client.get("/search") {
    parameter("q", "ktor")
    parameter("page", 2)
}
client.submitForm("/signup", formParameters = parameters { append("name", name); append("email", email) })
```

# Design Overview

[design-overview]: #design-overview

The server examples send errors to a handler of their own, `decodeErrors`. Each error also has a
default response registered (0011, Default responses), so `respondDefaultError` works without a handler.

```kotlin
val decodeErrors = errorHandler<DecodeError> { error ->
    call.respond(HttpStatusCode.BadRequest, error.toString())
}
```

### 1. The server decodes a class

```kotlin
@Serializable
data class Search(val q: String, val page: Int = 1, val tags: List<String> = emptyList())

errorBoundary(decodeErrors) {
    get("/search") {
        val search = call.queryParameters.decodeOrError<Search>().ifError { return@get respondError(it) }
        call.respond(index.find(search))
    }
}
```

### 2. From every source

```kotlin
call.pathParameters.decodeOrError<UserPath>()
call.request.headers.decodeOrError<ClientHeaders>()
call.request.cookies.decodeOrError<Prefs>()
call.receiveFormOrError<SignUp>()
```

### 3. Every problem at once

```kotlin
// GET /search?page=x  ->  InvalidParameters([q: missing, page: "x" isn't an Int])
```

### 4. A throwing twin

```kotlin
val search = call.queryParameters.decodeOrThrow<Search>() // throws InvalidParametersException, a BadRequestException
```

### 5. The client encodes the same class

```kotlin
client.get("/search") { parameters(Search("ktor", page = 2)) } // sends ?q=ktor&page=2
client.submitForm("/signup", SignUp(name, email))             // sends a urlencoded body
```

### 6. The client decodes a response

```kotlin
val limits = response.headers.decodeOrError<RateLimitHeaders>() // RateLimitHeaders | InvalidParameters
```

# Design Details

[design-details]: #design-details

The design has three parts. The format and the errors are shared and live in `ktor-http`. The server and the client
each add the functions that connect the format to their own requests and responses.

|                                               | Server                                                   | Client                                     |
|-----------------------------------------------|----------------------------------------------------------|--------------------------------------------|
| Decode (can fail with `InvalidParameters`)    | query, path, request headers, request cookies, form body | response headers, response cookies         |
| Encode (throws; no `OrError` version)         | response headers                                         | query, request headers, form body          |

## Shared format

### Rules

One format reads and writes all parameters. A class maps to parameters by these rules:

- **A property is one parameter.**
- **Decoding needs every parameter, with two exceptions.** A property with a default value takes that value when
  its parameter is missing, and a nullable property becomes `null`.
- **Header names match case-insensitively.**

What a list property means depends on where the parameters go, because not every place can hold several values for
one name:

| Place  | A list property                                                                                    |
|--------|----------------------------------------------------------------------------------------------------|
| Query  | The name is repeated: `tags=a&tags=b`.                                                             |
| Form   | The name is repeated, as in the query.                                                             |
| Header | One header line with the values separated by commas: `X-Tags: a, b`.                               |
| Cookie | Not allowed. A cookie name holds one value.                                                        |
| Path   | Allowed only for a path segment that takes the rest of the path, such as `{name...}` in Resources. |

- **A header list is read from either form.** The decoder accepts one line, several lines or a mix.
- **Commas inside an element are not checked.** An element that contains a comma is written as it is, and the
  receiver reads it as two elements.
- **Repeated query names are valid HTTP.** The URL standard describes a query as a list of name and value pairs and
  lets a name appear more than once. Ktor's `Parameters` already stores several values for one name.
- **Cookies are decoded but never encoded.** A cookie that is read is only a name and a value, so a class holds all
  of it. A cookie that is set also needs a path, a domain, an expiry time and flags, and a property can't carry
  them. Cookies are still set with today's functions.
- **A property that is itself a class is not allowed.** Nested classes are rejected for now, in every place.
- **A class that breaks these rules is a programming mistake.** For example, a list property in a class that is
  decoded from cookies. Both encoding and decoding throw for it. It is not reported as `InvalidParameters`, because
  the request isn't at fault.

### Errors

```kotlin
public enum class ParameterLocation { Path, Query, Header, Cookie, Form }

public class ParameterProblem(
    public val name: String,
    public val location: ParameterLocation,
    public val value: String?,                     // null when the parameter is missing
    public val cause: Exception?,                  // why parsing failed, if it did
)
public error class InvalidParameters(public val problems: List<ParameterProblem>)

// a single parameter is still read with getOrError<T>(name), which returns one of these two
public error class MissingParameter(public val name: String, public val location: ParameterLocation)
public error class InvalidParameter(
    public val name: String,
    public val location: ParameterLocation,
    public val value: String
)
```

- **The location is known:** each source sets its `ParameterLocation`, so an error (and a violation in 0013) says
  where a parameter came from.
- **The same errors on both sides:** the client's decoding returns `InvalidParameters` too.

### Decoding and encoding functions

```kotlin
public inline fun <reified T> Parameters.decodeOrError(): T | InvalidParameters
public inline fun <reified T> Headers.decodeOrError(): T | InvalidParameters

// the throwing twins
public inline fun <reified T> Parameters.decodeOrThrow(): T
public inline fun <reified T> Headers.decodeOrThrow(): T

public class InvalidParametersException(public val error: InvalidParameters) : BadRequestException("...")
```

- **`Headers` is the same type on both sides,** so `call.request.headers.decodeOrError<T>()` on the server and
  `response.headers.decodeOrError<T>()` on the client are one function.
- **Encoding has no `OrError` version.** The encoding functions throw, because encoding almost never fails because
  of a value:
    - A missing serializer, or a class that breaks the rules above, is a programming mistake.
    - A value can't make encoding into the query or a form fail. Every character is escaped.
    - A value can make encoding into headers fail. A header value with a line break or another control character
      throws `IllegalHeaderValueException`, as `header(name, value)` does today. Text that comes from a user should
      be checked before the request is built, for example with a validator from 0013.

## Server

### Decoding a request

```kotlin
public inline fun <reified T> RequestCookies.decodeOrError(): T | InvalidParameters
public suspend inline fun <reified T> ApplicationCall.receiveFormOrError(): T | ReceiveFailure | InvalidParameters
```

```kotlin
@Serializable
data class Search(val q: String, val page: Int = 1, val tags: List<String> = emptyList())
// ?tags=a&tags=b is decoded into tags = [a, b]

@Serializable
data class ClientHeaders(@SerialName("X-Request-Id") val requestId: String, val accept: String? = null)

call.queryParameters.decodeOrError<Search>()
call.pathParameters.decodeOrError<UserPath>()
call.request.headers.decodeOrError<ClientHeaders>()
call.request.cookies.decodeOrError<Prefs>()
call.receiveFormOrError<SignUp>()                     // a form body; reading it can also fail with ReceiveFailure
get<Users.ById> { resource -> /* ... */ }             // the Resources plugin uses the same decoder and errors

typealias DecodeError = InvalidParameters | MissingParameter | InvalidParameter
// DecodeError is an alias used only by the examples in this document; it isn't part of the API
```

- `ReceiveFailure` comes from the server core follow-up KLIP.

### Throwing twins

```kotlin
call.queryParameters.decodeOrThrow<Search>()   // throws InvalidParametersException, which carries the error
call.receiveFormOrThrow<SignUp>()              // throws what receive throws, or InvalidParametersException
call.queryParameters.getOrFail<Int>("page")    // a single parameter: this function exists today
```

### Encoding response headers

```kotlin
@Serializable
data class RateLimitHeaders(
    @SerialName("X-RateLimit-Remaining") val remaining: Int,
    @SerialName("Retry-After") val retryAfter: Int? = null,
)

call.response.headers(RateLimitHeaders(remaining = 10)) // sets X-RateLimit-Remaining: 10; Retry-After is left out
```

## Client

### Encoding a request

```kotlin
client.get("/search") { parameters(Search("ktor", page = 2)) }   // query: ?q=ktor&page=2
client.get("/me") { headers(ClientHeaders(requestId = callId)) } // headers: X-Request-Id
client.submitForm("/signup", SignUp(name, email))                // a urlencoded form body
client.get(Users.ById(1))                                        // typed paths: the Resources plugin, same encoder
```

- **A class written by the client is read back by the server.** Both sides use one format, so if they share the class,
  the server decodes exactly what the client encoded.

### Decoding a response

```kotlin
// headers
val limits = response.headers.decodeOrError<RateLimitHeaders>() // RateLimitHeaders | InvalidParameters
val limits = response.headers.decodeOrThrow<RateLimitHeaders>() // throws InvalidParametersException

// cookies
public inline fun <reified T> List<Cookie>.decodeOrError(): T | InvalidParameters

val prefs = client.cookies("https://example.com").decodeOrError<Prefs>() // the cookies stored for this URL
```

Cookies reach the client in `Set-Cookie` response headers. Ktor already parses them into a `List<Cookie>`, and the
class is decoded from that list:

- **`client.cookies(url)` is the source to use.** It returns the cookies that the `HttpCookies` plugin has stored for
  a URL. It holds every cookie the server has set so far, and expired cookies are already removed.
- **The function also works on `response.setCookie()`,** which returns only the cookies that one response sets. A
  server rarely sets all of its cookies in one response, so a class with required properties often fails to decode
  from it.
- **Only names and values are decoded.** A cookie's other attributes, such as its expiry time and path, are ignored.

# Technical Details

[technical-details]: #technical-details

## Format location

- `ResourcesFormat`'s encoder and decoder move to `ktor-http`, which already depends on
  `kotlinx-serialization-core`. The server and the client both depend on `ktor-http`.
- Resources then delegates to it and maps `ResourceSerializationException` to `InvalidParameters`.
- The decoder collects problems instead of stopping at the first one, so `InvalidParameters` lists them all.
- The encoder keeps today's behaviour: every property is written, and a `null` is skipped.

## Sources

- **Headers and cookies** are adapted to the same name-to-values view as `Parameters`. For a list property, a
  header's lines are joined and split at commas first.
- **Forms** on the server read the urlencoded body into `Parameters`, as `receiveParameters()` does today, and then
  decode it. On the client, the class is encoded into `Parameters` and sent as `submitForm` sends them today.

# Drawbacks

[drawbacks]: #drawbacks

- **Decoding depends on KEEP-0462** for `T | InvalidParameters`. The throwing twins and all encoding don't.
- **Two ways to read a parameter:** one at a time with `getOrError` / `getOrFail`, or a class with `decodeOrError`.
- **The rules differ by place:** a class with a list property works for a query and fails for cookies, and the
  compiler can't check that.

# Advantages

[advantages]: #advantages

- **One format for all parameters:** query, path, header, cookie, form and Resources share it.
- **One class for both sides:** the client encodes the class that the server decodes.
- **List every problem at once**, with names, locations and values.

# Open Questions

[open-questions]: #open-questions

- Names: `parameters(value)` sits next to today's `parameters { }` builder function, and `headers(value)` next to
  `headers { }`. Distinct names such as `encodeParameters(value)` and `encodeHeaders(value)` would avoid the
  overlap and would mirror `decodeOrError`.
- Should the client have a `headersOrError(value)` for header values that come from user input? It would return the
  illegal value as an error instead of throwing.

# Future Directions

[future-directions]: #future-directions

- **Validation:** 0013 put the parameter location into violations.
- **Nested classes:** they could be flattened with prefixes, for example `address.city=Kyiv` in a query. This can be
  added later without breaking anything.
