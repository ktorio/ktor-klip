# Summary
[summary]: #summary

Improve server-side rate limiting in Ktor with a rate limiter that refills its bucket gradually, access to rate limiter stats, and use of `kotlin.time`. Add client-side rate limiting and throttling to control how often requests are sent.

# Motivation
[motivation]: #motivation

The server rate limiter now has just one refill policy: the whole bucket is refilled at once after a set time. Gradual refills would let requests start again sooner and keep the load more steady ([KTOR-8024](https://youtrack.jetbrains.com/issue/KTOR-8024)).

Apps also need rate limiter stats to show users how much of their limit is left and track where and when limits are reached. Most of the classes that hold these stats are internal, so the stats are hard to get today ([KTOR-8103](https://youtrack.jetbrains.com/issue/KTOR-8103)).

Rate limiters track time through a lambda that returns `getTimeMillis()` by default and can be replaced in tests to use fake time. The types in `kotlin.time` are a better fit for both tracking time and setting fake time in tests ([KTOR-9534](https://youtrack.jetbrains.com/issue/KTOR-9534)).

Ktor Client has no built-in support for rate limiting or throttling, so users need their own code or third-party plugins to control how often requests are sent ([KTOR-6248](https://youtrack.jetbrains.com/issue/KTOR-6248)). Building this into Ktor would let the client share rate limiting code with the server, so both gain from the same changes and fixes.

# Current Solutions
[current-solutions]: #current-solutions

## Server

Users can write a rate limiter with gradual refills by implementing the `RateLimiter` interface and using it in the `RateLimit` plugin. This gives them control over the refill policy but requires them to write and test the code.

The plugin has no public API to expose stats for its rate limiters, so apps cannot query them through the plugin.

Users can track time with `kotlin.time`, but they must wrap it in a `() -> Long` lambda and convert time values to milliseconds to fit the current API.

## Client

The [Ktor Client Throttle plugin](https://github.com/brudaswen/ktor-client-throttle) lets users set how many requests the client can send, how often the limit resets, and whether to retry when the limit is reached. Its [last release](https://github.com/brudaswen/ktor-client-throttle/releases/tag/1.0.0) was on October 29, 2025, about a year ago.

On the JVM, [Resilience4j's Kotlin support](https://resilience4j.readme.io/docs/getting-started-4) lets users cap how many times a suspend function can be called in a given time span and delay calls when that cap is reached. Using it with Ktor Client requires manual integration. It supports only full resets at fixed intervals, with no option for gradual refills.

# Design Overview
[design-overview]: #design-overview

Pain points:

- **Refill choice:** The server has only one built-in refill policy, so users who need gradual refills must implement their own.
- **Stats access:** Apps cannot query stats to show users if they have hit their limit, or to track which routes hit limits, how often, and when.
- **Time handling:** Raw `Long` timestamps do not express their time unit or clock source, which makes time math prone to errors. Using wall-clock timestamps to measure elapsed time may lead to refills being affected when the system clock is adjusted.
- **Client support:** Ktor Client has no built-in rate limiting or throttling, so users must add a third-party plugin or integrate a rate limiter themselves.
- **Separate APIs:** Users who need rate limiting on both the client and server must work with different APIs and refill rules, and may need to duplicate custom logic.

Outline:

- **Shared core:** Move the rate limiter API and code into a module that both client and server plugins can use.
- **Refill policies:** Keep the current full refill policy and add gradual refills.
- **Stats API for the Server:** Let each rate limiter expose its current state and add Micrometer metrics support to track use over time. Apps can also subscribe to rate limiting events to store their own stats.
- **Time handling:** Use `kotlin.time` types and a monotonic time source to measure elapsed time, with support for fake time in tests.
- **Client plugin:** Add rate limiting and throttling, with a choice to reject requests or wait when the limit is reached. Set a maximum wait time per provider, with per-request overrides for selected providers. There is no cap on how many requests can wait at once for now.

# Design Details
[design-details]: #design-details

## Shared RateLimiter Interface

Move `RateLimiter` to the `io.ktor.ratelimit` package in the shared `ktor-rate-limit` module so both client and server plugins use the same interface. A rate limiter uses tokens to track how many requests it can allow. Each request spends one or more tokens based on the calculated weight, and the refill policy sets when new tokens become available.

The interface has two suspend functions:

```kotlin
public suspend fun tryConsume(tokens: Int = 1): State
public suspend fun snapshot(): RateLimiterSnapshot
```

`tryConsume` is the existing method moved from the server's `RateLimiter` interface. It tries to consume the requested number of tokens and returns `State.Available` with the state after consumption, or `State.Exhausted` if there are not enough tokens.

The client and server plugins decide how to handle an exhausted limiter, such as rejecting the request or waiting before trying again.

`snapshot` returns the number of tokens left and the maximum number the limiter can hold, without consuming tokens:

```kotlin
public class RateLimiterSnapshot(
    public val remainingTokens: Int,
    public val limit: Int
)
```

The `snapshot` method throws `UnsupportedOperationException` by default so existing custom server rate limiters can keep working without implementing it. All built-in rate limiters implement this method.

## Providers and Rate Limiters

A provider is like a named factory that creates rate limiters based on request keys.

The request key groups requests that share a rate limiter. For example, if the key is a user ID, the limiter has 10 tokens refilled in full every minute, and each request's weight is calculated to be 1, then each user can make up to 10 requests per minute.

Both client and server providers have four main settings:

- **Name:** Identifies the provider.
- **Request key:** A function that calculates the key from the request.
- **Limiter factory:** A function that creates a rate limiter for each distinct key.
- **Request weight:** A function that calculates how many tokens the request consumes.

Key and weight functions use `ApplicationCall` on the server and `HttpRequestBuilder` on the client.

Requests with equal keys share the same limiter within a provider. By default, the request key is the same for all requests within a provider, so those requests share one rate limiter.

Providers can be selected for routes on the server or requests on the client, while a global provider applies to all requests.

## Incremental Refill

Add an incremental refill policy to the shared module, so both client and server providers can use it.

The existing `DefaultRateLimiter` restores all tokens at once after each `refillPeriod`. The new `IncrementalRefillRateLimiter` adds one token every `refillInterval` instead:

```kotlin
val fullRefill = RateLimiter.default(limit = 10, refillPeriod = 50.seconds)
val incrementalRefill = RateLimiter.incrementalRefill(limit = 10, refillInterval = 5.seconds)
```

If all 10 tokens are spent at once, the full refill limiter has no tokens until the 50-second period ends. With incremental refills, one token is added after 5 seconds, another after 10 seconds, and so on.

When `tryConsume` cannot consume enough tokens for a request, it returns `State.Exhausted` with a `toWait` duration. For full refills, `toWait` is the duration needed to refill the whole bucket; for incremental refills, it is the duration needed to replenish enough tokens for the request to proceed.

### Server Example

This sample lets each IP address make up to 5 login attempts at once, replenishing one attempt every 30 seconds:

```kotlin
install(RateLimit) {
    register(RateLimitName("login")) {
        incrementalRefillRateLimiter(limit = 5, refillInterval = 30.seconds)
        requestKey { call -> call.request.origin.remoteHost }
    }
}
routing {
    rateLimit(RateLimitName("login")) {
        post("/login") {
            // Check credentials and respond
        }
    }
}
```

### Client Example

This sample lets the client send up to 10 requests to a weather API at once, replenishing one token every second. When no tokens are left, requests wait up to 2 seconds before failing with `RateLimitRejectedException`:

```kotlin
val client = HttpClient {
    install(RateLimit) {
        register(RateLimitName("weather")) {
            incrementalRefillRateLimiter(limit = 10, refillInterval = 1.seconds)
            maxWaitTime = 2.seconds
        }
    }
}
client.get("https://weather.example.com/forecast?city=London") {
    rateLimit(RateLimitName("weather"))
}
```

## Time Handling with kotlin.time

Server rate limiters currently use a `() -> Long` lambda, which returns `getTimeMillis()` by default, for both refill timing and timestamps. Tests replace it to supply fake time. A `Long` timestamp carries no unit, and conversion between units is error-prone. Using the system clock to measure elapsed time can affect refills when the clock is adjusted.

Propose to use `TimeSource.WithComparableMarks` to measure elapsed time for refills and `Clock` to supply timestamps for response headers. By default, these are `TimeSource.Monotonic` and `Clock.System`, so adjusting the system clock does not affect refill timing.

Both `RateLimiter.default()` and `RateLimiter.incrementalRefill()` allow users to optionally supply a `timeSource` and a `clock`. The provider functions offer the same options on the client and server.

Tests can use `TestTimeSource` to advance refill time without waiting. This is a shorter version of the existing `testNestedRateLimitsWithSameNameAndTimeSource` server test:

```kotlin
@Test
fun testRefillWithFakeTime() = testApplication {
    val timeSource = TestTimeSource()
    install(RateLimit) {
        register {
            rateLimiter(limit = 1, refillPeriod = 5.seconds, timeSource = timeSource)
        }
    }
    routing {
        rateLimit {
            get("/hello") { call.respondText("Hello") }
        }
    }

    assertEquals(HttpStatusCode.OK, client.get("/hello").status)
    assertEquals(HttpStatusCode.TooManyRequests, client.get("/hello").status)
    timeSource += 5.seconds
    assertEquals(HttpStatusCode.OK, client.get("/hello").status)
}
```

Tests that check timestamps can also supply a custom `Clock`. Advancing the time source controls refills, while the clock controls the timestamps.

Propose deprecation of the old server overloads that take `clock: () -> Long`. Provider calls that use only `limit` and `refillPeriod` select the new overload by default.

## Server Stats

### Showing a User's Limits

One use case in [KTOR-8103](https://youtrack.jetbrains.com/issue/KTOR-8103) is to let an app show a user's remaining quota without consuming tokens. Propose `Application.getRateLimiter(providerName, key)` to get the rate limiter associated with the key, creating it if it does not exist yet.

