|           |                                                                                                    |
|-----------|----------------------------------------------------------------------------------------------------|
| Feature   | OpenAPI Generation                                                                                 |
| Submitted | 2025-07-21                                                                                         |
| Accepted  | No                                                                                                 |
| Issue     | <https://youtrack.jetbrains.com/issue/KTOR-8316/>                                                  |
| Prototype | <https://github.com/ktorio/ktor-build-plugins/tree/bjhham/openapi-eap/samples/ktor-openapi-sample> |

### Contents

1. [Summary](#summary)
2. [Motivation](#motivation)
3. [Current Solutions](#current-solutions)
4. [Design Overview](#design-overview)
5. [Design Details](#design-details)
    1. [Static Analysis](#static-analysis)
    2. [Path Information API](#path-information-api)
6. [Technical Details](#technical-details)
    1. [Compiler plugin](#compiler-plugin)
    2. [Markdown Documentation API](#markdown-documentation-api)
    3. [Path Information API](#path-information-api-details)
    4. [Open API / Swagger Plugin Improvements](#open-api--swagger-plugin-improvements)
    5. [Extensibility](#extensibility)
    6. [Gradle Plugin](#gradle-plugin)
7. [Drawbacks](#drawbacks)
8. [Advantages](#advantages)
9. [Open Questions](#open-questions)
10. [Future Directions](#future-directions)

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

The IntelliJ plugin cannot be used on CI, as this is necessary to guarantee correctness for critical infrastructure (and often user-facing infrastructure). It also fails to provide the full range of features, or customization, for the OpenAPI specification. 

## 2. Third-Party Ktor Plugins

There are a few third-party solutions for generating OpenAPI documentation for Ktor applications at runtime:

1. [Kompendium](https://github.com/bkbnio/kompendium)
2. [Smiley4](https://github.com/SMILEY4/ktor-openapi-tools)
3. [Tegral](https://github.com/utybo/tegral)

These are all great solutions for runtime model generation, and will likely provide inspiration for future work on a more robust routing API; however, developers have voiced a desire for a less intrusive/verbose way to generate OpenAPI docs from their existing Ktor routes without the need for code changes.

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
1. **Static analysis:** A compile-time code analysis tool for supplying all missing information to the route from KDoc comments.
2. **Routing metadata API:** An extensible runtime routing metadata API for supplying and retrieving API details

## Static Analysis
[static-analysis]: #static-analysis

Here is a small example of the commenting API:

```kotlin
/**
 * Get a specific user by ID
 * 
 * - Tag: users
 * - Path: id [Long] The user identifier
 * - Responses:
 *   - 200 [User] The user with the supplied ID
 *   - 400 [ErrorMessage] Invalid ID
 *   - 404 [ErrorMessage] Not found
 */
get("/{id}") {
   val id = call.parameters["id"]?.toInt() ?: return@get call.respond(HttpStatusCode.BadRequest)
   call.respond(userService.getUser(id) ?: return@get call.respond(HttpStatusCode.NotFound))
}
```

As shown in the example, we can inject missing route information using the KDoc comment syntax. Developers will be supported by IDE tooling to resolve code references in the comments, and it will prevent the need to modify any existing routes in the current routing API.

The parsing of the KDoc comments into the specification will need to be handled through the Ktor Gradle plugin, which will be extended to allow top-level service details:

```kotlin
// in build.gradle.kts
ktor {
    @OptIn(OpenApiPreview::class)
    openapi {
        // toggles the compiler plugin
        enabled = true
        
        // toggles code inference
       codeInferenceEnabled = true
       
       // ignores uncommented endpoints
       onlyCommented = true
    }
}
```

The Ktor Gradle plugin will include a Kotlin compiler plugin to handle parsing the comments and inferring other request details from the routing call expressions.  This information will be made available as routing metadata by making small transformations to the routing code so that information is provided at runtime.

## Path Information API
[path-information-api]: #path-information-api

To include the extra information in our routing tree at runtime, we'll leverage the existing `Route.attributes` field for holding the extra information.

Declaring and retrieving the path info will be supported by easy-to-use extension functions and builder DSLs.

Here is how the extension function might look:

```kotlin
fun Route.describe(configure: OperationDsl.Builder.() -> Unit): Route {
    attributes[PathInfo.Attribute] = PathInfo.Builder().configure()
    return this
}
```

From the routing DSL, the inclusion of path information would appear as:

```kotlin
routing {
    get("/articles") {
        val query = call.queryParameters["q"]?.let(::parseQuery)
        call.respond(articleRepository.findArticles(query))
    }.describe {
        parameters {
            query("q")
        }
        responses {
            HttpStatusCode.OK {
                summary = "A list of articles"
                contentType = ContentType.Application.Json
                schema = jsonSchema<List<Article>>()
            }
        }
    }
}
```

This type of API can introduce a fair amount of clutter to your endpoints, which is why in general practice we expect the compiler plugin to supply this information from comments and static analysis.

In the following section, we'll go into greater detail on the specifics of both the static code analysis, and the runtime API.

# Technical Details
[technical-details]: #technical-details

In this section, we'll provide greater details on each of the OpenAPI specification system components.

## Compiler plugin
[compiler-plugin]: #compiler-plugin

Because Ktor's routing API is a builder DSL, there are no declarations, annotations, or references that we can leverage at runtime for populating API documentation; so if we want to keep our current style of routing, we must introduce some code transformations to provide this information.

Considering the earlier example:

```kotlin
get("/users/{id}") {
   val id = call.parameters["id"]?.toInt() ?: return@get call.respond(HttpStatusCode.BadRequest)
   call.respond(userService.getUser(id) ?: return@get call.respond(HttpStatusCode.NotFound))
}
```

We can infer the following:
- The method and path are already available at runtime through the route selector tree.
- The path parameter "id" is read.
- There are three types of responses:
  1. 400 Bad request
  2. 404 Not found
  3. 200 OK with the schema derived from the user type

Then, we can derive the default content type from the `ContentNegotiation` plugin.

Finally, we can provide this information at runtime by making the following transformation:

```kotlin
get("/users/{id}") {
   val id = call.parameters["id"]?.toInt() ?: return@get call.respond(HttpStatusCode.BadRequest)
   call.respond(userService.getUser(id) ?: return@get call.respond(HttpStatusCode.NotFound))
}.pathInfo {
    parameters {
        path("id")
    }
    responses {
        HttpStatusCode.BadRequest()
        HttpStatusCode.NotFound()
        HttpStatusCode.OK {
            contentType = ContentType.Application.Json
            schema = jsonSchema<List<Article>>()
        }
    }
}
```

Descriptions and other information can be supplied through comments, and developers can also supply metadata through the same parameter as needed.

To see all the types of available inferences at compile time, consult the following table:

| Inference           | Code Example                             |
|---------------------|------------------------------------------|
| Responses           | `call.respond(articles)`                 |
| Path Parameters     | `call.pathParameters["id"]`              |
| Query parameters    | `call.queryParameters["name"]`           |
| Request Headers     | `call.request.headers["X-Paging"]`       |
| Response Headers    | `call.response.header("X-Info", "abc")`  |
| Authenticate        | `authenticate("oauth2") {}`              |
| Authentication      | `authentication { basic("auth") }`       |
| Content Negotiation | `install(ContentNegotiation) { json() }` |
| Call Receive        | `call.receive<Post>()`                   |

As well as these code inferences, we'll also provide a means to augment your documentation using KDoc comments.

## Markdown Documentation API
[markdown-documentation-api]: #markdown-documentation-api

The annotation API provides a non-intrusive way to enhance the OpenAPI specification with details that cannot be inferred from code.

Each endpoint will need to be annotated with a KDoc comment that follows this general format:

```kotlin
/**
 * A summary of the endpoint
 * 
 * - <key>: <value>
 *   - <attribute-key>: <value>
 * ...
 */
get("/widgets") {
    call.respond(widgetService.list())
}
```

### Fields

Here is a list of the fields to be supported:

| Keyword              | Format                                          | Description                                                  |
|----------------------|-------------------------------------------------|--------------------------------------------------------------|
| tag                  | `tag: *name`                                    | Associates the endpoint with a tag for grouping              |
| path( parameter)(s)  | `path: [Type] name description`                 | Describes a path parameter                                   |
| query( parameter)(s) | `query: [Type] name description`                | Describes a query parameter                                  |
| header(s)            | `header: [Type] name description`               | Describes a header parameter                                 |
| cookie(s)            | `cookie: [Type] name description`               | Describes a cookie parameter                                 |
| body                 | `body: contentType [Type] description`          | Documents the request body type                              |
| response(s)          | `response: code contentType [Type] description` | Documents a response code with optional type and description |
| deprecated           | `deprecated: reason`                            | Marks an endpoint as deprecated                              |
| description          | `description: text`                             | Provides a detailed endpoint description                     |
| security             | `security: scheme`                              | Documents security requirements                              |
| externalDocs         | `externalDocs: href`                            | External documentation links                                 |
| ignore               | `ignore`                                        | Skips processing for this endpoint                           |

For a convenient short-hand, you can also group responses, tags, and parameters like so:

```markdown
- Responses:
  - 200 [String] A list of widgets
  – 404 [ErrorMessage] Not found
```

Applying extra attributes for these responses will be read when nested one level deeper than the response list item.

#### Type references

Note that for some fields, a type reference is specified, which will be used to automatically supply the schema definition in the OpenAPI specification.  This is an optional part of the definition, where in cases when it is unspecified, a default schema of `any` will be used.

Because KDoc links do not support optional modifiers or generics, we can use a custom syntax for indicating arrays, maps, and optionals.

| Modifier | Format           | JSON Schema mapping                                                          |
|----------|------------------|------------------------------------------------------------------------------|
| `+`      | `[String]+`      | [Array](https://json-schema.org/understanding-json-schema/reference/array)   |
| `?`      | `[String]?`      | [Required](https://www.learnjsonschema.com/2020-12/validation/required/)     |
| `:`      | `[String]:[Any]` | [Object](https://json-schema.org/understanding-json-schema/reference/object) |

#### Attributes

Many of the fields will have fields of their own for building the model.  We'll break these down into the following subsections:
- Parameter attributes: `path`, `query`, `header`, `cookie`
- Response attributes: `response`

##### Parameter attributes

| Tag          | Format                           | Default                                                                            |
|--------------|----------------------------------|------------------------------------------------------------------------------------|
| `required`   | `required: true/false`           | When type is provided, inferred from `?`.  Otherwise, `false` for all but `@path`. |
| `deprecated` | `deprecated: true/false`         | false                                                                              |

We'll also allow JSON Schema attributes to be included for all parameters.  You'll find these in a [Separate Appendix](appendices/open-api-json-schema-attributes.md).

##### Response attributes

| Tag          | Format                     | Default                                            |
|--------------|----------------------------|----------------------------------------------------|
| `headers`    | `headers:\n    key: value` | Empty; headers are provided in YAML object format. |

#### Full Example

Taking all the possible KDoc tags and extra attributes into consideration, here is an example of a complete KDoc comment:

```kotlin
routing {
    /**
     * Get a list of widgets
     * 
     * - Tag: widgets
     * - Path: [String] id Widget library ID
     *     pattern: [a-e0-9]{6,8}
     * - Query parameters:
     *   - [Int]? limit The maximum number of widgets to return
     *       minimum: 1
     *       default: 50
     *   - [String]? sort The sort field
     *       enum: [name, created]
     *       default: name
     *    - [Boolean] archived Whether to include archived widgets
     * - Responses:
     *   - 200 [String]:[com.acme.Widget] A list of widgets
     *   - 404 [com.acme.Widget]+ Not found
     */
    get("/widgets/{id}") {
        call.respond(repository.find(
            library = call.parameters["id"],
            limit = call.queryParameters["limit"]?.toIntOrNull() ?: 50,
            sort = call.queryParameters["sort"] ?: "name",
            archived = call.queryParameters["archived"]?.toBoolean() ?: false,
        ))
    }
}
```

There will be some cases where it will be impossible to relate an endpoint back to the comment, for example, when a dynamic string is used to define the path.  In these cases, the developer will need to manually configure the provided model using the specification API.

<a id="path-information-api-details"></a>
## Path Information API

The intent of the path information API is to provide a simple way to declare and retrieve relevant data regarding your endpoints in Ktor.  The information declared on your routes can be traversed, read, and combined to generate a full specification for OpenAPI or other kinds of contracts.

The basic function for storing to the route attributes was already provided in the design details, but here we'll explore the declaration DSL and how to retrieve the information at runtime.

### Declaration DSL

The operation details assigned to Ktor routes will have an accompanying DSL for injection and manipulation.

You can find a partial implementation at [appendices/open-api-dsl.kt](appendices/open-api-dsl.kt).

This DSL will be called from the compiler plugin to inject details inferred from the code structure.

### Route Traversal

With the newly introduced metadata for our routes, we can now produce a complete model by traversing all routes in the routing tree.

This can be done with the high-level procedure:
1. Get the root of the tree.
2. For each child:
    1. If node is a leaf, combine metadata from ancestors and include it as an operation model.
    2. Else, return to step 2.

We can perform this from an endpoint currently by starting from `call.route` and find the root through its parent relations, then using the metadata stored in the attributes to build the model.  This requires some casting from the interface `Route` to the implementation `RoutingNode`, so it's not ideal for extension.  We may want to introduce some helper functions and a visitor pattern to allow for easier analysis of the routing state of an application.

## Open API / Swagger Plugin Improvements

Because we're introducing the runtime construction of an OpenAPI model, we'll need to revisit our use of file references for the OpenAPI and Swagger plugins.  It's unlikely the routing will change during the execution of an application's lifetime, so the simplest approach would be to introduce some creation and caching pipeline for the model file.

## Extensibility
[extensibility]: #extensibility

The functionality of the generation ought to be extensible in the following ways:

1. Overriding the comment-parsing in the compiler plugin using a custom function.
2. Ability to serve different specifications using the OpenAPI sources.
3. Providing custom sources for the model.

## Gradle Plugin
[gradle-plugin]: #gradle-plugin

The Gradle plugin component of this feature will be an extension of the current Ktor gradle plugin.  It will handle the properties supplied to the compiler plugin, which will inject OpenAPI information into the routing DSL.

Returning to our earlier example, you can see the general appearance of the DSL inside a gradle build script:

```kotlin
// in build.gradle.kts
ktor {
    openapi {
        // enable or disable processing
        enabled = true
    }
}
```

# Drawbacks
[drawbacks]: #drawbacks

The main drawback of using the annotation API is that it does not enforce correspondence between the actual source code and the resulting specification.  You can, for example, change the response type without changing the comment, which will result in a discrepancy.

You could also argue that having multiple sources to compile the specification creates unneeded complexity, which could lead to some difficulty when tracing problems in your specification.

Eventually, we would like to introduce an alternative routing API that includes all information required for building the model during runtime.  This would eliminate the need for the annotation API, and the Gradle plugin, but it would be too disruptive to current users to replace their routing.

# Advantages
[advantages]: #advantages

The proposed solution addresses the need to provide an unobtrusive way to inject OpenAPI documentation into Ktor's current routing API.  It covers all requirements for serving the specification from the application and should satisfy the general needs for API developers.

# Open Questions
[open-questions]: #open-questions

There are some important technical details that will require testing:

1. How robust will the code analysis processing be?
    - For example, when calling the routing API from a custom function, can we trace the path value?
2. What will be the performance impact?
    - If there is an impact, we ought to relegate the Gradle task to production builds only. 
3. How can we update the specification incrementally at runtime?
    - It would be ideal if changes to the comments could be detected and reflected in the specification while the server is running in development mode.

During the prototyping phase, we should find answers to these questions and adjust the design accordingly.

# Future Directions
[future-directions]: #future-directions

In this document, we mentioned plans for developing an alternative routing API that includes all required information.

For one possible approach to a more feature-rich routing API, you can explore the [Ktor-Typed](https://github.com/nomisRev/ktor-typed) repository.