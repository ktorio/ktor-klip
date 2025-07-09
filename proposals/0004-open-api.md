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
 * @response [HttpStatusCode.NotFound] [User] not found
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

Dive into the technical specifics:
- Clarify interactions with existing features.
- Outline high-level implementation data.
- Discuss edge cases with examples if applicable.

# Drawbacks
[drawbacks]: #drawbacks

Discuss potential reasons against implementing this proposal. Note considerations that could demand a new proposal or adjustment.

# Advantages
[advantages]: #advantages

Detail why this design is optimal. Consider the impact of not proceeding with this proposal.

# Open Questions
[open-questions]: #open-questions

Outline any aspects that need resolution during the RFC review or implementation. Mention related topics that are out of scope but could be addressed later.

# Future Directions
[future-directions]: #future-directions

Explore potential future directions for your proposal. Consider how it might evolve and interact within the project. Use this section for ideas outside the current RFC's scope but relevant context.