|             |                                                                                                                                       |
|-------------|---------------------------------------------------------------------------------------------------------------------------------------|
| Feature     | Dependency Injection                                                                                                                  |
| Submitted   | 2024-12-06                                                                                                                            |
| Accepted    | No                                                                                                                                    |
| Issue       | https://youtrack.jetbrains.com/issue/KTOR-6621/Make-Dependency-Injection-Usage-Simple                                                 |
| Preceded by | [Dependency Injection (Google Doc)](https://docs.google.com/document/d/1XJE-2AcQ9SH87Y1kK_0oJGCke_r3ewNhLEMCbE12FVc/edit?usp=sharing) |

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
   2. [Naming](#naming)
   3. [Resolution](#resolution)
      1. [Naming](#naming-1)
6. [Technical Details](#technical-details)
   1. [Instantiation by Reflection](#instantiation-by-reflection)
   2. [Validation](#validation)
      1. [Compile-time Validation](#compile-time-validation)
   3. [Dependency lifecycles](#dependency-lifecycles)
   4. [Performance](#performance)
   5. [Resolving from Configuration](#resolving-from-configuration)
   6. [Extensibility](#extensibility)
   7. [Testing](#testing)
7. [Drawbacks](#drawbacks)
8. [Advantages](#advantages)
9. [Open Questions](#open-questions)
10. [Future Directions](#future-directions)

<hr />

# Summary
[summary]: #summary

We describe a general solution for handling [dependency injection (DI)](https://en.wikipedia.org/wiki/Dependency_injection)
in Ktor server applications.  Our intent is to provide a simple, performant interface, while avoiding the drawbacks that
that can arise when working with the automatic configuration of dependencies.

# Motivation
[motivation]: #motivation

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

## Declaration
[declaration]: #declaration

As with declaring extensions in a Ktor application, users can expect to declare their dependencies from the
same scope: `Application`.

Here is an example module that provides some dependencies:

```kotlin
fun Application.configure() {
    dependencies {
        provide<DataSource> { PostgresDataSource("jdbc:postgresql://localhost:5440/test") }
        provide<Repository<User>> { UserRepository(resolve()) }
    }
}
```

Where the function calls here look like:

```kotlin
fun Application.dependencies(block: DependencyProviderContext.() -> Unit) { TODO() }

inline fun <reified T> DependencyProviderContext.provide(key: String? = null, noinline provide: suspend AsyncDependencyResolutionContext.() -> T): Unit =
    set(DependencyKey.of(typeInfo<T>(), key), provide)

suspend inline fun <reified T> AsyncDependencyResolutionContext.resolve(key: String? = null): T =
    get(DependencyKey.of(typeInfo<T>(), key))
```

And the context interfaces look like:

```kotlin
fun interface DependencyProviderContext {
    fun <T> set(key: DependencyKey, value: suspend AsyncDependencyResolutionContext.() -> T)
}
fun interface AsyncDependencyResolutionContext {
    suspend fun <T: Any> get(key: DependencyKey): T
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


## Resolution
[resolution]: #resolution

In practice, we can expect the declaring modules to be few, and the resolving modules to be many.

Here is an example of how to resolve a dependency from the `Application` scope:

```kotlin
fun Application.configure() {
    val repository by resolve<Repository<User>>()
}
```

And the function declarations would look like:

```kotlin
inline fun <reified T> Application.resolve(key: String? = null): Lazy<T> =
    attributes[AttributeKey<DependencyInjectionService>("DI")].resolutionContext().resolve(key)

inline fun <reified T> DependencyResolutionContext.resolve(key: String? = null): Lazy<T> =
    get(DependencyKey.of(typeInfo<T>(), key))
```

Which is using a context interface that looks like:

```kotlin
fun interface DependencyResolutionContext {
    fun <T: Any> get(key: DependencyKey): Lazy<T>
}
```

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
# application.yam / ktor / application 
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

## Instantiation by reflection
[instantiation-by-reflection]: #instantiation-by-reflection

Most dependency injection systems include some means for instantiating simple types by supplying resolving dependencies 
transitively through the reflection over the constructors.  There can be more than one constructor on the type, in which
case annotations are generally used for marking the correct one to use.  Alternatively, the framework could pick the 
first constructor that it can successfully resolve.  This can cause some confusion with traceability.

For the initial prototype of dependency injection in Ktor, we can forgo this automatic instantiation, but it would be 
useful to discuss the approach here for future reference.

There are a few ways to tackle this with the Ktor DI:

1. **Global configuration:** we can include some configuration property or attribute that modifies the behavior of 
   `resolve()` to always try constructing an instance when it is not directly declared.
2. **Declaration-side:** the lambda argument of the `provide` function could be optional, so that simply calling
   `provide<MyObject>()` will call the constructor of this type with some transitive inference.
3. **Resolution-side:** instead of calling the normal `resolve()` function, we could introduce a `construct()` function
   for explicitly creating a type.

We could implement one or more of these solutions for automatic construction.  For tooling on selecting the right 
constructor, we can include some logging when the framework decides automatically, indicating that an explicit 
declaration is required for overriding the behavior.  As for the choice process in the framework itself, one option
might be to attempt the constructor with the fewest arguments.  Some further research on the topic will be necessary.

## Validation
[validation]: #validation

Normally, validation occurs when calling `resolve()` in the application.  This will make an attempt to find the instance
of the expected type in the instance repository.  If nothing was declared for this type, then it will throw an exception
`DependencyMissingException`, unless otherwise implemented by a custom provider.

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

## Performance
[performance]: #performance

The general performance bottlenecks found in DI frameworks are caused from heavy up-front scans, heavy use of 
reflection, and repeated calls to slow instantiation.  These issues will be avoided in this design by using Kotlin's 
compile-time type inference (using `typeOf<T>()`), minimal use of reflection, and by only using singletons.

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

An upcoming feature includes resolving more complex types from properties, which could benefit from the use of this flow 
greatly.

## Extensibility
[extensibility]: #extensibility

Users will be able to override the default implementation or extend it with some cascading layers of resolution.

To do this, they can assign an attribute on the `Application` before declaring dependencies:

```kotlin
fun Application.configure() {
   attributes.put(DependencyInjectionServiceKey, MyDependencyInjectionService)
}
```

Where the key would set a service with the interface:

```kotlin
interface DependencyInjectionService {
    fun providerContext(): DependencyProviderContext
    fun resolutionContext(): DependencyResolutionContext
}
```

This way, the `provide()` and `resolve()` functions can be overridden as needed.

For example, if a user prefers to keep their Koin declarations while using the new DI system for resolution, they
could do something like this:

```kotlin
fun Application.configure() {
    attributes.put(DependencyInjectionServiceKey, object: DependencyInjectionService {
       fun providerContext(): DependencyProviderContext =
           throw IllegalStateException("")
       
       fun resolutionContext() = DependencyResolutionContext { key ->
           val (type, qualifier) = key.asKoinKey()
           getKoin().get(type, qualifier)
       }
    })
   
    koin { 
        modules(module {
             single<Repository<User>>(named("users")) { UserRepository() }
        })
    }
   
    val repository: Repository<User> = resolve()
}
```

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
 - Instantiation by reflection

Some further design will need to be done to introduce these.

# Future Directions
[future-directions]: #future-directions

With the introduction of a standard DI to Ktor, we can start introducing extensions that provide instances for use 
elsewhere in the application.  For example, we could update the Exposed plugin to register a `Database` instance, or 
we could introduce a generic LLM client instance that could be declared from configuration.

With the declaration-side extension point, this also opens the door for other providers.  If users prefer annotations
for injection, for example, they can introduce such a provider.