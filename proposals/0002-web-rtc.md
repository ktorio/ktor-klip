|             |                                                                                         |
|-------------|-----------------------------------------------------------------------------------------|
| Feature     | WebRTC Client                                                                           |
| Submitted   | 2024-12-13                                                                              |
| Accepted    | No                                                                                      |
| Issue       | [KTOR-7958 WebRTC Client](https://youtrack.jetbrains.com/issue/KTOR-7958/WebRTC-Client) |
| Preceded by |                                                                                         |
| Followed by |                                                                                         |

### Contents

1. [Summary](#summary)
2. [Motivation](#motivation)
3. [Current Solutions](#current-solutions)
4. [Design Overview](#design-overview)

<hr />

# Summary
[summary]: #summary

The introduction of the Web Real-Time Communication (WebRTC) protocol into Ktor, allowing peer-to-peer communication
between Ktor clients.  This feature includes only the client implementations, so the
[Interactive Connectivity Establishment (ICE)](https://en.wikipedia.org/wiki/Interactive_Connectivity_Establishment)
back-end will be omitted.

# Motivation
[motivation]: #motivation

WebRTC has grown in popularity for peer-to-peer communication between clients across all platforms.  All major browsers
now support the protocol, and there are libraries for the JVM, Android, and iOS.  However, there is a notable absence of
support for a general Kotlin solution that can work seamlessly across all platforms.  Introducing WebRTC to Ktor could
present a compelling story for developers wishing to connect clients of all platforms using a single codebase.

There has been [some interest](https://youtrack.jetbrains.com/issue/KTOR-6645/Web-feedback-from-FAQ-https-ktor.io-docs-faq.html)
in Ktor support for WebRTC.  As a multiplatform networking library for Kotlin, it does seem like a natural fit.

# Current Solutions
[current-solutions]: #current-solutions

## WebRTC Android

[WebRTC Android](https://github.com/GetStream/webrtc-android) is Google's WebRTC pre-compiled library for Android by
[Stream](https://getstream.io/). It reflects the recent [GetStream/webrtc](https://github.com/getstream/webrtc) updates
to facilitate real-time video chat using functional UI components, Kotlin extensions for Android, and Compose.

## webrtc-java

The [web-rtc](https://github.com/devopvoid/webrtc-java) provides a Java wrapper for the
[WebRTC Native API](https://webrtc.github.io/webrtc-org/native-code/native-apis/), and works on several architectures.

## WebRTC Browser API

The [WebRTC API](https://developer.mozilla.org/en-US/docs/Web/API/WebRTC_API) is implemented for all major browsers, and
can be accessed through Javascript.  There may still be some incompatibilities, so there is also a shim
[adapter.js](https://github.com/webrtcHacks/adapter) to avoid issues.  The Javascript API has yet to be ported to
[kotlinx.browser](https://github.com/Kotlin/kotlinx-browser), so it will require some more effort to access the browser
API.

## Peer.js

[Peer.js](https://peerjs.com/) is a Javascript library that simplifies the interaction with the WebRTC API.  It is the
most popular library for abstraction over WebRTC.

# Design Overview
[design-overview]: #design-overview

Our primary focus ought to be the introduction of a common API for abstracting WebRTC across all platforms.  For the
initial release, we'll publish the common API with a single implementation on the easiest platform.  Since we'll
probably be relying on different base implementations, the modules will be separated as `ktor-client-web-rtc` for the
common interfaces and `ktor-client-web-rtc-<impl>` for each implementation, rather than creating a single multiplatform
library.  Most likely, we can start with translating the Typescript API to Kotlin using
[dukat](https://github.com/Kotlin/dukat) for the `wasmJs` target.