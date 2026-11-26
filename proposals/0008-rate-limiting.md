|             | |
|-------------|---|
| Feature     | Rate Limiting and Throttling |
| Submitted   | 2026-11-26 |
| Accepted    | No |
| Issue       | [KTOR-8024](https://youtrack.jetbrains.com/issue/KTOR-8024), [KTOR-8103](https://youtrack.jetbrains.com/issue/KTOR-8103), [KTOR-9534](https://youtrack.jetbrains.com/issue/KTOR-9534), [KTOR-6248](https://youtrack.jetbrains.com/issue/KTOR-6248) |
| Preceded by | |
| Followed by | |

### Contents

1. [Summary](#summary)
2. [Motivation](#motivation)
3. [Current Solutions](#current-solutions)
4. [Design Overview](#design-overview)
5. [Design and Technical Details](#design-and-technical-details)
6. [Drawbacks](#drawbacks)
7. [Advantages](#advantages)
8. [Open Questions](#open-questions)
9. [Future Directions](#future-directions)

<hr />

# Summary
[summary]: #summary

Improve server-side rate limiting in Ktor by adding a limiter that refills its bucket gradually, by giving Ktor users access to rate limiter stats, and by using `kotlin.time`. Add client-side rate limiting and throttling to control how often requests are sent.

# Motivation
[motivation]: #motivation

The server rate limiter has just one refill policy: the whole bucket is refilled at once after a set time. Gradual refills would let requests start again sooner and keep the load steadier ([KTOR-8024](https://youtrack.jetbrains.com/issue/KTOR-8024)).

One of the requested features is exposing rate limiter stats to show end users how much of their limit is left and track where and when limits are reached. Most of the classes that hold these stats are internal, so the stats are hard to get today ([KTOR-8103](https://youtrack.jetbrains.com/issue/KTOR-8103)).

Rate limiters track time with a lambda that returns `getTimeMillis()` by default and can be replaced in tests to use fake time. The types in `kotlin.time` are a better fit for both tracking time and setting fake time in tests ([KTOR-9534](https://youtrack.jetbrains.com/issue/KTOR-9534)).

Ktor Client has no built-in support for rate limiting or throttling, so users have to develop their own solutions or use third-party plugins to control how often requests are sent ([KTOR-6248](https://youtrack.jetbrains.com/issue/KTOR-6248)). Building this into Ktor would let the client share rate limiting code with the server, so both gain from the same changes and fixes.

# Current Solutions
[current-solutions]: #current-solutions

## Server

Users can introduce a rate limiter with gradual refills by implementing the `RateLimiter` interface. This gives them control over the refill policy but requires them to write and test the code.

The plugin has no public API to expose stats of its rate limiters, so the users cannot query them through the plugin.

Users can track time with `kotlin.time`, but they must wrap it in a `() -> Long` lambda and convert time values to milliseconds to fit the current API.

## Client

The [Ktor Client Throttle plugin](https://github.com/brudaswen/ktor-client-throttle) lets users set how many requests the client can send, how often the limit resets, and whether to retry when the limit is reached. Its [last release](https://github.com/brudaswen/ktor-client-throttle/releases/tag/1.0.0) was on October 29, 2025, about a year ago.

On the JVM, [Resilience4j's Kotlin support](https://resilience4j.readme.io/docs/getting-started-4) lets users cap how many times a suspend function can be called in a given time span and delay calls when that cap is reached. Using it with Ktor Client requires manual integration. It supports only full resets at fixed intervals, with no option for gradual refills.

# Design Overview
[design-overview]: #design-overview

Pain points:

- **Refill choice:** The server has only one built-in refill policy, so users who need gradual refills must implement their own.
- **Stats access:** Ktor users cannot query stats to show end users if they have hit their limit, or to track which routes hit limits, how often, and when.
- **Time handling:** Raw `Long` timestamps do not express their time unit or clock source, which makes time math prone to errors. Using wall-clock timestamps to measure elapsed time may lead to refills being affected when the system clock is adjusted.
- **Client support:** Ktor Client has no built-in rate limiting or throttling, so users must add a third-party plugin or implement the plugin themselves.
- **Separate APIs:** Users who need rate limiting on both the client and server must work with different APIs and refill rules, and may need to duplicate custom logic.

Outline:

- **Shared core:** Move the rate limiter API and shared code into a module that both client and server plugins can use.
- **Refill policies:** Keep the current full refill policy and add gradual refills.
- **Stats API for the Server:** Let each rate limiter expose its current state and add Micrometer metrics support to track use over time. Ktor users can also subscribe to rate limiting events to store their own stats.
- **Time handling:** Use `kotlin.time` types and a monotonic time source to measure elapsed time, with support for fake time in tests.
- **Client plugin:** Add rate limiting and throttling, with a choice to reject requests or wait when the limit is reached and an ability to set a maximum wait time per provider, with per-request overrides for selected providers.

# Design and Technical Details
[design-and-technical-details]: #design-and-technical-details

## Shared RateLimiter Interface

Move `RateLimiter` to the `io.ktor.ratelimit` package in the shared `ktor-rate-limit` module so both client and server plugins use the same interface.

A rate limiter uses tokens to track how many requests it can allow, and its refill policy sets when new tokens become available. The interface has two suspend functions:

```kotlin
public suspend fun tryConsume(tokens: Int = 1): State
public suspend fun snapshot(): RateLimiterSnapshot
```

The `tryConsume` is the existing method moved from the server's `RateLimiter` interface. It tries to consume the requested number of tokens and returns `State.Available` with the state after consumption, or `State.Exhausted` if there are not enough tokens.

The client and server plugins decide how to handle an exhausted limiter, such as rejecting the request or waiting before trying again.

The `snapshot` method reads the limiter's current quota without consuming tokens. It throws `UnsupportedOperationException` by default so existing custom server rate limiters can keep working without implementing it. All built-in rate limiters implement this method.

## Shared Types

Both plugins use `RateLimitName` to identify a provider, with `RateLimitName.global` for the global provider. The old server `RateLimitName` is deprecated.

```kotlin
@JvmInline
public value class RateLimitName(public val name: String) {
    public companion object {
        public val global: RateLimitName
    }
}
```

`RateLimiterKey` combines the provider name and request key to identify a limiter.

```kotlin
public class RateLimiterKey(
    public val providerName: RateLimitName,
    public val key: Any,
)
```

`RateLimiterSnapshot` holds the number of tokens left and the maximum number the limiter can hold.

```kotlin
public class RateLimiterSnapshot(
    public val remainingTokens: Int,
    public val limit: Int
)
```

## Providers and Rate Limiters

A provider is like a named factory that creates rate limiters based on request keys.

The request key groups requests that share a rate limiter. For example, if the key is a user ID, the limiter refills 10 tokens in full every minute, and each request's weight is 1, then each user can make up to 10 requests per refill period.

Both client and server providers have four main properties:

- **Name:** Identifies the provider.
- **Request key:** A function that calculates the key from the request.
- **Limiter factory:** A function that creates a rate limiter for each distinct key.
- **Request weight:** A function that calculates how many tokens the request consumes.

Key and weight functions accept `ApplicationCall` on the server and `HttpRequestBuilder` on the client.

The default weight is 1, and a weight of 0 uses no tokens. Both plugins throw `IllegalArgumentException` if `requestWeight` returns a negative value, before the request reaches that provider's limiter.

Requests with equal keys share the same limiter within a provider. By default, the request key is the same (`Unit`) for all requests within a provider, so they share one rate limiter.

Providers can be applied to routes on the server or requests on the client, while a global provider applies to all requests.

## Incremental Refill ([KTOR-8024](https://youtrack.jetbrains.com/issue/KTOR-8024))

Propose to add an incremental refill policy to the shared module, so both client and server providers can use it.

The existing `DefaultRateLimiter` restores all tokens at once after each `refillPeriod`. The new `IncrementalRefillRateLimiter` adds 1 token every `refillInterval` instead:

```kotlin
val fullRefill = RateLimiter.default(limit = 10, refillPeriod = 50.seconds)
val incrementalRefill = RateLimiter.incrementalRefill(limit = 10, refillInterval = 5.seconds)
```

If all 10 tokens are spent at once, the full refill limiter has no tokens until the 50-second period ends. With incremental refills, one token is added after 5 seconds, another after the next 5 seconds, and so on.

When `tryConsume` cannot consume enough tokens for a request, it returns `State.Exhausted` with a `toWait` duration. For full refills, `toWait` is the duration needed to refill the whole bucket; for incremental refills, it is the duration needed to replenish enough tokens for the request to proceed.

### Server Example

In the following code snippet the plugin lets each IP address make up to 5 login attempts at once, replenishing one attempt every 30 seconds:

```kotlin
install(RateLimit) {
    register(RateLimitName("login")) {
        incrementalRefillRateLimiter(limit = 5, refillInterval = 30.seconds)
        requestKey { call -> call.request.origin.remoteAddress }
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

In the following code snippet the client sends up to 10 requests to a weather API at once, replenishing one token every second. When no tokens are left, requests wait up to 2 seconds before failing with `RateLimitRejectedException`:

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

## Time Handling with kotlin.time ([KTOR-9534](https://youtrack.jetbrains.com/issue/KTOR-9534))

Server rate limiters currently use a `() -> Long` lambda, which returns `getTimeMillis()` by default, for both refill timing and timestamps. Tests replace it to supply fake time. A `Long` timestamp carries no time unit, and conversion between units is error-prone. Using the system clock to measure elapsed time can affect refills when the system clock is adjusted.

Propose to use `TimeSource.WithComparableMarks` to measure elapsed time for refills and `Clock` to supply timestamps for response headers. By default, these are `TimeSource.Monotonic` and `Clock.System`.

Both `RateLimiter.default()` and `RateLimiter.incrementalRefill()` allow users to optionally supply a `timeSource` and a `clock` arguments. The provider functions offer the same options on the client and server.

Tests can use `TestTimeSource` to advance refill time without waiting. This is an updated version of the existing `testNestedRateLimitsWithSameNameAndTimeSource` server test:

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

Propose deprecation of the old server overloads that take `clock: () -> Long`. Existing user code calling provider functions with `limit` and `refillPeriod` arguments only will select the new overload by default.

## Server Stats ([KTOR-8103](https://youtrack.jetbrains.com/issue/KTOR-8103))

### Showing quota for the user

One of the use cases is to let an app show a user's remaining quota without consuming tokens. Propose `Application.getRateLimiter(providerName, key)` to get the rate limiter associated with the key, creating it if it does not exist yet. The new shared `RateLimit` interface has a `snapshot()` method that returns the number of tokens left and the maximum number of tokens the bucket can hold.

The following code snippet shows how to let each signed-in user download up to 10 reports in each one-hour refill period and check how many downloads they have left. Authentication is assumed to be configured:

```kotlin
val reports = RateLimitName("reports")
install(RateLimit) {
    register(reports) {
        rateLimiter(limit = 10, refillPeriod = 1.hours)
        requestKey { call -> call.principal<UserIdPrincipal>()!!.name }
    }
}
routing {
    authenticate {
        rateLimit(reports) {
            get("/reports/download") {
                // Generate and send the report
            }
        }
        get("/reports/quota") {
            val userId = call.principal<UserIdPrincipal>()!!.name
            val quota = application.getRateLimiter(reports, userId).snapshot()
            call.respondText("${quota.remainingTokens} of ${quota.limit} downloads left")
        }
    }
}
```

Both routes use the same user ID as the key. The quota route is outside `rateLimit(reports)`, so checking the quota spends no tokens and still works after the user reaches the limit.

To read the user's quota, we need the same rate limiter object that limits their other requests. If the rate limiter doesn't exist yet, we need to create it. However, the current factory takes an `ApplicationCall` and we cannot reliably use the `ApplicationCall` from a quota request to identify the rate limiter for the other endpoints, since those call objects may differ. We therefore propose `rateLimiterFactory { key -> ... }` to create limiters from the request key alone. The key must contain all the data needed to create the limiter, so `Application.getRateLimiter(providerName, key): RateLimiter` can create it if needed and the plugin can reuse it for requests with the same provider name and key. We would deprecate the old `rateLimiter { call, key -> ... }` overload and keep it as a fallback when handling requests subject to limit.

The `getRateLimiter()` method throws `IllegalStateException` if the limiter doesn't exist yet and the provider uses only the deprecated factory. To let `getRateLimiter()` create a missing limiter, Ktor users need to migrate to the shared `RateLimiter` interface and use `rateLimiterFactory`, which accepts only the request key parameter.

### Collecting Server Stats

Backend teams need to track when and how often requests hit rate limits on each route, for example, to decide if those limits need to change.

The server `RateLimit` plugin emits `RateLimitEvent` through `Application.monitor` each time a rate limiter allows or rejects a request.

```kotlin
// Event definition used with the application's Events instance (Application.monitor).
public val RateLimitEvent: EventDefinition<RateLimitEventData> = EventDefinition()

// Represents the data carried by the event.
public sealed class RateLimitEventData(
    public val limiter: RateLimiterKey,
    public val route: Route?,
    public val method: HttpMethod,
) {
    public class Allowed(
        limiter: RateLimiterKey,
        route: Route?,
        method: HttpMethod,
        public val tokensLeft: Int,
        public val tokensTotal: Int,
        public val fullRefillAt: Instant,
    ) : RateLimitEventData(limiter, route, method)

    public class Rejected(
        limiter: RateLimiterKey,
        route: Route?,
        method: HttpMethod,
        public val toWait: Duration,
    ) : RateLimitEventData(limiter, route, method)
}
```

The properties of those classes are self-explanatory. The `route` will be `null` for the events from the global rate limiter. The `Allowed` and `Rejected` classes correspond to the `RateLimiter.State.Available` and `RateLimiter.State.Exhausted` states returned by `RateLimiter.tryConsume()`, respectively.

The following example uses `RateLimitEvent` to count rejections by route and reports the counts.

```kotlin
val rejectionsByRoute = java.util.concurrent.ConcurrentHashMap<String, Long>()

monitor.subscribe(RateLimitEvent) { event ->
    if (event !is RateLimitEventData.Rejected) return@subscribe
    val path = event.route?.path() ?: return@subscribe
    rejectionsByRoute.merge(path, 1L) { count, increment -> count + increment }
}

routing {
    get("/stats/rejections") {
        val stats = rejectionsByRoute.entries.joinToString("\n") { (path, count) ->
            "$path: $count"
        }
        call.respondText(stats)
    }
}
```

The handler increments the count for each `Rejected` event that has a route. `ConcurrentHashMap` keeps those updates safe when several requests arrive at once.

### Micrometer Metrics

The Micrometer integration lets backend teams track rate limit metrics alongside their other server metrics. They can collect the metrics with [Prometheus](https://prometheus.io/) and view them in [Grafana](https://grafana.com/oss/grafana/), or send them to [Datadog](https://www.datadoghq.com/). They can compare rejection rates by route over time and set alerts when rejections spike.

The new `ktor-server-rate-limit-micrometer` module provides `RateLimitMetrics`, a Micrometer binder. When bound to a registry, it subscribes to `RateLimitEvent` and updates the registry's metrics each time a limiter allows or rejects a request.

The following example limits requests to `/inventory` and exposes rate limit metrics at `/metrics` for Prometheus to collect.

```kotlin
val prometheusRegistry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)
val inventory = RateLimitName("inventory")

install(RateLimit) {
    register(inventory) {
        rateLimiter(limit = 10, refillPeriod = 1.minutes)
    }
}

install(MicrometerMetrics) {
    registry = prometheusRegistry
    meterBinders += RateLimitMetrics(monitor)
}

routing {
    rateLimit(inventory) {
        get("/inventory") {
            // Inventory response
        }
    }

    get("/metrics") {
        call.respondText(
            prometheusRegistry.scrape(),
            ContentType.Text.Plain.withParameter("version", "0.0.4"),
        )
    }
}
```

Prometheus regularly scrapes the server's `/metrics` endpoint. Grafana can use the Prometheus server as a data source to plot rejection rates by route over time.

`RateLimitMetrics` uses two meter names by default.

- `ktor.rate.limit.requests` is a counter that increments each time a rate limiter allows or rejects a request.
- `ktor.rate.limit.retry.after` is a timer that records the retry delay that the limiter reports for rejected requests. It can be used to spot routes with long retry delays and review their limits or refill policies.

Both meters have tags to store provider name, route path, and HTTP method. The global limiter intercepts before routing, so its `route` tag is `n/a`. The request counter also has a `result` tag with the value `allowed` or `rejected`.

`RateLimitMetrics` requires an event monitor dependency to be built, and an optional configuration lambda with a `RateLimitMetricsConfig` receiver.

```kotlin
public class RateLimitMetrics(
    monitor: Events,
    configure: RateLimitMetricsConfig.() -> Unit = {},
) : MeterBinder

@KtorDsl
public class RateLimitMetricsConfig {
    public var metricName: String = "ktor.rate.limit"
    public fun addTag(key: String, value: (RateLimitEventData) -> String)
}
```

The `metricName` property sets the prefix for the rate limit meter names. The `addTag()` method adds a custom tag. Custom tags work best with a small set of values because each distinct tag combination creates a separate time series.

The following example adds an `api_version` tag so a Grafana dashboard can show separate rejection rates for `/v1` and `/v2` during a rollout.

```kotlin
install(MicrometerMetrics) {
    meterBinders += RateLimitMetrics(monitor) {
        metricName = "catalog.rate.limit"
        addTag("api_version") { event ->
            val version = event.route?.path().orEmpty().trimStart('/').substringBefore('/')
            if (version == "v1" || version == "v2") version else "other"
        }
    }
}
```

## Client Rate Limiting and Throttling ([KTOR-6248](https://youtrack.jetbrains.com/issue/KTOR-6248))

An app can exceed an API’s request quota when it sends several requests to it at once. The client `RateLimit` plugin lets the app control how often it sends requests and choose if a request should wait or fail when the limit is reached. It also honors response headers such as `Retry-After` by delaying further requests to the same origin.

The new `ktor-client-rate-limit` module provides the client `RateLimit` plugin. It uses the shared rate limiter API, and its configuration allows setting a global provider, registering providers that individual requests can select, and delaying requests based on hints from the server.

```kotlin
@KtorDsl
public class RateLimitConfig {
    // Registers a provider that individual requests can select.
    public fun register(
        name: RateLimitName = LIMITER_NAME_EMPTY,
        block: RateLimitProviderConfig.() -> Unit,
    )
    // Sets the global provider.
    public fun global(block: RateLimitProviderConfig.() -> Unit)
    // Sets how to delay further requests based on the server response
    public fun serverCooldown(extract: suspend (HttpResponse) -> Duration?)
}
```

The `register()` and `global()` methods take a configuration lambda with the `RateLimitProviderConfig` class as the receiver.

```kotlin
@KtorDsl
public class RateLimitProviderConfig(name: RateLimitName) {
    public var maxWaitTime: Duration = 5.seconds
    public fun requestKey(block: suspend (HttpRequestBuilder) -> Any)
    public fun requestWeight(block: suspend (HttpRequestBuilder, key: Any) -> Int)
    public fun rateLimiterFactory(factory: (key: Any) -> RateLimiter)

    public fun rateLimiter(
        limit: Int,
        refillPeriod: Duration,
        timeSource: TimeSource.WithComparableMarks = TimeSource.Monotonic,
    )

    public fun rateLimiter(
        limit: Int,
        refillPeriod: Duration,
        clock: Clock,
        timeSource: TimeSource.WithComparableMarks = TimeSource.Monotonic,
    )

    public fun incrementalRefillRateLimiter(
        limit: Int,
        refillInterval: Duration,
        timeSource: TimeSource.WithComparableMarks = TimeSource.Monotonic,
    )

    public fun incrementalRefillRateLimiter(
        limit: Int,
        refillInterval: Duration,
        clock: Clock,
        timeSource: TimeSource.WithComparableMarks = TimeSource.Monotonic,
    )
}
```

The global provider applies to every outgoing request attempt. Calling `rateLimit(name)` in a request, selects a registered provider by name so the request must also pass that provider's limit. Calling `rateLimit()` without arguments selects the provider registered without a name. A request can select several providers, and the selection carries over to retries and redirects.

The plugin tries to consume tokens from the global provider first, then from each selected provider in the order they were selected. For each provider, the request key identifies the limiter and the request weight determines how many tokens to consume. The client sends the request only after all applicable limiters allow it. If a later limiter rejects the request, tokens consumed by earlier limiters stay spent.

The server applies the same model to incoming requests, with routes selecting which providers apply after the global provider.

The following example sends search requests as new queries arrive. The catalog API client has a global limit of 100 requests per minute, and the search limiter starts with 20 tokens and replenishes one every 3 seconds. Each search that passes both limits uses one token from each limiter.

```kotlin
val search = RateLimitName("search")
val client = HttpClient {
    install(RateLimit) {
        global {
            rateLimiter(limit = 100, refillPeriod = 1.minutes)
        }
        register(search) {
            incrementalRefillRateLimiter(limit = 20, refillInterval = 3.seconds)
        }
    }
}

suspend fun observeSearchQueries(queries: Flow<String>, showResults: suspend (String) -> Unit) {
    queries.collectLatest { query ->
        val response = client.get("https://api.example.com/search") {
            parameter("q", query)
            rateLimit(search)
        }
        showResults(response.bodyAsText())
    }
}
```

### Waiting and Rejection

Each provider has a queue for each request key. Once enough tokens are available for the request at the front of the queue, the limiter deducts them, removes the request from the queue, and lets it proceed. There is no cap on how many requests can wait at once.

The `maxWaitTime` of a provider sets how long each request attempt can wait, 5 seconds by default. The timer starts just before the request joins the queue and covers the wait time to reach the front of the queue and get enough tokens. `Duration.INFINITE` allows waiting without a time limit. If the request cannot proceed within the allowed time, the plugin throws `RateLimitRejectedException`.

```kotlin
public class RateLimitRejectedException(
    public val maxWaitTime: Duration,
    message: String = rejectionMessage("tokens exhausted", maxWaitTime)
) : IllegalStateException(message)
```

With a `maxWaitTime` of 0 or negative, the request fails before joining the queue if the queue is not empty.

If a queued request is canceled, the plugin removes it from the queue. If it was at the front of the queue, the next request will try to consume tokens. Closing the client cancels all queued requests and their callers get `CancellationException`.

The `rateLimit(name)` configuration block can override `maxWaitTime` for a provider on that request, including its retries and redirects. A `null` value for `maxWaitTime` will reset all request overrides. The same configuration block works with `rateLimit(RateLimitName.global)` to override the wait time of a configured global provider.

```kotlin
@KtorDsl
public class RateLimitRequestConfig {
    public var maxWaitTime: Duration? = null
}

public fun HttpRequestBuilder.rateLimit(
    name: RateLimitName = LIMITER_NAME_EMPTY,
    configure: RateLimitRequestConfig.() -> Unit = {},
)
```

In the following example the wait time is set to 0, so the status request fails immediately if the rate limiter doesn't have enough tokens.

```kotlin
val status = RateLimitName("status")
val client = HttpClient {
    install(RateLimit) {
        register(status) {
            rateLimiter(limit = 10, refillPeriod = 1.minutes)
        }
    }
}

client.get("https://api.example.com/status") {
    rateLimit(status) {
        maxWaitTime = Duration.ZERO
    }
}
```

### Server Cooldowns

A response from the server can delay further requests to the same origin (scheme, host, and port). For example, a `429 Too Many Requests` response with `Retry-After: 30` tells the client to wait 30 seconds before sending more requests to that origin. The client will respect the cooldown even if no providers are configured.

The client waits for the cooldown to end before it tries to consume tokens. While the request waits for tokens, the client will use the latest response from the same origin to decide if it should start or extend the cooldown. After it consumes tokens, the client checks the cooldown again and waits for it to end before sending the request.

The timer of `maxWaitTime` starts after any initial server cooldown ends, so the request can wait up to that duration in addition to the cooldown. Canceling a request ends its cooldown wait, and closing the client cancels all cooldown waits.

By default, the plugin reads the delay with a `HttpResponse.rateLimitDelay()` method. The method is public so a custom `serverCooldown` handler can check its own headers first and fall back to Ktor's built-in header parsing.

```kotlin
public fun HttpResponse.rateLimitDelay(): Duration?
```

The method parses the following headers:

- `Retry-After`, with a delay in seconds or an HTTP date.
- The IETF draft `RateLimit` header.
- `RateLimit-Remaining` and `RateLimit-Reset`, with the reset given in seconds.
- `X-RateLimit-*` and `X-Rate-Limit-*`, with `Reset` as a Unix timestamp in seconds or `Reset-After` as seconds until reset.

If the method extracts multiple delays, it returns the longest one. The method returns `null` if it finds no valid cooldown hint.

The `serverCooldown { ... }` method can be used to replace the default handler. The `serverCooldown { null }` will ignore server hints.

```kotlin
public class RateLimitConfig {
    public fun serverCooldown(extract: suspend (HttpResponse) -> Duration?)
}
```

The handler runs before the response reaches the caller, so it should not consume the response body.

Suppose a file service sends a custom `X-Backoff-Seconds` header with a delay in seconds. In the following example, the client respects that delay if it's positive and otherwise falls back to Ktor's built-in parser.

```kotlin
val client = HttpClient {
    install(RateLimit) {
        serverCooldown { response ->
            val seconds = response.headers["X-Backoff-Seconds"]?.toIntOrNull()
            seconds?.takeIf { it > 0 }?.seconds ?: response.rateLimitDelay()
        }
    }
}
```

# Drawbacks

This design would require deprecating several public server APIs and adapting some of them to work with the shared types.

| Deprecated server API | Migration |
| --- | --- |
| `RateLimitName` | Import `io.ktor.ratelimit.RateLimitName`. |
| `RateLimitConfig.register` with the old `RateLimitName` | Pass the shared `RateLimitName`. |
| `Route.rateLimit` with the old `RateLimitName` | Pass the shared `RateLimitName`. |
| `RateLimiter` | Import or implement `io.ktor.ratelimit.RateLimiter`. |
| `RateLimiter.Unlimited` | Use the shared `RateLimiter.Unlimited`. |
| `RateLimiter.default` with `clock: () -> Long` | Use the shared `RateLimiter.default` with `kotlin.time`. |
| `RateLimitProviderConfig.rateLimiter` with `clock: () -> Long` | Use the `kotlin.time` overload. |
| `RateLimitProviderConfig.rateLimiter { call, key -> ... }` | Use `rateLimiterFactory { key -> ... }` and include the data needed to create the limiter in the key. |
| `RateLimitProviderConfig.modifyResponse` | Use `modifySharedResponse` with the shared `RateLimiter.State`. The separate method keeps old callbacks compatible and avoids a JVM signature clash because both callback types erase to `Function2`. |

- **Negative weights fail.** Server code that relied on negative weights to add tokens needs to change its weight calculation.
- **Unused limiters can build up.** Both plugins keep rate limiters for the life of the client or server application, even after requests stop using their keys. As more distinct keys arrive, the number of stored limiters can grow without bound, which increases memory use and can hurt performance.
- **Client queue growth.** A burst of requests can leave many requests in the queue at once, which can lead to high memory use.
- **Heavy requests can hold up lighter ones.** A request at the front of the queue can hold up lighter requests even if the limiter has enough tokens for the lighter ones.
- **An infinite wait can block the queue.** With `maxWaitTime = Duration.INFINITE`, a request that needs more tokens than the bucket can hold stays at the front of the queue until it is canceled.
- **Rejected requests can still spend tokens.** A request can consume tokens from one provider and then be rejected by another, leaving fewer tokens for later requests.
- **Cooldowns can affect other endpoints.** A server hint from one endpoint can delay calls to other endpoints on the same origin, even when they use different providers.

# Advantages

A shared `RateLimiter` API lets Ktor users write and test a custom limiter once and use it in both plugins.

# Open Questions

Should we remove idle rate limiters? If so, when is it safe to remove one? A new limiter starts with a full bucket, so removing the old one too soon can give extra tokens to the next request with the same key. In general, coordinating eviction with concurrent access to rate limiters is hard to get right.

# Future Directions

- Add a cap on how many requests can wait in each client queue.
- Let the client apply a cooldown to a single endpoint so requests to other endpoints on the same origin can still proceed.
- Make the `HttpRequestRetry` plugin be able to parse the `Retry-After` header as HTTP date.
- Make the `HttpRedirect` plugin honor `Retry-After` before following a redirect.
