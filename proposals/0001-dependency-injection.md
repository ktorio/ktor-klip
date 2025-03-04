|             |                                                                                                                                       |
|-------------|---------------------------------------------------------------------------------------------------------------------------------------|
| Feature     | Dependency Injection                                                                                                                  |
| Submitted   | 2024-12-06                                                                                                                            |
| Accepted    | Yes                                                                                                                                   |
| Issue       | [KTOR-6621](https://youtrack.jetbrains.com/issue/KTOR-6621/Make-Dependency-Injection-Usage-Simple)                                    |
| Preceded by | [Dependency Injection (Google Doc)](https://docs.google.com/document/d/1XJE-2AcQ9SH87Y1kK_0oJGCke_r3ewNhLEMCbE12FVc/edit?usp=sharing) |
| Prototype   | [ktor-chat/dependency-injection](https://github.com/ktorio/ktor-chat/compare/main...dependency-injection)                             |

### Contents

1. [Summary](#summary)
2. [Motivation](#motivation)
3. [Current Solutions](#current-solutions)
   1. [Koin](#koin)
   2. [Kodein](#kodein)
   3. [Spring](#spring)
   4. [Dagger, Micronaut, etc.](#dagger-micronaut-etc)
4. [Design Overview](#design-overview)
5. [Design Details](#design-details)
   1. [Declaration](#declaration)
      1. [Naming](#naming)
   2. [Resolution](#resolution)
      1. [By Parameters](#by-parameters) 
      2. [Naming](#naming-1)
6. [Technical Details](#technical-details)
   1. [Automatic injection](#automatic-injection)
      1. [Configuration](#configuration)
   2. [Validation](#validation)
      1. [Compile-time Validation](#compile-time-validation)
   3. [Dependency lifecycles](#dependency-lifecycles)
      1. [Request-scoped dependencies](#request-scoped-dependencies)
      2. [Development mode](#development-mode)
      3. [Hooks](#hooks)
   4. [Performance](#performance)
   5. [Collision resolution](#collision-resolution)
   6. [Resolving from Configuration](#resolving-from-configuration)
   7. [Extensibility](#extensibility)
   8. [Testing](#testing)
7. [Drawbacks](#drawbacks)
8. [Advantages](#advantages)
9. [Open Questions](#open-questions)
10. [Future Directions](#future-directions)

<hr />

# Summary
[summary]: #summary

We describe a general solution for handling [dependency injection (DI)](https://en.wikipedia.org/wiki/Dependency_injection)
in Ktor server applications.  Our intent is to provide a simple, performant interface, while avoiding the drawbacks
that can arise when working with the automatic configuration of dependencies.

# Motivation
[motivation]: #motivation

Dependency injection (DI) is a technique for populating modules with their required components through an external 
injection service.  It allows for modules to reference only abstractions, while the injection service handles
the details of construction at runtime, thereby decoupling said modules from the implementations they depend on.  This 
decoupling allows for highly adaptive applications, where implementations can be replaced without modifying the 
existing code.

We receive many support requests from users having difficulty getting started with dependency injection in Ktor,
especially when coming from a background of using frameworks like Spring Boot.  There are several third party libraries
that can be used with Ktor to provide dependency injection; however, the process of selection, integration, and maintenance,
creates a barrier to adoption.  Furthermore, our documentation for more advanced use-cases can suffer from a lack of
DI support, which exacerbates challenges with learning the framework.

Our goal for this tooling is to provide a clear idiomatic approach to declaring and resolving dependencies in Ktor applications.
The resulting API should work "out of the box" without any external dependencies, but should also provide
some integration paths for existing projects to leverage their current DI solutions for declaring dependencies.

# Current Solutions
[current-solutions]: #current-solutions

### Koin
[koin]: #koin

[Koin](https://insert-koin.io/) is a lightweight implementation, which avoids using reflection and favors idiomatic Kotlin.

There is also a Ktor plugin provided in the project generator currently.

The API looks like this:

```kotlin
// installation
fun Application.installDependencies() {
    koin {
        modules(module {
            single { Service() }
            factory(named("key")) { Repository(get()) }
        })
    }
}

// resolution
fun Application.resolveDependencies() {
    val service by inject<Service>()
    val factory by inject<Repository>(named("key"))
}
```

Internally, this uses a concurrent hashmap of string keys (`KClass`, `Qualifier`, `Scope`) to factories, where the 
singleton values are basically lazy instantiated instances.  The map is scoped to a `KoinApplication` instance, which
is saved in the Ktor application's `attributes` map.  It is important to note that, because of the use of `KClass`, 
generic types are not differentiated and require the use of a named qualifier.

The Koin plugin also provides request-scoped instances, which could be handy for diagnostics or unsafe types.

### Kodein
[kodein]: #kodein

[Kodein](https://kosi-libs.org/kosi-libs/index.html) has a similar philosophy Koin, providing a lightweight implementation using a straightforward idiomatic 
Kotlin API.

A basic example looks like this:

```kotlin
val di = DI {
    bindProvider<Dice> { RandomDice(0, 5) }
    bindSingleton<DataSource> { SqliteDS.open("path/to/file") }
}

class Controller(private di: DI) {
    private val ds: DataSource by di.instance()
}
```

Kodein promises to ensure that order of injection is unimportant for resolving instances, and it can handle generic types.

There doesn't appear to be any Ktor-specific features for Kodein.

### Spring
[spring]: #spring

[Spring](https://docs.spring.io/spring-framework/reference/core/beans/introduction.html)'s DI system is integrated into the application framework, providing a mature, feature-rich solution for 
developers.  You can map instantiation details for types via XML, annotations, or programmatic injection.

Generally, behind the scenes, Spring leverages reflection to scan for injection annotations, then instantiates the 
classes at runtime using reflection, unless using programmatic elements for the module declaration.

The heavy use of reflection can complicate matters for debugging, and can create some performance lags when the server 
is warming up.  Ideally, a system that provides automatic injection ought to include some diagnostics so that users can 
navigate to the source of any given instance.  All things considered, the flexibility of the framework is noteworthy, 
and generally considered a positive for engineering loosely coupled applications.

### Dagger, Micronaut, etc.
[dagger-micronaut-etc]: #dagger-micronaut-etc

There are plenty more dependency injection frameworks that follow similar standards with the use of annotations.  In 
light of the Ktor philosophy and general approach to the API, we'll discount the use of annotations for dependency 
injection, because it would create too much incongruity with the framework.  To continue with our commitment of 
"no magic" programmatic configuration, we'll stick with frameworks that provide programmatic means for injection.

# Design Overview
[design-overview]: #design-overview

Drawing from our review of other solutions, we have compiled the following design goals and requirements for the 
integrated dependency injection system.

### Core Requirements

1. Usability
    - It must have a simple interface and be accessible for new users.
    - It must support named dependencies to resolve ambiguities.
    - It must avoid temporal coupling when declaring dependencies (i.e., declaration order should not matter).
    - It should work "out of the box" without introducing unnecessary complexity.
2. Performance
    - It must be performant, avoiding runtime bottlenecks such as excessive reflection or repeated heavy computations.
3. Modularity
    - The system must be optional and seamlessly integrate into the existing Ktor platform.
    - It must be extensible, so developers can adjust or override its behavior as needed.

### Type support

1. Support for generic types, interfaces, and type variance (e.g., covariant types during resolution).
2. Ability to instantiate types using reflection as an optional feature.

### Tooling

1. The system must support runtime references for flexibility in dependency selection.
2. It should allow dependency declaration and configuration via external files (e.g., `application.conf`).
3. It should avoid any reliance on annotations or meta-programming, staying consistent with Ktor's "no magic" philosophy.
4. It should permit compile-time validation of dependencies to avoid runtime errors from missing dependencies.

# Design Details
[design-details]: #design-details

We'll break down the API by the different phases of its usage:

1. **Declaration:** how the instantiation of different types is registered
2. **Resolution:** how we can access the different types from the running application

When developing a Ktor application, you can expect it to look something like this:

```kotlin
fun main() {
   embeddedServer(Netty, port = 8080, host = "0.0.0.0", module = Application::module)
      .start(wait = true)
}

fun Application.module() {
   declareDependencies()    // See "Declaration" section 
   configureApplication()   // See "Resolution" section
}
```

In this example, both modules are called from the main `module()`, but they can be treated as interchangeable units 
using configuration.

For example, when using YAML configuration: 

```yaml
# application.yaml / ktor / application 
modules:
   - io.ktor.example.ImplementationKt.declareDependencies
   - io.ktor.example.ApplicationKt.configureApplication
```

This way, we can introduce a dependency inversion where the `declareDependencies` module can live on a different runtime
classpath than the application module `configureApplication`, which has no knowledge of the implementation details.

In the next two sections, we'll provide the API design for both declaring and resolving dependencies.

## Declaration
[declaration]: #declaration

As with declaring extensions in a Ktor application, users can expect to declare their dependencies from the
same scope: `Application`.

Here is how to declare dependencies from an application module:

```kotlin
fun Application.declareDependencies() {
    dependencies {
        // Base case
        provide<DataSource> { PostgresDataSource("jdbc:postgresql://localhost:5440/test") }
        // With named instance, using resolve() from the provider context
        provide<DataSource>("mongo") { MongoDataSource(resolve()) }
        // Using constructor injection from a class reference
        provide<Repository<Message>>(MessageRepository::class)
        // Using resolution and constructor injection from the provider context
        provide { RoomRepository(resolve<DataSource>(), create(::RoomAuthorityAdapter)) }
    }
}
```

### Naming
[naming]: #naming

For the *dependencies* scope:

| Alternative | Reason for avoiding                  |
|-------------|--------------------------------------|
| di          | Not immediately recognizable acronym |
| inject      | confusing for Koin users             |
| providers   | too abstract                         |

For the *provide* function:

| Alternative | Reason for avoiding                                    |
|-------------|--------------------------------------------------------|
| inject      | confusing for Koin users                               |
| supply      | this suggests that there is some scarcity of instances |
| declare     | too abstract and non-operative                         |
| single      | awkward wording out of context                         |

For the *create* function:

| Alternative | Reason for avoiding                                          |
|-------------|--------------------------------------------------------------|
| construct   | too specific; this call should allow non-constructor lambdas |
| inject      | not the most important detail of the function                |


## Resolution
[resolution]: #resolution

In practice, we can expect the declaring modules to be few, and the resolving modules to be many.

Here are the examples of how to resolve dependencies from a Ktor module:

```kotlin
fun Application.configureRouting() {
    // Resolve by property delegation, this uses Lazy delegation
    val users: Repository<User> by dependencies
    // Using a named instance
    val mongo: DataSource by dependencies.named("mongo")
    // Resolve from the dependencies property in the Application scope, this triggers the evaluation
    val messages: Repository<Message> = dependencies.resolve()
   
    // Using the instances in the application code
    routing {
        get("/users") {
            call.respond(users.list())
        }
        get("/messages") {
           call.respond(messages.list())
        }
        // ...
    }
}
```

### By Parameters
[by-parameters]: #by-parameters

Alternatively, we'll provide the option to resolve dependencies using module parameters:

```kotlin
fun Application.configureRouting(
   users: Repository<User>,
   mongo: DataSource,
   messages: Repository<Message>,
) {
   routing {
      get("/users") {
         call.respond(users.list())
      }
      // ...
   }
}
```

This allows for more natural injection when invoking modules from code, while also providing some 
compile-time safety.


### Naming
[naming-1]: #naming-1

For the *resolve* function:

| Alternative | Reason for avoiding                                 |
|-------------|-----------------------------------------------------|
| get         | overused; conflicting; too general                  |
| inject      | doesn't seem to be the correct verb for the context |
| locate      | too specific; not immediately obvious what it does  |


# Technical Details
[technical-details]: #technical-details

In practice, we can expect developers to include several dependency declaration modules, followed by many more 
resolutions in the feature modules.  To handle this, the implementation will need to build a tree of dependencies 
from the set of declaration blocks, then perform the resolutions in a blocking scope at the site of the first 
"application-scoped" `resolve()` call.  This introduces temporal coupling between the two phases of declaration and 
resolution, though the declarations can happen in any order.

Developers will be able to leverage our current application module configurations, but with the added attention for 
dependency resolution.

For example, when configuring a server using a YAML file:

```yaml
# application.yaml / ktor / application 
modules:
   ## Declare dependencies
   - io.ktor.chat.RootModuleKt.rootModule
   - io.ktor.chat.DatabasesKt.databaseModule
   - io.ktor.chat.RepositoriesKt.repositoriesModule
   ## Declare features with resolve
   - io.ktor.chat.RestModuleKt.restModule
   - io.ktor.chat.HealthCheckKt.healthCheckModule
   - io.ktor.chat.AuthenticationKt.authModule
   - io.ktor.chat.UsersKt.usersModule
   - io.ktor.chat.MessagesKt.messagesModule
   - io.ktor.chat.RoomsKt.roomsModule
   - io.ktor.chat.MembershipsKt.membersModule
```

The same can be done using programmatic configuration, although this would be less flexible for managing modules at 
runtime in different environments.

## Automatic injection
[automatic-injection]: #automatic-injection

For declaring and resolving classes, our implementation will need to include some means for injecting parameters for 
automatic construction.  This introduces some direction in the code that can hinder traceability.  For this reason, 
we will avoid implicit construction on calls to `resolve()` and instead introduce an explicit API.

As mentioned in the design overview, when providing types through automatic instantiation, the function changes from
`resolve()` to `create()`.  This suggests that, rather than searching for the instance, we'll create it from its 
parameters.  Anywhere resolution takes place, the create function can be called.

The desired extent of automation in a dependency injection framework is a matter of personal preference.  For that 
reason, we will include means for configuring the default behaviors.

### Configuration
[configuration]: #configuration

#### Create behavior

1. **Parameter interpretation:** by default, a function parameter will be interpreted as a nameless key from its declared 
   type.  For named instances, we'll provide a standard `@Named` annotation.  For overriding this behavior, a new 
   function `(KParameter) -> DependencyKey` can be provided in the configuration.
2. **Parameter resolution:** by default, calling `create()` for a type will attempt to do the same for its parameters
   that are not already declared.  This transitive instantiation will be possible to be overridden through another
   function `(KParameter) -> ResolutionStrategy` where resolution strategy can be either `CREATE` or `RESOLVE`.
3. **Constructor selection:** we'll provide another standard annotation for marking the injection constructor 
   `@Inject` when there are multiple to choose from.  In the absence of a marker annotation, the platform will
   default to naively choosing the first viable constructor.  This can be overridden with another function 
   with the signature `(KClass<T>) -> Collection<KFunction<T>>`.

#### Resolve behavior

The standard behavior for `resolve()` will be to check the instance repository for the provided key and fail if nothing 
is found.  For some projects, developers may want to override this behavior by creating new instances, or searching 
some other source, instead.

#### Optionals and defaults

When resolving an optional or defaulted property, the standard behavior will be to use the default value when nothing 
is found, instead of throwing an exception. This behavior ought to be configurable in the plugin with a custom function.

## Validation
[validation]: #validation

Normally, validation occurs when calling `resolve()` in the application.  This will make an attempt to find the instance
of the expected type in the instance repository.  If nothing was declared for this type, then it will throw an exception
`DependencyMissingException`, unless otherwise implemented by a custom provider.  During declaration, there is also a 
risk of running into a deadlock from a circular reference, which warrants its own validation with a subtype of the 
missing dependency exception.

Runtime dependency resolution allows for added flexibility; however, it can lead to some frustration when the 
application fails due to missing dependencies.  For this, some DI frameworks have introduced compile-time checking to 
ensure that all dependencies can be resolved before you run the application.

### Compile-time validation
[compile-time-validation]: #compile-time-validation

For Kotlin applications, compile-time validation of sources will usually involve gradle extensions.  Since Ktor already
has a Gradle plugin, this seems like a natural fit for handling this kind of validation.  We should be able to check 
the classpath for the dependency injection package, then run the validation accordingly after the compilation step.  The 
validation can come in the form of a compiler plugin.

The general processing algorithm for the compiler plugin would go as this:

1. Find the application entry point through the specified main class.
2. Find the list of modules used in the application.
3. Trace through each module and build a set of provided dependencies from calls to `provide`.
4. Validate the `resolve` calls later in the modules against the set of provided.  When the dependency is missing, 
   include a compiler warning.

This feature will likely fall outside the scope of the first iteration, but it will be crucial for the developer 
experience.

## Dependency lifecycles
[dependency-lifecycles]: #dependency-lifecycles

Instances will be lazily created when called from a `resolve()` function during startup.  All instances will be created
once and kept for the lifetime of the application (i.e., singleton instances).  If users wish to use a factory, they can
simply provide a lambda for the instance, then invoke it at the site where it is required.  We won't provide any cleanup
API for instances, so if there is some need for cleanup, then it may go into a regular application shutdown hook.

### Request-scoped dependencies
[request-scoped-dependences]: #request-scoped-dependencies

Some frameworks also include the ability to create request-scoped dependencies, like the Ktor plugin for Koin.  We'll 
defer investigating this as an option until later iterations of the API.

### Development mode
[development-mode]: #development-mode

We will need to ensure that dependencies are resolved correctly after development-mode "hot" refreshes of the server.

### Hooks
[hooks]: #hooks

Most phases in the Ktor application lifecycle include some means of registering hooks, like startup or shutdown.  Until 
we hear a compelling use-case for invoking some code when dependencies are declared or resolved, we'll avoid introducing 
this functionality.

## Performance
[performance]: #performance

The general performance bottlenecks found in DI frameworks are caused from heavy up-front scans, heavy use of 
reflection, and repeated calls to slow instantiation.  These issues will be avoided in this design by using Kotlin's 
compile-time type inference (using `typeOf<T>()`), minimal use of reflection, and by only using singletons.

## Collision resolution
[collision-resolution]: #collision-resolution

Providing dependencies in a declarative way can lead to problems when the same type is declared multiple times.  For
these cases, we'll need to introduce some collision resolution strategies.  The most logical default strategy would be
to throw an exception when a dependency is declared twice, but for some cases it makes sense to infer some priority
from the context.  For example, when declaring dependencies for tests, we ought to allow overriding dependencies from
the test environment, so we may replace small pieces of functionality without over-exposing the test code to the project
anatomy.

## Resolving from configuration
[resolving-from-configuration]: #resolving-from-configuration

It's common for run-time dependency resolution to be based on configuration.  For this, we can introduce some custom
qualifiers for reading from the application's configuration.

Here's an example of what the API could look like:

```kotlin
fun Application.configure() {
    dependencies {
        provide<DataSource> { PostgresDataSource(resolveProperty("database.connectionUrl")) }
    }
}
```

An upcoming feature includes resolving more complex types from properties, which could benefit from the use of this 
flow.

## Extensibility
[extensibility]: #extensibility

Users will be able to override the default implementation or extend it using various configuration extension points.

To override the entire implementation:

```kotlin
fun Application.configureDependencies() {
   attributes.put(DependencyRegistryKey, MyDependencyRegistry)
}
```

Or for simpler use cases, different aspects can be overridden with the help of delegation:

```kotlin
fun Application.configureDependencies() {
    install(DI) {
        // override inspection and creation of types
        reflection = object: DependencyReflection by default.reflection {
            // override creation
            override fun <T: Any> create(kClass: KClass<T>, get: (DependencyKey) -> Any): T = TODO()
            // override constructor selection
            override fun <T: Any> constructors(kClass: KClass<T>): Collection<KFunction<T>> = TODO()
            // override KParameter to dependency key
            override fun toKey(parameter: KParameter): DependencyKey = TODO()
        }
        // override providing dependencies
        provider = object: DependencyProvider by default {
            override fun <T> set(key: DependencyKey, value: DependencyResolver.() -> T) = TODO()
        }
        // override resolving dependencies 
        resolver = object: DependencyResolver by default {
            override fun <T: Any> get(key: DependencyKey): T = TODO()
        }
    }
}
```

Here is a real-world example of how you can extend dependency resolution with Koin:

```kotlin
fun Application.rootModule() {
   install(Koin) {
      modules(module {
         single<Algorithm>(named("hash")) {
            Algorithm.HMAC256(property("security.secret"))
         }
      })
   }
   install(DI) {
      resolver = KoinResolver(default, getKoin())
   }
}

class KoinResolver(
   val base: DependencyRegistry,
   private val koin: Koin,
) : DependencyResolver by base {
   override fun <T : Any> get(key: DependencyKey): T =
      koin.getOrNull(key.type.type, key.name?.let(::StringQualifier))
         ?: base.get(key)
}
```

This allows resolving dependencies that are provided by Koin modules.
The full example can be found at [ktor-chat/tree/dependency-injection-koin-ext](https://github.com/ktorio/ktor-chat/tree/dependency-injection-koin-ext)

## Testing
[testing]: #testing

The introduction of DI to Ktor should improve the ease of testing.  Hard dependencies can now be replaced in the test 
server application configuration for mocking.

For example, using [kmock](https://github.com/bitPogo/kmock):

```kotlin
@Test
fun verifyEndpoint() = testApplication {
    dependencies {
        provide(Repository) { 
            kmock().also { it._fetch returns listOf(myEntity) } 
        }
    }
    configureMyEndpoint() // calls resolve<Repository>()
      
    // execute tests with client
}
```

In theory, the testing of the actual module resolution should occur during compilation. However, if this is impossible,
then users will be able to test that all modules can be resolved by simply including them in the application install
section for a unit test using `testApplication`.

# Drawbacks
[drawbacks]: #drawbacks

## Declarative dependency approach

This design follows the general pattern found in Koin or Kodein, which aligns more closely with idiomatic Kotlin.  This 
can be a bit of an obstacle for those who are familiar only with Spring or Dagger, who expect dependency resolution to 
be handled via annotations.

## Two-phase process

The expectation for developers to declare before resolving can lead to a run-time failure in the absence of compile-time 
checking. Ideally, we could lean on the compiler to enforce the ordering here by modifying the core Ktor API, but to 
keep this functionality as an extension of the framework, this is impossible.  With a compiler extension in the Ktor 
Gradle plugin, we can achieve the best of both worlds.

# Advantages
[advantages]: #advantages

By introducing this API to Ktor, we'll have lowered the barrier to using dependency injection, which should help greatly
with scaling out codebases and with the standardization of Ktor application structure.  Through the use of our simple API,
with options for extension, we'll have honored the Ktor values of maintaining simplicity, performance, and no magic.

# Open Questions
[open-questions]: #open-questions

Some aspects of the implementation were discussed earlier in the document but will not be available in the prototype.

They include:
 - Compile-time checking
 - Client-side injection
 - Request-scoped instances

Some further design will need to be done to introduce these.

# Future Directions
[future-directions]: #future-directions

With the introduction of a standard DI to Ktor, we can start introducing extensions that provide instances for use 
elsewhere in the application.  For example, we could update the Exposed plugin to register a `Database` instance, or 
we could introduce a generic LLM client instance that could be declared from configuration.

With the declaration-side extension point, this also opens the door for other providers.  If users prefer annotations
for injection, for example, they can introduce such a provider.