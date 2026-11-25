# Summary
[summary]: #summary

Improve server-side rate limiting in Ktor by adding a limiter that refills its bucket gradually, giving Ktor users access to rate limiter stats, and using `kotlin.time`. Add client-side rate limiting and throttling to control how often requests are sent.

# Motivation
[motivation]: #motivation

The server rate limiter now has just one refill policy: the whole bucket is refilled at once after a set time. Gradual refills would let requests start again sooner and keep the load more steady ([KTOR-8024](https://youtrack.jetbrains.com/issue/KTOR-8024)).

Ktor users also need rate limiter stats to show end users how much of their limit is left and track where and when limits are reached. Most of the classes that hold these stats are internal, so the stats are hard to get today ([KTOR-8103](https://youtrack.jetbrains.com/issue/KTOR-8103)).

Rate limiters track time through a lambda that returns `getTimeMillis()` by default and can be replaced in tests to use fake time. The types in `kotlin.time` are a better fit for both tracking time and setting fake time in tests ([KTOR-9534](https://youtrack.jetbrains.com/issue/KTOR-9534)).

Ktor Client has no built-in support for rate limiting or throttling, so users need their own code or third-party plugins to control how often requests are sent ([KTOR-6248](https://youtrack.jetbrains.com/issue/KTOR-6248)). Building this into Ktor would let the client share rate limiting code with the server, so both gain from the same changes and fixes.

# Current Solutions
[current-solutions]: #current-solutions

## Server

Users can write a rate limiter with gradual refills by implementing the `RateLimiter` interface and using it in the `RateLimit` plugin. This gives them control over the refill policy but requires them to write and test the code.

The plugin has no public API to expose stats for its rate limiters, so Ktor users cannot query them through the plugin.

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
- **Client support:** Ktor Client has no built-in rate limiting or throttling, so users must add a third-party plugin or integrate a rate limiter themselves.
- **Separate APIs:** Users who need rate limiting on both the client and server must work with different APIs and refill rules, and may need to duplicate custom logic.

Outline:

- **Shared core:** Move the rate limiter API and code into a module that both client and server plugins can use.
- **Refill policies:** Keep the current full refill policy and add gradual refills.
- **Stats API for the Server:** Let each rate limiter expose its current state and add Micrometer metrics support to track use over time. Ktor users can also subscribe to rate limiting events to store their own stats.
- **Time handling:** Use `kotlin.time` types and a monotonic time source to measure elapsed time, with support for fake time in tests.
- **Client plugin:** Add rate limiting and throttling, with a choice to reject requests or wait when the limit is reached. Set a maximum wait time per provider, with per-request overrides for selected providers. There is no cap on how many requests can wait at once for now.

# Design and Technical Details
[design-and-technical-details]: #design-and-technical-details

## Shared RateLimiter Interface

Move `RateLimiter` to the `io.ktor.ratelimit` package in the shared `ktor-rate-limit` module so both client and server plugins use the same interface. A rate limiter uses tokens to track how many requests it can allow. Each request spends one or more tokens based on the calculated weight, and the refill policy sets when new tokens become available.

The interface has two suspend functions:

```kotlin
public suspend fun tryConsume(tokens: Int = 1): State
public suspend fun snapshot(): RateLimiterSnapshot
```

`tryConsume` is the existing method moved from the server's `RateLimiter` interface. It tries to consume the requested number of tokens and returns `State.Available` with the state after consumption, or `State.Exhausted` if there are not enough tokens.

The client and server plugins decide how to handle an exhausted limiter, such as rejecting the request or waiting before trying again.

`snapshot` reads the limiter's current quota without consuming tokens. It throws `UnsupportedOperationException` by default so existing custom server rate limiters can keep working without implementing it. All built-in rate limiters implement this method.

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

The request key groups requests that share a rate limiter. For example, if the key is a user ID, the limiter has 10 tokens refilled in full every minute, and each request's weight is calculated to be 1, then each user can make up to 10 requests per minute.

Both client and server providers have four main settings:

- **Name:** Identifies the provider.
- **Request key:** A function that calculates the key from the request.
- **Limiter factory:** A function that creates a rate limiter for each distinct key.
- **Request weight:** A function that calculates how many tokens the request consumes.

Key and weight functions accept `ApplicationCall` on the server and `HttpRequestBuilder` on the client.

Requests with equal keys share the same limiter within a provider. By default, the request key is the same for all requests within a provider, so those requests share one rate limiter.

Providers can be selected for routes on the server or requests on the client, while a global provider applies to all requests.

## Incremental Refill ([KTOR-8024](https://youtrack.jetbrains.com/issue/KTOR-8024))

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

## Time Handling with kotlin.time ([KTOR-9534](https://youtrack.jetbrains.com/issue/KTOR-9534))

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

Propose deprecation of the old server overloads that take `clock: () -> Long`. Provider factory calls that pass values only for `limit` and `refillPeriod` arguments, select the new overload by default.

## Server Stats ([KTOR-8103](https://youtrack.jetbrains.com/issue/KTOR-8103))

### Showing a User's Limits

One use case is to let an app show a user's remaining quota without consuming tokens. Propose `Application.getRateLimiter(providerName, key)` to get the rate limiter associated with the key, creating it if it does not exist yet. The returned rate limiter has a `snapshot()` method that returns the number of tokens left and the maximum number it can hold.

The following code snippet shows how to let each signed-in user download 10 reports per hour and check how many downloads they have left. Authentication is assumed to be configured:

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

To read a user's quota, we need the same rate limiter object that limits their requests. If the rate limiter doesn't exist yet, we need to create it. However, the current factory takes an `ApplicationCall`, and we can't reliably use the `ApplicationCall` from a quota request in place of one from the user's limited requests. We therefore propose `rateLimiterFactory { key -> ... }` to create limiters from the request key alone. The key must contain all the data needed to create the limiter, so `Application.getRateLimiter(providerName, key): RateLimiter` can create it if needed and the plugin can reuse it for requests with the same provider name and key. We would deprecate the old `rateLimiter { call, key -> ... }` overload and keep it as a fallback when handling limited requests.

`getRateLimiter()` throws `IllegalStateException` if the `RateLimit` plugin isn't installed or if the limiter doesn't exist yet and the provider uses only the deprecated factory.

Omitting `key` uses the default request key (`Unit`). Omitting `providerName` selects the unnamed provider.

### Collecting Server Stats

Backend teams need to track when and how often requests hit rate limits on each route to decide if those limits need to change.

The server `RateLimit` plugin raises `RateLimitEvent` through `Application.monitor` each time a rate limiter allows or rejects a request.

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

The handler adds one to the count for each `Rejected` event that has a route. `ConcurrentHashMap` keeps those updates safe when several requests arrive at once.

### Micrometer Metrics

The Micrometer integration lets backend teams track rate limit metrics alongside their other server metrics. Teams can collect the metrics with [Prometheus](https://prometheus.io/) and view them in [Grafana](https://grafana.com/oss/grafana/), or send them to [Datadog](https://www.datadoghq.com/). They can compare rejection rates by route over time and set alerts when rejections spike.

The new `ktor-server-rate-limit-micrometer` module provides `RateLimitMetrics`, a Micrometer meter binder. When bound to a registry, it subscribes to `RateLimitEvent` and updates the registry's metrics each time a limiter allows or rejects a request.

The following example shows how an API with `RateLimit` installed can expose its rate limit metrics for Prometheus to collect.

```kotlin
val prometheusRegistry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)

install(MicrometerMetrics) {
    registry = prometheusRegistry
    meterBinders += RateLimitMetrics(monitor)
}

routing {
    get("/metrics") {
        call.respondText(
            prometheusRegistry.scrape(),
            ContentType.Text.Plain.withParameter("version", "0.0.4"),
        )
    }
}
```

Prometheus regularly scrapes the server's `/metrics` endpoint. Grafana uses the Prometheus server as a data source to plot rejection rates by route over time ([Grafana setup guide](https://grafana.com/docs/grafana/latest/datasources/prometheus/)).

`RateLimitMetrics` uses two meter names by default.

- `ktor.rate.limit.requests` is a counter that increments each time a rate limiter allows or rejects a request.
- `ktor.rate.limit.retry.after` is a timer that records the retry delay the limiter reports for rejected requests. It can be used to spot routes with long retry delays and review their limits or refill policies.

Both meters have `limiter`, `route`, and `method` tags for the provider name, route path, and HTTP method. The global limiter checks requests before routing, so its `route` tag is `n/a`. The request counter also has a `result` tag with the value `allowed` or `rejected`.

`RateLimitMetrics` takes an event monitor, usually `Application.monitor`, and an optional configuration lambda with a `RateLimitMetricsConfig` receiver.

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

`metricName` sets the prefix used to name the rate limit meters. A blank value leaves out the prefix and the separating dot, so the meters are named `requests` and `retry.after`. `addTag()` defines a custom tag by name and takes a function that supplies its value for each rate limit event. Custom tags work best with a small set of values because each distinct tag combination creates a separate time series.

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

An app that calls an API with a request quota can exceed it when several tasks send requests at once. The client `RateLimit` plugin lets the app control how often it sends requests and choose if a request should wait or fail when the limit is reached. It also honors response headers such as `Retry-After` by delaying further requests to the same origin.

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
    // Sets how server responses can delay further requests to the same origin.
    public fun serverCooldown(extract: suspend (HttpResponse) -> Duration?)
}
```

The `register()` and `global()` methods take a configuration lambda with the client's `RateLimitProviderConfig` as its receiver.

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

The global provider applies to every outgoing request attempt. Calling `rateLimit(name)` in a request selects a registered provider by name so the request must also pass that provider's limit. Calling `rateLimit()` selects the provider registered without a name. A request can select several providers, and the selection carries over to retries and redirects.

The plugin tries to consume tokens from the global provider first, then from each selected provider in the order they were selected. For each provider, the request key identifies the limiter and the request weight sets how many tokens to consume. The default weight is one. The client sends the request only after all applicable limiters allow it. If a later limiter rejects the request, tokens consumed by earlier limiters stay spent.

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

A provider's `maxWaitTime` limits the full wait for that provider. The timer starts just before the request joins the queue and covers the wait to reach the front and get enough tokens. The limit defaults to 5 seconds and applies separately to each provider on every request attempt, including retries and redirects. `Duration.INFINITE` allows waiting without a time limit. If the request cannot proceed within the allowed time, the plugin throws `RateLimitRejectedException`.

```kotlin
public class RateLimitRejectedException(
    // The provider's wait limit for this attempt, with any request override applied.
    public val maxWaitTime: Duration,
    message: String = rejectionMessage("tokens exhausted", maxWaitTime)
) : IllegalStateException(message)
```

With a `maxWaitTime` of zero or less, the request fails before joining if another request is already in the queue. If it can take the front of the queue right away, it tries to consume tokens once and proceeds if it gets enough.

If a queued request is canceled, the plugin removes it from the queue. If it was at the front of the queue, the next request can try to consume tokens. Closing the client cancels all queued requests, so their callers get `CancellationException`.

The `rateLimit(name)` configuration block can override `maxWaitTime` for a provider on that request, including its retries and redirects. A `null` override uses the provider's configured wait time. The same configuration block works with `rateLimit(RateLimitName.global)` to override the wait time of a configured global provider.

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

The following example sets the wait time to zero so the status request fails immediately if the rate limiter doesn't have enough tokens.

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

A response from the server can delay further requests to the same origin (scheme, host, and port). For example, a `429 Too Many Requests` response with `Retry-After: 30` tells the client to wait 30 seconds before sending more requests to that origin. The client will respect the cooldown even if no providers are set up.

The client waits for the cooldown to end before it tries to consume tokens. While the request waits for tokens, the client uses the latest response from the same origin to decide if it should start or extend the cooldown. After it consumes tokens, the client checks the cooldown again and waits for it to end before sending the request.

The timer for a provider's `maxWaitTime` starts after any initial server cooldown ends, so the request can wait up to that limit in addition to the cooldown. Canceling a request ends its cooldown wait, and closing the client cancels all cooldown waits.

By default, the plugin reads the delay with `HttpResponse.rateLimitDelay()`. The method is public so a custom `serverCooldown` handler can check its own headers first and fall back to Ktor's built-in header parsing.

```kotlin
public fun HttpResponse.rateLimitDelay(): Duration?
```

The method parses the following headers:

- `Retry-After`, with a delay in seconds or an HTTP date.
- The IETF draft `RateLimit` header, with `r=0` and `t` as seconds until reset.
- `RateLimit-Remaining` and `RateLimit-Reset`, with the reset given in seconds.
- `X-RateLimit-*` and `X-Rate-Limit-*`, with `Reset` as a Unix timestamp in seconds or `Reset-After` as seconds until reset.

If the method extracts multiple delays, it uses the longest one. The method returns `null` if it finds no valid hint.

Use `serverCooldown { ... }` to replace the default handler with a custom one. Use `serverCooldown { null }` to ignore server hints.

```kotlin
public fun serverCooldown(extract: suspend (HttpResponse) -> Duration?)
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
