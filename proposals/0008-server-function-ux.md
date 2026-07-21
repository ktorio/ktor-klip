|           |                                                |
|-----------|------------------------------------------------|
| Feature   | Server UX Improvements                         |
| Submitted | 2026-07-21                                     |
| Accepted  | No                                             |
| Issue     | https://youtrack.jetbrains.com/issue/KTOR-8554 |
| Prototype | https://github.com/bjhham/ktor-serve           |

### Contents

1. [Summary](#summary)
2. [Motivation](#motivation)
3. [Current Solutions](#current-solutions)
4. [Design Overview](#design-overview)
5. [Design Details](#design-details)
   1. [Non-HTTP Engines](#non-http-engines)
   2. [Serverless Functions](#serverless-functions)
6. [Drawbacks](#drawbacks)
7. [Advantages](#advantages)
8. [Open Questions](#open-questions)

<hr />

# Summary
[summary]: #summary

Our entry point functions for calling into Ktor can create some friction for new users due to a complex interaction between the different components and DSLs. This document will propose a new API that will improve the ease of use.

# Motivation
[motivation]: #motivation

We have several issues addressed to this topic in YouTrack, with each referencing different problems:
- https://youtrack.jetbrains.com/issue/KTOR-8554
- https://youtrack.jetbrains.com/issue/KTOR-8555
- https://youtrack.jetbrains.com/issue/KTOR-9264

Topics of discussion have also come up:
1. Strange contention between the `ApplicationEnvironment` configuration and the configuration files
2. A general excess of `embeddedServer` functions with different signatures with no clear reasoning behind the overloads.
3. It is unclear how to pass command line arguments to the embedded server without calling the engine main function.
4. Application modules are not re-loaded automatically with function references due to issues with reflection and casting.
5. There's no way to separate the application logic from the server lifecycle and port bindings.

The current set of functions for the creation of an embedded server is as follows:
```kotlin
fun <TEngine : ApplicationEngine, TConfiguration : ApplicationEngine.Configuration> embeddedServer(
    factory: ApplicationEngineFactory<TEngine, TConfiguration>,
    port: Int = 80,
    host: String = "0.0.0.0",
    watchPaths: List<String> = listOf(WORKING_DIRECTORY_PATH),
    module: suspend Application.() -> Unit
): EmbeddedServer<TEngine, TConfiguration>

fun <TEngine : ApplicationEngine, TConfiguration : ApplicationEngine.Configuration> CoroutineScope.embeddedServer(
    factory: ApplicationEngineFactory<TEngine, TConfiguration>,
    port: Int = 80,
    host: String = "0.0.0.0",
    watchPaths: List<String> = listOf(WORKING_DIRECTORY_PATH),
    parentCoroutineContext: CoroutineContext = EmptyCoroutineContext,
    module: suspend Application.() -> Unit
): EmbeddedServer<TEngine, TConfiguration>

fun <TEngine : ApplicationEngine, TConfiguration : ApplicationEngine.Configuration> CoroutineScope.embeddedServer(
    factory: ApplicationEngineFactory<TEngine, TConfiguration>,
    vararg connectors: EngineConnectorConfig = arrayOf(),
    watchPaths: List<String> = listOf(WORKING_DIRECTORY_PATH),
    parentCoroutineContext: CoroutineContext = EmptyCoroutineContext,
    module: Application.() -> Unit
): EmbeddedServer<TEngine, TConfiguration>

fun <TEngine : ApplicationEngine, TConfiguration : ApplicationEngine.Configuration> CoroutineScope.embeddedServer(
    factory: ApplicationEngineFactory<TEngine, TConfiguration>,
    vararg connectors: EngineConnectorConfig = arrayOf(),
    watchPaths: List<String> = listOf(WORKING_DIRECTORY_PATH),
    parentCoroutineContext: CoroutineContext = EmptyCoroutineContext,
    module: suspend Application.() -> Unit
): EmbeddedServer<TEngine, TConfiguration>

fun <TEngine : ApplicationEngine, TConfiguration : ApplicationEngine.Configuration> embeddedServer(
    factory: ApplicationEngineFactory<TEngine, TConfiguration>,
    environment: ApplicationEnvironment = applicationEnvironment(),
    configure: TConfiguration.() -> Unit = {},
    module: suspend Application.() -> Unit = {}
): EmbeddedServer<TEngine, TConfiguration>

fun <TEngine : ApplicationEngine, TConfiguration : ApplicationEngine.Configuration> embeddedServer(
    factory: ApplicationEngineFactory<TEngine, TConfiguration>,
    rootConfig: ServerConfig,
    configure: TConfiguration.() -> Unit = {}
): EmbeddedServer<TEngine, TConfiguration> {
    return EmbeddedServer(rootConfig, factory, configure)
}
```

From the provided signatures, it should be immediately obvious that it is not obvious how to create an embedded server.

The relevant abstractions at play include, all of which require some familiarity:
- `ApplicationEngineFactory`: generally supplied by Ktor, this is a reference to the engine implementation.  For example, `CIO` or `Netty`.
- `ApplicationEngine`: the actual engine instance, which is responsible for handling requests.  It has a reference to the environment and the engine configuration. It also includes lifecycle functions (start and stop).
- `ApplicationEngine.Configuration`: the configuration for the engine. This includes the connectors (ports, hosts), parallelism, and shutdown grace period options.
- `EngineConnectorConfig`: this is an element of a list inside `ApplicationEngine.Configuration`. It represents a single port binding.
- `ApplicationEnvironment`: this consists of the application properties (i.e., `ApplicationConfig`) and logging.
- `Application`: this is where all the request handling logic lives.  It also contains a reference to the engine, environment, and coroutine context.
- `ServerConfig`: includes watch paths, modules, environment, development mode. It is passed the `EmbeddedServer` instance as part of its configuration.
- `EmbeddedServer`: houses all of the above and provides the top-level `start` and `stop` functions.  This also handles reloading modules and other general lifecycle concerns.

There are also out-of-the-box main functions that can be used directly, such as `io.ktor.server.cio.EngineMain.main`, which can be customized using command line arguments.

Oftentimes, users get confused about how to add new connectors like HTTPS, or how to include other configuration files.  For each question, there are several answers, none of which is obvious.

After the server is instantiated, developers must then manage the lifecycle using the `start(wait: Boolean)` and `stop()` functions.  It would be better to integrate the Ktor server jobs into the coroutine framework and leverage the concurrency model here.

Overall, the current API is confusing and cumbersome.  With a small redesign, it can be improved greatly without modifying any existing code.

# Current Solutions
[current-solutions]: #current-solutions

Let's briefly look at some other frameworks and how they approach the execution of their server instances.

## Javascript

For the simplest possible [Node.js](https://nodejs.org) server, the built-in `http` package can be used:

```js
import http from "node:http";

const server = http.createServer((req, res) => {
  res.writeHead(200, { "content-type": "text/plain" });
  res.end("hello\n");
});

server.listen(3000, "0.0.0.0", () => {
  console.log("listening on http://0.0.0.0:3000");
});
```

This pattern is carried over to [Express](https://expressjs.com/):

```js
import express from "express";

const app = express();
app.get("/", (req, res) => res.send("hello\n"));

app.listen(3000, "0.0.0.0", () => {
  console.log("listening on http://0.0.0.0:3000");
});
```

## Python

The standard library provides an easy-to-use [HTTP server](https://docs.python.org/3/library/http.server.html):

```python
from http.server import HTTPServer, BaseHTTPRequestHandler

class Handler(BaseHTTPRequestHandler):
    def do_GET(self):
        self.send_response(200)
        self.send_header("content-type", "text/plain")
        self.end_headers()
        self.wfile.write(b"hello\n")

HTTPServer(("0.0.0.0", 8000), Handler).serve_forever()
```

Or using [Flask](https://flask.palletsprojects.com/en/2.2.x/):

```python
from flask import Flask

app = Flask(__name__)

@app.get("/")
def hello():
    return "hello\n"

app.run(host="0.0.0.0", port=8000)
```

## Kotlin

### Http4k

For an alternative to Ktor, you can use [http4k](https://www.http4k.org/)

```kotlin
import org.http4k.core.Response
import org.http4k.core.Status
import org.http4k.server.Netty
import org.http4k.server.asServer

fun main() {
    val app = { _: org.http4k.core.Request -> Response(Status.OK).body("hello\n") }
    val server = app.asServer(Netty(port = 8080, host = "0.0.0.0")).start()
    println("listening on http://0.0.0.0:${server.port()}")
}
```

### Spring Boot

And here is a simple example using Spring Boot:

```kotlin
import org.springframework.boot.WebApplicationType
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.builder.SpringApplicationBuilder

@SpringBootApplication
class App

fun main(args: Array<String>) {
    SpringApplicationBuilder(App::class.java)
        .web(WebApplicationType.SERVLET)
        .properties(
            "server.address=0.0.0.0",
            "server.port=8080"
        )
        .run(*args)
}
```

### ArrowKt

And here is an example of ArrowKt using Ktor:

```kotlin
import arrow.continuations.SuspendApp
import arrow.continuations.ktor.server
import arrow.fx.coroutines.resourceScope
import io.ktor.server.application.*
import io.ktor.server.config.*
import io.ktor.server.netty.Netty
import io.ktor.server.routing.routing
import kotlinx.coroutines.*

fun main() = SuspendApp {
    val env = ApplicationConfig("application.yaml").getAs<Env>()
    resourceScope {
        val dependencies = dependencies(env)
        val _ = server(Netty, host = env.server.host, port = env.server.port) { app(dependencies) }
        awaitCancellation()
    }
}
```


## Summary

Let's discuss the common features for the more successful solutions to the creation and execution of an embedded server.

We can group these approaches into two general categories:

1. Server as a _function_
   - A server is treated like a function for operating on requests and responses.
   - It is later bound to the particular ports and addresses for listening.
   - This pattern is used by the majority of micro-frameworks like Express, Flask, and http4k.
2. Server as a _process_
   - For services geared more towards configuration and running a single instance, the ports and addresses are bound to the server, which is executed like a process.
   - This seems to be the general approach taken by the Python standard library, Ktor, and Spring Boot.

The different implementations have a variety of configuring the server itself for routing, logging, etc., but they generally follow a builder pattern or use some kind of instrumented code inference, like Spring.

For Ktor, we're using a builder pattern and treating the server as an executable process. As discussed in the previous section, the main problems of our current approach is the proliferation of types and builder functions.

# Design Overview
[design-overview]: #design-overview

To fix our situation, we'll provide a new simplified API for calling into Ktor. We'll provide it as an optional mechanism in a 3.x release, then deprecate the existing functions in 4.0 if it is accepted.

The goals of the new API will be to minimize:
- arguments
- function names
- function calls (i.e., `embeddedServer`, `start(waiting)`)

And to simplify:
- DSL interaction
- config files
- working with CLI args

Furthermore, if we intend to abstract away the networking side of our server lifecycle, then we'll need a baseline engine function that is agnostic to HTTP and networking.

# Design Details
[design-details]: #design-details

Instead of creating a new instance of `embeddedServer`, then managing the lifecycle of the object, we can simply call a suspend function and allow structured concurrency to handle everything else.

Since we're not interacting with the server instance, we can simply call the new function, "serve".

Here is what it might look like:

```kotlin
suspend fun main(args: Array<String>) {
    // suspend function, exits when server is stopped
   Netty.serve(args) {
        // properties, logging, classloader, etc.
        configure("application.yaml")
        // engine config
        engine {}
        // connectors { http, https, unix }
        http {
            host = "127.0.0.1"
            port = 8080
        }
        // application logic
        // you can reference the mutable modules
        modules += {
            routing {
                get {
                    call.respond("Hello, World!")
                }
            }
        }
        // or install plugins directly
        // similarly to how `testApplication` scope works
        routing {
           get {
              call.respond("Hello, World!")
           }
        }
    }
}
```

Each of these blocks would be optional with reasonable defaults like port 8080 for a default HTTP connector, and we should ensure that file configuration is available for all other parts so that `Netty.serve()` will work out of the box.

After providing the new function, we can deprecate the existing `embeddedServer` functions as well as the `EngineMain` functions.  Due to the complex nature of the current server functions, an automatic migration tool might be challenging to create.  It might be pertinent for us to publish an "skill" for LLMs to leverage in replacing existing functions.

In some cases, users might want an instance of the server to manage themselves.  For this, we can include a `buildServer` function with the same lambda argument that returns the server instance.

A partial implementation of the new API can be found in the [ktor-serve](https://github.com/bjhham/ktor-serve) repository.

## Non-HTTP Engines
[non-http-engines]: #non-http-engines

If we allow for non-HTTP engines, then the connector functions would be replaced with another relevant source, and most other functions would remain intact.

For example, when building a message consumer that reads off a queue, you could use something like:

```kotlin
suspend fun main() {
   Kafka.serve {
        queue {
            url = "https://127.0.0.1/my-queue"
            clientId = "my-client-id"
        }
        // Single route, operating on a higher abstraction to HTTP
        handle {
            call.respond("Hello, World!")
        }
    }
}
```

It's hard to say at the moment how easy it would be to implement a generic non-HTTP engine of this nature.  This will require some more investigation.

## Serverless Functions
[serverless-functions]: #serverless-functions

For serverless functions, we export a function that provides the functionality of the server.

In these cases, an engine is not needed because there is no lifecycle or bindings.

Here is what this might look like with the new API:

```kotlin
interface ApplicationCallMapping<Req, Res> {
    suspend fun mapRequest(request: Req): ApplicationRequest
    suspend fun mapResponse(response: ApplicationResponse): Res
}
object W3cCallMapping : ApplicationCallMapping<W3cRequest, W3cResponse> {
    // TODO
}

@OptIn(ExperimentalJsExport::class)
@JsExport
val fetch: suspend (W3cRequest) -> W3cResponse = W3cCallMapping.serverless {
    // logging and config can be updated here as well
    routing {
        get {
            call.respond("Hello, World!")
        }
    }
}
```

This will require some tricks to fake out some aspects of the engine that have leaked into the `Application` layer, but we can deprecate some of these interactions for now.


# Drawbacks
[drawbacks]: #drawbacks

Adding another standard for running Ktor server applications will create more complexity before the former APIs are retired.

# Advantages
[advantages]: #advantages

Users no longer have to deal with the many different ways to run Ktor server applications, and can instead use a single standard for all platforms.

# Open Questions
[open-questions]: #open-questions

We'll need to create PoC's to validate the following concepts:
 - Non-HTTP engines
 - Serverless functions

Ktor's underlying pipeline abstraction should allow for these uses; however, it's likely the parts of the abstraction are tightly coupled to HTTP.