|             |                                                              |
|-------------|--------------------------------------------------------------|
| Feature     | Static content: Serve assets with content-hash in URL        |
| Submitted   | 2026-04-28                                                   |
| Accepted    | No                                                           |
| Issue       | [KTOR-6717](https://youtrack.jetbrains.com/issue/KTOR-6717/) |
| Preceded by |                                                              |
| Followed by |                                                              |

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

We describe a general solution for serving assets with content-hash in URL (asset fingerprinting).
Our intent is to provide a simple API to enhance performant static serving capabilities of a Ktor server.

# Motivation
[motivation]: #motivation

Content-hashing is a popular mechanism to provide optimal caching for static assets, like JavaScript and CSS.
Ktor server currently provides no way of doing this out of the box.

# Current Solutions
[current-solutions]: #current-solutions

Currently, you would have to do the hashing and serving "by hand".
You cannot build on the `staticFiles` or `staticResources` plugin, as they don't provide way to manipulate and expose the served path on file basis.

# Design Overview
[design-overview]: #design-overview

Computing the hash and serving under that computed URL, should be abstracted away.
The proposed solution allows one to reduce the boilerplate to the bare minimum, while providing a simple way of accessing all relevant values, including the served path.

# Design Details
[design-details]: #design-details

There are two core building blocks: _registries_ which provide one or more _revisioned files_.
A registry allows for registering files from various sources, like JVM resources, files, kotlinx.io Source, etc.
Registries also provide the base path. There can be an any amount of registries throughout the server.

```kotlin
package com.example

object AppAssets : RevFileRegistry("/assets/") {
    val main: RevisionedFile = resource("main.js")
    val styles: RevisionedFile = resource("styles.css")
}
```

The source, `resource` in the example above, will read the file and compute its hash eagerly.
The returned `RevisionedFile` is used to provide the hashed `path: String` and the file `content: OutgoingContent.ReadChannelContent`, among other secondary properties.

The registry objects are added when installing the dedicated plugin:

```kotlin
package com.example

fun Application.module() {
    install(RevFilePlugin) {
        +AppAssets
    }
}
```

The `RevFilePlugin` will hook into `onCall`, to check if any of the registries has a file for the requested path.
This lookup functionality is provided by the abstract `RevFileRegistry` class.
If the lookup returns a file, the `onCall` responds with its content and optimal cache headers, e.g. `max-age=31536000, public, immutable`.

Since the path is computed at runtime, other places, like an HTML `<link>` tag, must reference the computed path like so:

```kotlin
link(href = AppAssets.styles.path, rel = "stylesheet")
```

Using the revisioned files is library agnostic, and can be used everywhere where you can use a `String` for a path.

## Example: kotlinx.html integration

To integrate with kotlinx.html, nothing more than a simple extension function is required, which takes in a `RevisionedFile`:

```kotlin
@HtmlTagMarker
fun HEAD.stylesheet(file: RevisionedFile) {
    link(href = file.path, rel = "stylesheet")
}
```

Making the usage very simple:

```kotlin
head {
   stylesheet(AppAssets.styles)
}
```

## Additional data

The revisioned file can also provide its MIME type, e.g. to use in the HTML `type` attribute.

Since a hash is computed anyway, it can also be used to provide (weak) [ETags](https://developer.mozilla.org/en-US/docs/Web/HTTP/Reference/Headers/ETag)
and [subresource integrity](https://developer.mozilla.org/en-US/docs/Web/Security/Defenses/Subresource_Integrity).

## Custom Sources

Users can create sources on their own. To register a file, only the following values are required:

```kotlin
// method on RevFileRegistry
fun register(
    originalName: String,
    contentType: ContentType,
    content: OutgoingContent.ReadChannelContent
): RevisionedFile
```

# Technical Details
[technical-details]: #technical-details

The proposed plugin is completely stand-alone and requires no changes to other parts of Ktor.

You can find a working implementation in [this repository](https://github.com/JanMalch/ktor-revfile).

# Drawbacks
[drawbacks]: #drawbacks

In the proposed implementation all hashes are computed in a blocking manner at startup, delaying when the server can handle requests.
User can postpone this delay to the first request by using `by lazy`.

A while back, I also noticed minor issues with the [Caching headers plugin](https://ktor.io/docs/server-caching-headers.html), which added caching headers in addition to the one added by the proposed plugin.
Unless this is already fixed (or will be fixed), users must add an early return themselves:

```kotlin
install(CachingHeaders) {
        options { call, outgoingContent ->
            if (HttpHeaders.CacheControl in outgoingContent.headers) return@options null
            when (outgoingContent.contentType?.withoutParameters()) {
                ContentType.Text.JavaScript, ContentType.Text.CSS -> CachingOptions(CacheControl.MaxAge(maxAgeSeconds = 24 * 60 * 60))
                else -> null
            }
        }
}
```

# Advantages
[advantages]: #advantages

This design is optimal for both reducing boilerplate, while still being open for user extensions and custom usages.
It provides an intuitiv little DSL, while not having too much magic & indirection.

# Open Questions
[open-questions]: #open-questions

- The linked working implementation pulls in okio as a multi-platform dependency, to compute the hash. Is this fine? Are there better alternatives?
- Names, e.g. `RevisionedFile`, are up for discussion.

# Future Directions
[future-directions]: #future-directions

- More file sources
- Transforming contents, e.g. minifying JS and CSS
- More integrations with other libraries
