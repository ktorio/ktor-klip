|             |                                                 |
|-------------|-------------------------------------------------|
| Feature     | OpenAPI Generation                              |
| Submitted   | 2024-12-01                                      |
| Accepted    | No                                              |
| Issue       | https://youtrack.jetbrains.com/issue/KTOR-8316/ |

### Contents

1. [Summary](#summary)
2. [Motivation](#motivation)
3. [Current Solutions](#current-solutions)
4. [Design Overview](#design-overview)
5. [Design Details](#design-details)
6. [Drawbacks](#drawbacks)
7. [Advantages](#advantages)
8. [Open Questions](#open-questions)
9. [Future Directions](#future-directions)

<hr />

# Summary
[summary]: #summary

The OpenAPI specification is a widely used standard for API documentation. It is a common format for API design, documentation, and code generation.

This is a proposal to support the generation of OpenAPI specifications from Ktor applications.  The specification must be generated from the applications defined routes and be available for download when the server is running.

# Motivation
[motivation]: #motivation

OpenAPI, or API documentation, has become a necessity in the fast-growing landscape of modern server-side systems. Several Ktor users expressed that OpenAPI generation is a hard requirement for their adoption of Ktor.  To stay relevant as a back-end framework, it is important for Ktor to support this.

# Current Solutions
[current-solutions]: #current-solutions

There have been a few approaches taken already to generating OpenAPI specifications from Ktor applications, but as we'll discuss, they have limitations that prevent widespread adoption.

## 1. IntelliJ Ultimate Plugin Auto-generator

The Intellij plugin cannot be used on CI, as this is necessary to guarantee correctness for critical infrastructure (and often user-facing infrastructure). It also fails to provide the full range of features, or customization, for the OpenAPI specification. 

## 2. Third-Party Ktor Plugins

There are a few third-party solutions for generating OpenAPI documentation for Ktor applications at runtime:

1. [Kompendium](https://github.com/bkbnio/kompendium)
2. [Smiley4](https://github.com/SMILEY4/ktor-openapi-tools)
3. [Tegral](https://github.com/utybo/tegral)

These are good solutions, but they are not officially supported by Jetbrains and are reportedly quite intrusive and/or verbose in their approach to the API.

## 3. InspeKtor Gradle Plugin

[InspeKtor](https://github.com/tabilzad/inspektor) is a Gradle plugin that can be used to generate OpenAPI specifications from Ktor application code.  It relies on embedding extra information with annotations next to your routes or type-safe resources. 

# Design Overview
[design-overview]: #design-overview

Our main challenge for instrumenting Ktor to generate OpenAPI specifications is that we have very little information from our routing implementation to use in the resulting model.  Without any up-front declarations of parameters, response types, or errors, we need to rely on the introduction of new APIs to explicitly declare them.  This can lead to redundancies and inconsistencies. 

We also have multiple relevant sources in a project that can contribute to the model (e.g. routing, authorization, content negotiation, etc.).  Each of these needs to be accounted for if we are to have a specification that represents the server implementation.

As far as the API design is concerned, we must also consider the key strengths of Ktor's routing API, so that we can enhance the API without changing its minimalist character.

To summarize our high-level requirements, the tooling must:
1. Generate an OpenAPI specification from the application's routes.
2. Provide mechanisms for injecting new information into the specification.
3. Prevent inconsistencies and redundancy by maintaining strong cohesion with application code.
4. Maintain the readability and simplicity of Ktor's routing API by requiring minimal changes.

# Design Details
[design-details]: #design-details

To address the requirements above, we propose a multifaceted approach:
1. An extensible runtime specification generator which can infer details from the application state (i.e., routing, authorization, etc.)
2. A compile-time code analysis tool for supplying all missing information to the specification generator.
3. (Second phase) Introduce a new routing API that includes a greater share of the required information to mitigate possible inconsistencies.

Here is how you might expect the API to look in the first phase of our design:

```kotlin
/**
 * Get a specific user by ID
 * 
 * @tag [Users]
 * @param id The user identifier
 * @response 200 [User] found
 * @response 404 [User] not found
 */
get("/{id}") {
    val id = call.parameters["id"]?.toInt() ?: throw BadRequestException("Invalid ID")
    call.respond(userService.getUser(id) ?: throw NotFoundException())
}
```

As shown in the example, we intend to inject the missing path information using the KDoc comment syntax.  Developers will be supported by IDE tooling to resolve code references in the comments, and it will prevent the need to modify any existing routes in the current routing API.

The injection of the KDoc comments into the specification will need to be handled through a gradle task, which may also include some top-level details like the name of the service:

```kotlin
// in build.gradle.kts
ktor {
    openapi {
        // top-level details may be provided in the gradle task call
        title = "My Service"
        description = "Does all sorts of cool things"
        version = "1.0.0"
        
        // configure the gradle task for reading comments
        analysis {
            enabled = true
            // output files, tweaking sources, etc.
        }
    }
}
```

As the server is running, it will merge multiple sources:
 - Files (output from the gradle task, or manually created)
 - Routing
 - Authorization plugin
 - Content negotiation plugin

These will be selected through your application properties file and exposed as a dynamic model which is served from your OpenAPI endpoint.

In the following section, we'll provide details on all of the above sources and how each field is populated to form the final OpenAPI specification.  Additionally, we'll cover the introspection API for processing the endpoint details that will be used for generating the specification.

# Technical Details
[technical-details]: #technical-details

In this section, we'll discuss the details of the implementation.

## Default path introspection from the Routing API

Our routing API builds an internal model which is already accessible from the application state.  It provides a limited set of details that can be used to populate the path information for the OpenAPI endpoints.

Here is an example of the routing API:

```kotlin
routing {
    route("/api/v1") {
        get("/users") {
            call.respond(userService.getUsers())
        }
        get("/users/{id}") {
            val id = call.parameters["id"]?.toInt() ?: throw BadRequestException("Invalid ID")
            call.respond(userService.getUser(id) ?: throw NotFoundException())
        }
        post("/users") {
            userService.createUser(call.receive())
            call.respond(HttpStatusCode.Created)
        }
    }
}
```

As each route in the example is defined from the DSL, it is added to the application's internal model.  We can use this model to infer details pertaining to each route:
1. The merged path
2. The path parameters
3. The HTTP method

Because the handling of parameters and responses is contained to the route's lambda argument, we cannot infer details about them for the specification.  As a result, the default model will appear very basic without additional sources.

To address this requirement, we indent to supplement the path information with a KDoc-like annotation API that can be read by our Gradle plugin.

## Annotation API

The annotation API provides a non-intrusive way to enhance the OpenAPI specification with details that cannot be inferred from code.

Each endpoint will need to be annotated with a KDoc comment that follows this general format:

```kotlin
/**
 * A summary of the endpoint
 * 
 * @<key> <value>
 * ...
 */
get("/widgets") {
    call.respond(widgetService.list())
}
```

Here is a list of the parameters to be supported:

| Tag            | Format                              | Description                                                  |
|----------------|-------------------------------------|--------------------------------------------------------------|
| `@tag`         | `@tag [TagName]`                    | Associates the endpoint with a tag for grouping              |
| `@param`       | `@param name description`           | Describes a path or query parameter                          |
| `@header`      | `@header name description`          | Describes a header parameter                                 |
| `@cookie`      | `@cookie name description`          | Describes a cookie parameter                                 |
| `@body`        | `@body [Type] description`          | Documents the request body type                              |
| `@response`    | `@response code [Type] description` | Documents a response code with optional type and description |
| `@deprecated`  | `@deprecated reason`                | Marks an endpoint as deprecated                              |
| `@description` | `@description text`                 | Provides a detailed endpoint description                     |
| `@security`    | `@security scheme`                  | Documents security requirements                              |

Note that for `@body` and `@response` fields, a type reference is specified, which will be used to automatically supply the schema definition in the OpenAPI specification.

By default, all parameters will be considered required unless the name is suffixed with a question mark (`?`).  For example, `@param name?` indicates that the parameter is optional.

There will be some cases where it will be impossible to relate an endpoint back to the comment, for example when a dynamic string is used to define the path.  In these cases, the developer will need to manually configure the provided model using the specification API.

## Specification API

To support the generation of the OpenAPI specification, we'll be extending our current OpenAPI plugin with several new functions that hook into the dynamic model generation.

Our current plugin has a single routing function that can be used for serving your specification from a file:

```kotlin
routing {
    openAPI(path="openapi", swaggerFile = "openapi/documentation.yaml") {
        codegen = StaticHtmlCodegen()
    }
}
```

We'll introduce a new function for generating the model dynamically:

```kotlin
fun Route.openAPI(path: String, modelSource: OpenAPISource = DefaultOpenAPISource, configure: OpenAPIConfig.() -> Unit = {})
```

Where the `OpenAPISource` argument is a functional interface:

```kotlin
fun interface OpenAPISource {
    fun generate(application: Application): OpenAPI
}
```

Now, instead of simply parsing the model from a file, you can provide any implementation for populating the model.

The `DefaultOpenAPISource` implementation will use a combination of the application's internal state and any model files supplied to some default paths.  To keep backwards compatability, it will first give preference to the `openapi/documentation.yaml` file, then fallback to the routing API's internal state, combined with the annotation API's output files.

## Gradle Plugin

The Gradle plugin component of this feature will be an extension of the current Ktor gradle plugin.  It will govern the task of generating parts of the OpenAPI specification during build time.

Returning to our earlier example, you can see the general appearance of the DSL inside a gradle build script:

```kotlin
// in build.gradle.kts
ktor {
    openapi {
        // top-level details may be provided in the gradle task call
        title = "My Service"
        description = "Does all sorts of cool things"
        version = "1.0.0"
        
        // configure the gradle task for reading comments
        analysis {
            enabled = true
            // output files, tweaking sources, etc.
        }
    }
}
```

Note that the general properties of the specification are provided through the top-level `openapi` block.  The analysis block is used to configure the Gradle task that will read the comments in your source code.

# Drawbacks
[drawbacks]: #drawbacks

The main drawback of using the annotation API is that it does not enforce correspondence between the actual sourcecode and the resulting specification.  You can, for example, change the response type without changing the comment, which will result in a discrepancy.

You could also argue that having multiple sources to compile the specification creates unneeded complexity, which could lead to some difficulty when tracing problems in your specification.

Eventually, we would like to introduce an alternative routing API that includes all information required for building the model during runtime.  This would eliminate the need for the annotation API, and the Gradle plugin, but it would be too disruptive to current users to replace their routing.

# Advantages
[advantages]: #advantages

The proposed solution addresses the need to provide an unobtrusive way to inject OpenAPI documentation into Ktor's current routing API.  It covers all requirements for serving the specification from the application and should satisfy the general needs for API developers.

# Future Directions
[future-directions]: #future-directions

In this document, we mentioned future plans for developing an alternative routing API that includes all required information.  We have not yet started work on this, but we expect to have a prototype ready in the next few months.