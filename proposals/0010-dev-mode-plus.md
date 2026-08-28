|           |                                                                                                                                                                                 |
|-----------|---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| Feature   | Enhanced Development Mode                                                                                                                                                       |
| Submitted | 2024-12-01                                                                                                                                                                      |
| Accepted  | No                                                                                                                                                                              |
| Issue     | https://youtrack.jetbrains.com/issue/KTOR-8277                                                                                                                                  |
| Prototype | [Gradle plugin branch](https://github.com/ktorio/ktor-build-plugins/tree/bjhham/dev-mode-plus-eap), [Ktor branch](https://github.com/ktorio/ktor/tree/bjhham/dev-mode-plus-eap) |

### Contents

1. [Summary](#summary)
2. [Motivation](#motivation)
3. [Current Solutions](#current-solutions)
4. [Design Overview](#design-overview)
5. [Design Details](#design-details)
6. [Technical Details](#technical-details)
7. [Drawbacks](#drawbacks)
8. [Advantages](#advantages)
9. [Future Directions](#future-directions)

<hr />

# Summary
[summary]: #summary

In this document, we describe a new mechanism for running Ktor server applications when developing locally.

# Motivation
[motivation]: #motivation

When developing applications, it's important to have a quick development loop. That means being able to modify sources and see the resulting changes in the running app as quickly as possible.

Ktor has a baseline implementation for handling this idea, but falls short in a few areas. 

Currently, our "development mode" involves:
1. Running a Gradle build in continuous mode
2. An internal file watcher in Ktor to check for changes
3. A refreshing URL classloader, bound to an `Application` instance

Whenever a change is made in the source code, Gradle will compile the relevant files, which triggers the file watcher to reload the server.

There's a few problems with this approach:
1. Having to run a separate process every time you're developing locally is inconvenient.  This ought to be a single run configuration.
2. We have a chain of two file watchers: the Gradle watcher and the internal Ktor one.  Both of these are just polling the file system for changes, so each one adds a couple seconds of delay.  This lag effects the developer flow.
3. The URL classloader refresh can only handle suspend module function references.  It does not work with lambdas or blocking function references.  This is very limiting and confusing.
4. The mechanism is quite flaky and will often miss changes or go into a bad state.
5. It does not cascade changes to any running web front ends.

To remain competitive as a back end framework, it is important that we cover all these pain points and provide a first-rate development experience.

# Current Solutions
[current-solutions]: #current-solutions

Across JVM stacks, the strongest development loops come from Quarkus (`quarkus:dev`) and JRebel/HotswapAgent-style class redefinition: both minimize restart cost through incremental reload and class redefinition, with typical server-side update times around ~0.2–2s (and up to ~5s for larger changes). Micronaut and Spring Boot DevTools are generally restart/reload-oriented (~1–8s depending on change scope), with Spring’s restart classloader and Micronaut’s AOT bean metadata as the main differentiators. In all of these JVM options, browser refresh is usually not intrinsic to the server runtime itself; when present (for example via companion LiveReload tooling), it is an additional step on top of server readiness.

Go tooling (Air/Fresh/CompileDaemon) follows a straightforward watch → rebuild → process restart model and typically lands around ~0.5–3s, but likewise does not include built-in browser refresh. Node ecosystems split into two tiers: Vite and Next.js/Nuxt provide integrated HMR/Fast Refresh with browser updates included in the feedback loop (commonly ~50ms–1.5s for UI-visible changes), while restart-based tools such as nodemon/ts-node-dev/tsx are closer to backend-style loops (~0.5–3s) and require a separate browser reload step. For this RFC, cycle-time figures should therefore be interpreted as including browser refresh only when the framework provides integrated HMR/Fast Refresh; otherwise, they represent time-to-server-ready.

Ktor's current development mode is roughly analogous to Micronaut's in terms of cycle time and tooling.  However, as mentioned in the previous section, we have some problems with coverage and stability.

Another source of inspiration is Compose Hot Reload, which provides the best developer experience when working on Compose UI's.  The Gradle plugin provides a single run task that orchestrates recompilation and reloads in the running application.

# Design Overview
[design-overview]: #design-overview

To provide a developer experience that is worthy of widespread adoption, we can surmise the following requirements for this proposal:

- Development mode is handled entirely from a single run configuration
- Fast cycle time for code changes to manifest in the running application (< 1 second)
- Automatic HTML reloading when the server refreshes
- Execution is IDE agnostic

# Design Details
[design-details]: #design-details

For implementing the hot reload, we'll follow the same approach as Compose Hot Reload.  The Gradle plugin will provide a single run task that orchestrates recompilation and reloads in the running application.

When using the Ktor Gradle plugin, a `hotRun` task will be provided that orchestrates recompilation and reloading for the running application.  To improve the delay between class file updates and server refreshes, we'll expose a static list of running servers in the running process, which will be called directly from a hot swap agent.  For redefining classes, we can also leverage the JetBrains runtime to automatically replace all bytecode in the running application.

To summarize, the *original* process was:

1. User starts a `gradle build -t` process
2. User starts the server with `gradle run`
3. User modifies server code
4. Gradle detects file change, rebuilds the relevant classes
5. Ktor detects class file changes
6. Ktor rebuilds a URL classloader with the new classes
7. Ktor reloads the server using the new classloader

**Total time:** 3–10 seconds

The *new* process follows:

1. User starts the server with `gradle hotRun`
  - This kicks off the server and hotswap agent in tandem
2. User modifies server code
3. Gradle detects file change, rebuilds the relevant classes
4. Hotswap agent injects new classes and calls `EmbeddedServer.reload()` directly

**Total time:** 1–2 seconds

Under [Technical Details](#technical-details), we'll provide more information on the new mechanism. The main advantage, as far as the user is concerned, is that they only have a single run command, and their changes should appear in a fraction of the time.

## Reloading the Front End
[reloading-the-front-end]: #reloading-the-front-end

To support a faster development loop when making changes to the front end, we can facilitate refreshing HTML pages after changes are made in the back end.  This would only be applicable when updates are made to the HTML in the response for the given endpoint, so situations that involve server-side rendering.  In cases where the scripting manages the DOM entirely, we'll need to reload the compiled script.

This feature will need a Ktor plugin to facilitate.  The process would look something like:

1. User loads an HTML page. The Ktor automatic refresh plugin intercepts the HTML and injects a script that waits for an event from the back end.
2. Whenever the server reloads, the plugin sends an event to the front end script with the new HTML.
3. The front end script compares the new HTML with the original model and reloads the page if necessary.

A mechanism like this could be tricky to implement, but it will enable developers to quickly iterate on front-end changes without having to manually refresh the page.  This would be akin to the experience found for Javascript frameworks, which would be much elevated from anything else on the JVM.

In the next section, we'll provide more details on how we can achieve the full set of features to enable this enhanced development mode.

# Technical Details
[technical-details]: #technical-details

The new hot reload mechanism will be implemented entirely within the Gradle plugin.  We'll introduce a single `hotRun` task that will be used to start the server in development mode.

It will consist of the following:
1. Launching the main application with the [JetBrains runtime](https://github.com/JetBrains/JetBrainsRuntime).  This will allow for enhanced class redefinition ([DCEVM](https://ssw.jku.at/dcevm/))
2. A [hot swap agent](https://github.com/HotswapProjects/HotswapAgent) and plugin.  The agent handles replacing classes in the running JVM, and the plugin handles restarting the active server in the main process.
3. A forked continuous build process that watches for relevant sourcecode changes and recompiles the classes.  Generally this will just be `./gradlew classes -t`.

## JetBrains Runtime
[jetbrains-runtime]: #jetbrains-runtime

Standard JVM HotSwap (available via JPDA/debugger) is constrained to method-body modifications and cannot apply structural changes such as adding or removing methods, fields, superclasses, interfaces, or synthetic lambda classes.

To lift these restrictions, the development mode leverages the [JetBrains Runtime (JBR)](https://github.com/JetBrains/JetBrainsRuntime), which integrates Dynamic Code Evolution VM ([DCEVM](https://ssw.jku.at/dcevm/)). DCEVM allows full, in-place class redefinition at runtime when classes are recompiled, preserving active JVM state, open network connections, session caches, and warm JIT compilation without requiring a full JVM restart.

### Toolchain and Provisioning

The Gradle plugin configures the execution environment to use the JetBrains Runtime through Gradle's `JavaToolchainService`:

- **Java Version:** Targeted at Java 21 (`JavaLanguageVersion.of(21)`), which is the version currently compatible with JBR DCEVM enhanced redefinition.
- **Vendor:** Configured for `JvmVendorSpec.JETBRAINS`.
- **Auto-provisioning:** Gradle automatically locates locally installed JBR instances (such as those bundled with IntelliJ IDEA). When combined with the Foojay Toolchain Resolver plugin in `settings.gradle.kts`, Gradle can download and provision the JBR on demand if not present locally.

### JVM Arguments

The server process is launched with the following JVM flags:

- `-XX:+IgnoreUnrecognizedVMOptions`: Ensures compatibility if the task is executed on an alternative JVM that does not support JBR-specific flags.
- `-XX:+AllowEnhancedClassRedefinition`: Activates DCEVM's enhanced class redefinition engine for structural modifications.
- `-XX:HotswapAgent=external`: Notifies the runtime that class redefinitions will be driven by an external Java agent (`HotSwapAgent`) and opens internal JDK packages (such as `jdk.internal.loader`). This enables the agent to read application classloaders and monitor compiled output directories.

### HotSwapAgent Integration

Class redefinition is orchestrated using [HotSwapAgent](https://github.com/HotswapProjects/HotswapAgent) (`org.hotswapagent:hotswap-agent:2.0.3`) attached as a `-javaagent`:

- `autoHotswap=true`: Instructs the agent to actively watch compiled output directories on the classpath and redefine changed classes via the `Instrumentation` API without requiring an attached debugger.
- `disablePlugin=AnonymousClassPatch`: Disables HotSwapAgent's Java-specific anonymous class patch plugin, which fails when encountering Kotlin synthetic lambda class names (such as `...$configureRouting$1$1`). DCEVM handles Kotlin synthetic and lambda classes natively.
- **Plugin Merging:** HotSwapAgent requires custom plugins to reside on the same classloader as the agent jar itself. At runtime, the Gradle plugin dynamically merges a bundled `KtorReload` plugin into the agent jar (`build/runHot/runHot-agent.jar`), ensuring proper registration and execution of Ktor-specific reload hooks.

## Gradle Task Options
[gradle-task-options]: #gradle-task-options

The Gradle plugin introduces a dedicated `JavaExec` task (`runHot` / `hotRun`) that starts the Ktor application with the JetBrains Runtime, attaches the configured HotSwapAgent, and orchestrates background continuous recompilation.

### Continuous Recompilation (`HotRecompiler`)

To eliminate the need for a secondary terminal running continuous compilation, the task automatically forks a background continuous Gradle build (`./gradlew classes -t` or `gradlew.bat` on Windows):

- **Cache Isolation:** The continuous compilation process is launched with `--no-configuration-cache` and `--no-build-cache`. This ensures that source modifications are recompiled immediately and `.class` files are always rewritten to disk even when reverting changes, preventing cached `UP-TO-DATE` or `FROM-CACHE` hits from bypassing the file watcher.
- **Lifecycle Management:** The background recompiler process PID is tracked via a pidfile (`build/runHot/runHot.recompiler.pid`). Gradle's `FlowScope.always` hook registers a `StopHotRecompilerFlowAction` to reliably terminate the background process tree upon task completion or cancellation (including `Ctrl+C`). A JVM shutdown hook is also registered to support `--no-daemon` execution, and stale processes from previous runs are cleaned up on startup.

### Configuration Properties

Developers can customize the hot-run workflow using Gradle project properties:

| Property                                   | Default  | Description                                                                                                                                                                                                                                                                                                                                                                                                                   |
|--------------------------------------------|----------|-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `io.ktor.hotReload.autoRecompile`          | `true`   | Enables or disables the forked background continuous compilation process (`-Pio.ktor.hotReload.autoRecompile=false`). When disabled, developers can drive compilation manually through IDE auto-make or a separate terminal running `gradle -t classes`.                                                                                                                                                                      |
| `io.ktor.hotReload.verbose`                | `false`  | Enables verbose diagnostic logging (`-Pio.ktor.hotReload.verbose=true`). When enabled, surfaces raw continuous build output (prefixed with `[recompile]`) and elevates HotSwapAgent internal logging to `DEBUG` (`LOGGER=debug`). When disabled, output is streamlined to concise "Change detected, recompiling..." notices and elapsed reload durations (e.g. `Reloaded in 120ms`), while still surfacing build failures.    |
| `io.ktor.hotReload.embeddedServerAccessor` | *(none)* | Specifies a fully qualified static method returning `Collection<EmbeddedServer<*, *>>` (e.g., `-Pio.ktor.hotReload.embeddedServerAccessor=com.example.ApplicationKt#getEmbeddedServers`). When configured, the bundled `KtorReload` plugin invokes `EmbeddedServer.reload()` on all running server instances after class redefinition, re-running application modules to apply structural changes such as newly added routes. |

## Changes to Ktor Server

To support the new "hot run" execution, the Ktor server will need to remove all internal classloader handling and instead expose a static registry of any running server instances.  From this static property, the hot swap agent can call `reload()` on each of the running server instances.  This should simplify the embedded server handling to a large extent and reduce the specialization of the JVM source set.

## Front End Reloading
[front-end-reloading]: #front-end-reloading

For the release of the enhanced development mode, we'll introduce a new plugin for reloading HTML pages whenever the server restarts.  This will be a route-scoped plugin that can be installed on any endpoint serving HTML pages.

For the MVP, the mechanism will work as follows:
1. The plugin introduces an SSE endpoint that notifies the browser on all server restarts.
2. For every retrieval of HTML content within the route-scoped plugin, our plugin transforms the raw HTML and inserts a small script that watches for the restart events.
3. Whenever there is a server restart event, the browser will reload the page.

This process can be useful for a quick feedback loop, but it's primitive in this state.  With further development, we can investigate more sophisticated page updates.

Future work in this area would include:
1. Direct DOM tree updates; diffs and retention of dynamic content
2. Cataloging of referenced static assets on the page and the targeted reloading of these
3. Tracking dynamic content and reloading modified API endpoints

Each UI framework may differ in these approaches, so it would likely be best to provide as much instrumentation as possible without commiting to any specific implementation here.

## Kotlin Toolchain Support

With Kotlin Toolchain becoming more popular, we'll need to implement the same Gradle task for the Toolchain.  Currently, they do not have an extension repository, but we can contribute to their OOTB Ktor extension.

# Drawbacks
[drawbacks]: #drawbacks

This approach will require some breaking changes for the existing development mode.  For instance, the list of watch paths will need to be incorporated into the Gradle properties.  This will, however, allow for a more integrated build with proper input / output caching.

# Advantages
[advantages]: #advantages

The features described here should provide a much-needed improvement for development experience in Ktor.

# Future Directions
[future-directions]: #future-directions

As mentioned under [Front End Reloading](#front-end-reloading), providing a good experience for front-end development requires some specialized handling dependent on the UI framework used.  When Ktor is adopted more widely as a full-stack framework, we can provide some wrapper plugins here.