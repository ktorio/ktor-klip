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

Our main challenge for instrumenting Ktor to generate OpenAPI specifications is that we have very little to leverage from our routing implementation to infer the details of the resulting model.  Without any up-front declarations of parameters, response types, or errors, we need to rely on the introduction of new APIs to explicitly declare them.  This can lead to redundancies and inconsistencies.

# Design Details
[design-details]: #design-details

Provide a detailed description of the proposal. Include examples and explain its impact on users. Address:
- Usage examples to clarify the feature.
- How this will benefit users.
- Potential error messages or warnings if relevant.
- For technical proposals, focus on what contributors need to know about implementation and impacts.

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