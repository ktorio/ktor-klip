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
3. [Glossary](#glossary)
4. [Current Solutions](#current-solutions)
5. [Design Details](#design-details)
6. [Technical Details](#technical-details)
7. [Drawbacks](#drawbacks)
8. [Advantages](#advantages)
9. [Open Questions](#open-questions)
10. [Future Directions](#future-directions)

<hr />

# Summary

Introduce Web Real-Time Communication (WebRTC) into Ktor to enable peer‑to‑peer communication between Ktor clients. 
This proposal covers client implementations only; the
[Interactive Connectivity Establishment (ICE)](https://en.wikipedia.org/wiki/Interactive_Connectivity_Establishment)
backend is intentionally omitted. Applications should integrate existing STUN/TURN infrastructure
such as [coturn](https://github.com/coturn/coturn).

High-level usage:

- [Create a `WebRtcClient`](#create-a-client) by choosing a platform engine factory.
    - JS/Wasm: `JsWebRtc`
    - Android: `AndroidWebRtc`
    - iOS: `IosWebRtc`
- [Create a `WebRtcPeerConnection`](#create-a-connection-and-negotiate-sdp) with an optional configuration.
- [Perform SDP offer/answer negotiation](#create-a-connection-and-negotiate-sdp).
- [Exchange ICE candidates](#ice-candidate-exchange) via signaling.
- [Create a data channel and send/receive messages](#data-channel).
- [Add local audio/video media tracks; observe remote tracks via `TrackEvent` flow](#media-tracks).

# Motivation

WebRTC has grown in popularity for peer-to-peer communication between clients across all platforms. All major browsers
now support the protocol, and there are libraries for the JVM, Android, and iOS. However, there is a notable absence of
support for a general Kotlin solution that can work seamlessly across all platforms. Introducing WebRTC to Ktor could
present a compelling story for developers wishing to connect clients of all platforms using a single codebase.

There has
been [some interest](https://youtrack.jetbrains.com/issue/KTOR-6645/Web-feedback-from-FAQ-https-ktor.io-docs-faq.html)
in Ktor support for WebRTC. As a multiplatform networking library for Kotlin, it does seem like a natural fit.

# Glossary

This section gives a concise primer on WebRTC, ICE, SDP, and RTP.

- WebRTC
    - WebRTC is a set of standards and APIs for real-time, peer-to-peer communication in browsers and native apps.
    - A peer creates a `RTCPeerConnection` (represented here as `WebRtcPeerConnection`) which can carry two kinds of
      streams:
        - `Media` (audio/video) via RTP and Secure RTP (SRTP)
        - `Data` via Stream Control Transmission Protocol (SCTP) over Datagram Transport Layer Security (DTLS)
+          (exposed as `DataChannel`)
    - WebRTC does not define signaling. Applications must exchange metadata (SDP offers/answers and ICE candidates) via
      an out-of-band channel (e.g., WebSocket, HTTP).

- ICE (Interactive Connectivity Establishment)
    - Purpose: find a working network path between peers across NATs/firewalls.
    - Components:
        - `ICE candidates`: potential connection endpoints gathered locally (host, server-reflexive via `STUN`, and
          relayed via `TURN`).
        - `STUN` servers help discover the public-facing address/port.
        - `TURN` servers relay traffic when direct paths fail.
    - Flow (simplified):
        1. Each peer gathers local candidates and sends them to the remote via signaling.
        2. Peers run connectivity checks on candidate pairs (using STUN binding requests) to pick the best path.
        3. ICE states evolve (`NEW` → `CHECKING` → `CONNECTED`/`COMPLETED` or `FAILED`).
    - In this API, you observe `iceCandidates` and `iceConnectionState`/`iceGatheringState`; you can
      `addIceCandidate(...)` and optionally `awaitIceGatheringComplete()`.

- SDP (Session Description Protocol)
    - Purpose: describe session capabilities and parameters and agree on them using the Offer/Answer model (RFC 3264).
    - Key parts:
        - Global attributes (e.g., `fingerprint`, `ice-ufrag`, `ice-pwd`, `setup` role for DTLS).
        - `m=` media sections for each media kind (audio, video, application/data).
        - Codec lists and RTP parameters (payload types, `rtcp-mux`, `rtcp-fb`, `fmtp`).
    - Flow (simplified):
        1. Caller creates an `offer` (`createOffer()`), sets it locally, and sends it via signaling.
        2. Callee sets remote offer, creates an `answer` (`createAnswer()`), sets it locally, and sends back.
        3. Caller sets a remote answer. Later renegotiations repeat as needed (e.g., after `addTrack()` or
           `restartIce()`).

- RTP / SRTP and RTCP
    - `RTP` transports time-sensitive media packets; `SRTP` is the secure form used by WebRTC (keys established via
      DTLS-SRTP).
    - `RTCP` carries control information (statistics, reception reports) used for quality adaptation.
    - Relevant to this API: stats obtained via `pc.stats`/`getStatistics()` expose bitrate, packet loss, jitter,
      round-trip time, etc., typically derived from RTP/RTCP reports.

# Current Solutions

## WebRTC Android

[WebRTC Android](https://github.com/GetStream/webrtc-android) is Google's WebRTC pre-compiled library for Android by
[Stream](https://getstream.io/). It reflects the recent [GetStream/webrtc](https://github.com/getstream/webrtc) updates
to facilitate real-time video chat using functional UI components, Kotlin extensions for Android, and Compose.

## webrtc-java

The [webrtc-java](https://github.com/devopvoid/webrtc-java) provides a Java wrapper for the
[WebRTC Native API](https://webrtc.github.io/webrtc-org/native-code/native-apis/), and works on several architectures.

## WebRTC Browser API

The [WebRTC API](https://developer.mozilla.org/en-US/docs/Web/API/WebRTC_API) is implemented for all major browsers and
can be accessed through JavaScript. There may still be some incompatibilities, so there is also a shim
[adapter.js](https://github.com/webrtcHacks/adapter) to avoid issues.
There exists a [kotlin-wrappers-browser](https://github.com/JetBrains/kotlin-wrappers) library for accessing main
browser APIs in Kotlin/JS and Kotlin/Wasm targets, including WebRTC.

## Peer.js

[Peer.js](https://peerjs.com/) is a JavaScript library that simplifies the interaction with the WebRTC API. It is the
most popular library for abstraction over WebRTC.

## WebRTC.rs

[webrtc-rs](https://github.com/webrtc-rs/webrtc) is a pure-Rust implementation of the WebRTC stack that does not rely on
+Google’s native C++ library. Media capture support is limited; it can be enough for raw data transfer on native
targets.

# Design Details

This KLIP introduces a common, multiplatform WebRTC client API layered around a pluggable engine abstraction. The API
focuses on:

- [A single `WebRtcClient` facade constructed from a platform engine factory](#create-a-client)
- [A `WebRtcPeerConnection` representing a P2P connection](#create-a-connection-and-negotiate-sdp)
- [Data channels](#data-channel) and [media track abstractions](#media-tracks)
- Reactive event streams over `Kotlin Flows` for [ICE](#ice-candidate-exchange),
  [signaling](#signaling), [tracks](#media-tracks), [data channels](#data-channel), and
  [statistics](#negotiation-triggers-and-statistics)
- Keeping behavior consistency across all supported platforms and making it clear if there are any differences 

## Minimal examples

### Create a client

```kotlin
// JS/Wasm
val jsClient = WebRtcClient(JsWebRtc) {
    defaultConnectionConfig = {
        iceServers = listOf(WebRtc.IceServer("stun:stun.l.google.com:19302"))
        statsRefreshRate = 5.seconds
    }
}

// Android
val androidClient = WebRtcClient(AndroidWebRtc) {
    // Required: provide Android context via engine config block
    context = appContext
    defaultConnectionConfig = {
        iceServers = listOf(WebRtc.IceServer("stun:stun.l.google.com:19302"))
    }
}

// iOS
val iosClient = WebRtcClient(IosWebRtc) {
    // the same config here
}
```

**Note:**

- `WebRtcClient` delegates `WebRtcEngine` and is `Closeable`; call `client.close()` to release resources when done.
- On Android, if you provide a custom `MediaTrackFactory`, you must also provide `AndroidWebRtcEngineConfig.rtcFactory`;
  otherwise an error will be thrown during `PeerConnection` creation.

### Create a connection and negotiate SDP

```kotlin
val pcCaller = jsClient.createPeerConnection() // or androidClient.createPeerConnection()

// Caller
val offer = pcCaller.createOffer()
pcCaller.setLocalDescription(offer)
// send offer.sdp to remote via your signaling

// Callee description
val pcCallee = jsClient.createPeerConnection() // or androidClient.createPeerConnection()
pcCallee.setRemoteDescription(WebRtc.SessionDescription(WebRtc.SessionDescriptionType.OFFER, remoteOfferSdp))
val answer = pcCallee.createAnswer()
pcCallee.setLocalDescription(answer)
// send answer.sdp back via signaling

// Caller applies answer
pcCaller.setRemoteDescription(WebRtc.SessionDescription(WebRtc.SessionDescriptionType.ANSWER, remoteAnswerSdp))
```

### ICE candidate exchange

```kotlin
// Local candidates -> send via signaling
scope.launch {
    pc1.iceCandidates.collect { candidate ->
        // send candidate.candidate, candidate.sdpMid, candidate.sdpMLineIndex
    }
}

// Remote candidates received via signaling
pc2.addIceCandidate(WebRtc.IceCandidate(candidateString, sdpMid, sdpMLineIndex))

// Optionally await complete gathering
pc2.awaitIceGatheringComplete() // pc2.iceGatheringState.first { it == IceGatheringState.COMPLETE }
```

### Data channel

```kotlin
// Create a negotiated or in-band channel on one side
val channel = pc1.createDataChannel("chat")

// Listen to the channel lifecycle from the connection
scope.launch {
    pc2.dataChannelEvents.collect { event ->
        when (event) {
            is DataChannelEvent.Open -> println("Opened a new data channel: ${event.channel}")
            is DataChannelEvent.Closed -> println("Data channel closed: ${event.channel}")
            else -> {}
        }
    }
}

// Send/receive messages using Kotlin's `Channel`-like API. 
scope.launch { channel.send("hello") }
scope.launch { println("recv: " + channel.receiveText()) }
```

### Media tracks

```kotlin
// Create local tracks via the client's MediaTrackFactory
// In the browser, it uses navigator.mediaDevices.getUserMedia under the hood.
// In Android, it uses Camera2 API. Request camera/microphone permissions manually.
val audio = jsClient.createAudioTrack { echoCancellation = true }
val video = jsClient.createVideoTrack { width = 1280; height = 720 }

// Add/remove tracks
// After adding or removing tracks, you should renegotiate the connection to let the remote peer know about the change.
// For example, call pc.restartIce() and repeat offer/answer exchange.
val audioSender = pc.addTrack(audio)
val videoSender = pc.addTrack(video)
// Remove later via sender or track
pc.removeTrack(audioSender)

// Observe remote tracks
scope.launch {
    pc.trackEvents.collect { ev ->
        when (ev) {
            is TrackEvent.Add -> println("remote track: ${ev.track.id} ${ev.track.kind}")
            is TrackEvent.Remove -> println("remote track removed: ${ev.track.id}")
        }
    }
}
```

### Negotiation triggers and statistics

- Listen to `negotiationNeeded` `SharedFlow` to decide when to produce a new offer (e.g., after `addTrack` or
  `restartIce`).
- Configure `statsRefreshRate` in `WebRtcConnectionConfig` to periodically collect and expose stats via `pc.stats`
  `StateFlow`.

### Signaling

- This API intentionally avoids bundling signaling. See an example
  in [Ktor Chat](https://github.com/ktorio/ktor-chat).

## Using with Compose Multiplatform examples

This API provides a high-level abstraction over WebRTC, but it is not a replacement for the platform-specific APIs.
There are many extensions that allow you to retrieve the implementations used under the hood. 
Platform-specific libraries are exposed as transitive libraries, except of `WebRTC-SDK` CocoaPod for iOS.
Code snippets are taken from [Ktor Chat](https://github.com/ktorio/ktor-chat).

### Compose/Web
```kotlin
@Composable
fun VideoRenderer(
    videoTrack: WebRtcMedia.VideoTrack,
    modifier: androidx.compose.ui.Modifier
) {
    fun getStream(): MediaStream {
        return org.w3c.dom.mediacapture.MediaStream().apply {
            val track: web.mediastreams.MediaStreamTrack = videoTrack.getNative() // `kotlin-wrappers`
            @Suppress("CAST_NEVER_SUCCEEDS") // cast `kotlin-wrappers` to `org.w3c.dom`
            addTrack(track as org.w3c.dom.mediacapture.MediaStreamTrack)
        }
    }
    WebElementView(
        factory = {
            (document.createElement("video") as HTMLVideoElement).apply {
                srcObject = getStream()
                autoplay = true
            }
        },
        modifier = modifier,
        update = { video ->
            video.srcObject = getStream()
        }
    )
}
```

### Compose/Android
```kotlin
@Composable
fun AudioRenderer(audioTrack: WebRtcMedia.AudioTrack) {
    DisposableEffect(audioTrack) {
        audioTrack.enable(true)
        onDispose {
            audioTrack.enable(false)
        }
    }
}
```

### Compose/iOS
```kotlin
@Composable
fun VideoRenderer(
    videoTrack: WebRtcMedia.VideoTrack,
    modifier: androidx.compose.ui.Modifier
) {
    UIKitView(
        factory = {
            `WebRTC-SDK`.RTCMTLVideoView().apply {
                videoTrack.getNative().addRenderer(this)
            }
        },
        modifier = modifier,
        onRelease = { 
            videoTrack.getNative().removeRenderer(it)
        },
    )
}
``` 

If you want to use the `getNative()` methods on iOS, you would need `WebRTC-SDK` CocoaPod added to your project because 
it cannot be transitively included in the library.
```kotlin
// build.gradle.kts
kotlin {
    cocoapods {
        // configure your pods here
        pod("WebRTC-SDK") {
            version = "..."
            moduleName = "WebRTC" // you can change the module name for convenience
            packageName = "WebRTC"
        }
    }
}
```

# Technical Details

Modules and targets

- `ktor-client-webrtc/common`: public API and common abstractions
- `ktor-client-webrtc/jsAndWasmShared`: JS+Wasm engine based
  on [kotlin-wrappers](https://github.com/JetBrains/kotlin-wrappers)
- `ktor-client-webrtc/android`: Android engine integration
  with [stream-webrtc-android](https://github.com/GetStream/webrtc-android)
- `ktor-client-webrtc/ios`: iOS engine integration based on
  [WebRTC-SDK CocoaPod](https://github.com/webrtc-sdk/Specs)
- `ktor-client-webrtc/test`: common test stubs
- Tests: common and platform-specific test stubs

Engine factories

- [JS/Wasm](#create-a-client): object `JsWebRtc` : `WebRtcClientEngineFactory<JsWebRtcEngineConfig>`
    - Uses browser `RTCPeerConnection` and `Navigator` media devices
- [Android](#create-a-client): object `AndroidWebRtc` : `WebRtcClientEngineFactory<AndroidWebRtcEngineConfig>`
    - Requires Android context; uses `PeerConnectionFactory` and Android media devices
    - If you supply a custom `MediaTrackFactory`, also set `AndroidWebRtcEngineConfig.rtcFactory`; otherwise engine initialization will fail
- [iOS](#create-a-client): object `IosWebRtc` : `WebRtcClientEngineFactory<IosWebRtcEngineConfig>`
    - If you supply a custom `MediaTrackFactory`, also set `IosWebRtcEngineConfig.rtcFactory`; otherwise engine initialization will fail

Configuration surfaces

- `WebRtcConfig` (engine-wide)
    - `dispatcher`: background coroutine dispatcher for events
    - `mediaTrackFactory`: override default media factory
    - `defaultConnectionConfig`: default configuration for new connections
- `WebRtcConnectionConfig` (per-connection)
    - `iceServers`, `iceCandidatePoolSize`, `bundlePolicy`, `rtcpMuxPolicy`, `iceTransportPolicy`
    - `statsRefreshRate`, replay sizes for events (`remoteTracksReplay`, `dataChannelEventsReplay`,
      `iceCandidatesReplay`)
    - `exceptionHandler` to catch background exceptions

Events and flows

- `WebRtcConnectionEvents` exposes `StateFlow`/`SharedFlow` for connection state, signaling state, ICE states,
  candidates, track events, data channel events, stats, and `negotiationNeeded`.
- Replay sizes are tunable via `WebRtcConnectionConfig`.

Data channels

- `WebRtcDataChannelOptions`: `id`, `protocol`, reliability (`ordered`, `maxRetransmits`, `maxPacketLifeTime`),
  `negotiated`
- `DataChannelReceiveOptions`: channel `capacity`, overflow behavior (`onBufferOverflow`), `onUndeliveredElement`
  callback

Media

- `WebRtcMedia` provides `Audio`/`VideoTrack` constraints (`echoCancellation`, `width`/`height`, `frameRate`,
  `facingMode`, etc.).
- `MediaTrackFactory` creates audio/video tracks; platform implementations wire permissions and device access.

Testing and diagnostics

- `statsRefreshRate` enables a periodic statistics collection surfaced via `pc.stats`, or manually request stats via
  `pc.getStatistics()`
- Exceptions: `WebRtc.SdpException`, `WebRtc.IceException`; media-specific exceptions in `WebRtcMedia` (
  `PermissionException`, `DeviceException`)

# Drawbacks

- No built-in signaling: application authors must implement signaling transport and protocol.
- Permissions complexity on Android and browsers (user prompts, device readiness).

# Advantages

- Unified Kotlin API over WebRTC for multiple targets.
- Reactive model via Kotlin Flows for connection, ICE, tracks, data channels, and stats.
- Flexible configuration for connection behavior and stats.

# Open Questions

- Expanded media features: screen capture, device enumeration/selection.
- Advanced RTP parameters, simulcast/SVC controls, and bandwidth adaptation APIs.
- Reliability and schema of stats across platforms; common model alignment.

# Future Directions

- Add JVM desktop engine via webrtc-java.
- Release `ktor-client-webrtc-rs` as [Gobley](https://gobley.dev/docs/) stabilizes.
- Minimize behavior differences across all platforms.
- Enrich media support: screen sharing, device selection, constraint negotiation.
- Not all WebRTC capabilities are abstracted yet (advanced RTP parameters, simulcast/SVC specifics, screen capture,
  etc.).
- Improve diagnostics: richer stats, logging hooks, and integration with Ktor tooling.
