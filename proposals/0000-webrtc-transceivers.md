|             |                                                 |
|-------------|-------------------------------------------------|
| Feature     | WebRTC Transceivers                             |
| Submitted   | 2026-09-11                                      |
| Accepted    | No                                              |
| Issue       | https://youtrack.jetbrains.com/issue/KTOR-9572/ |
| Preceded by | [WebRTC Client](0002-web-rtc.md)                |
| Followed by |                                                 |

### Contents

1. [Summary](#summary)
2. [Motivation](#motivation)
3. [Current Solutions](#current-solutions)
4. [Design Overview](#design-overview)
5. [Design Details](#design-details)
6. [Technical Details](#technical-details)
7. [Drawbacks](#drawbacks)
8. [Advantages](#advantages)
9. [Open Questions](#open-questions)
10. [Future Directions](#future-directions)

<hr />

> **Status of this document.** This is a discussion draft prepared from
> [KTOR-9572](https://youtrack.jetbrains.com/issue/KTOR-9572/) and from the WebRTC client sources as they exist on
> `main` at the time of writing. It decides nothing. Claims are either attributed to a source or explicitly labelled
> **Assumption**; every label marks something a reviewer needs to confirm before this document can be acted on.

# Summary
[summary]: #summary

The multiplatform WebRTC client introduced by [KLIP 0002](0002-web-rtc.md) models media as tracks: a track is handed to
a connection with `addTrack` and removed with `removeTrack`, and remote media arrives as `TrackEvent.Add` /
`TrackEvent.Remove`. The underlying WebRTC model is one level richer — each media section of a connection is an
`RtpTransceiver` pairing one sender with one receiver and carrying a direction (`sendrecv`, `sendonly`, `recvonly`,
`inactive`). This proposal adds that pairing to the common API: a `WebRtc.RtpTransceiver` type with its `mid`, sender,
receiver and direction, `addTransceiver` / `getTransceivers` on `WebRtcPeerConnection`, and a way to correlate a remote
track with the transceiver that delivered it. The existing track-level calls keep working and are defined in terms of
transceivers. Media capability negotiation beyond direction — simulcast, encoding parameters, codec preferences — stays
out of scope and remains where [KLIP 0002](0002-web-rtc.md) left it.

# Motivation
[motivation]: #motivation

[KTOR-9572](https://youtrack.jetbrains.com/issue/KTOR-9572/) states the problem as reported: users can work with tracks
but "do not have a clear way to control the sender/receiver pair", and asks for transceivers so that media direction can
be set, recv-only and send-only streams can be created, tracks can be replaced more predictably, and renegotiation
follows standard WebRTC behaviour. The issue carries no comments, attachments or linked issues, so the paragraph above
is the entire reporter-supplied context; the rest of this section is evidence gathered from the repository.

The current common surface has a sender but no receiver and no transceiver. `WebRtcPeerConnection` exposes
`addTrack(track): WebRtc.RtpSender`, `removeTrack(sender)` and `removeTrack(track)`
([`WebRtcPeerConnection.kt`](https://github.com/ktorio/ktor/blob/main/ktor-client/ktor-client-webrtc/common/src/io/ktor/client/webrtc/WebRtcPeerConnection.kt)),
and `WebRtc` declares `RtpSender` with `track`, `dtmf`, `replaceTrack`, `getParameters` and `setParameters`
([`WebRtc.kt`](https://github.com/ktorio/ktor/blob/main/ktor-client/ktor-client-webrtc/common/src/io/ktor/client/webrtc/WebRtc.kt)).
The published API dump
([`ktor-client-webrtc.klib.api`](https://github.com/ktorio/ktor/blob/main/ktor-client/ktor-client-webrtc/api/ktor-client-webrtc.klib.api))
contains no declaration whose name mentions a receiver or a transceiver. Four consequences follow, and three of them
are visible inside the implementation rather than in user code:

1. **A receive-only connection cannot be expressed in common code.** Every media section is created as a side effect of
   supplying a local track, so a peer that only wants to receive has nothing to call.

2. **One engine already compensates with a hidden workaround.** The `webrtc-rs` engine passes
   `addDefaultTransceivers = true` unconditionally when creating a connection
   ([`Engine.kt`](https://github.com/ktorio/ktor/blob/main/ktor-client/ktor-client-webrtc/ktor-client-webrtc-rs/common/src/io/ktor/client/webrtc/rs/Engine.kt)),
   which makes the Rust layer add one audio and one video `sendrecv` transceiver to every peer connection "to emulate
   browser behavior"
   ([`lib.rs`](https://github.com/ktorio/ktor/blob/main/ktor-client/ktor-client-webrtc/ktor-client-webrtc-rs/common/rust/lib.rs)).
   The flag is not reachable from the common configuration, so an application cannot opt out of two media sections it
   may never use.

3. **Remote track removal has to be inferred.** Because direction is not part of the common model, the same engine
   listens for the native mute callback and consults `transceiver.current_direction()`, reporting a removal when the
   direction is `sendonly` or `inactive`
   ([`connection.rs`](https://github.com/ktorio/ktor/blob/main/ktor-client/ktor-client-webrtc/ktor-client-webrtc-rs/common/rust/connection.rs));
   the comment in that file attributes the approach to a library constraint and notes that reading track state straight
   after renegotiation is racy.

4. **Remote tracks cannot be attributed to a media section.** `TrackEvent.Add` and `TrackEvent.Remove` carry only a
   `WebRtcMedia.Track`
   ([`WebRtcPeerConnectionEvents.kt`](https://github.com/ktorio/ktor/blob/main/ktor-client/ktor-client-webrtc/common/src/io/ktor/client/webrtc/WebRtcPeerConnectionEvents.kt)),
   so an application receiving two video tracks has no `mid` and no transceiver identity with which to decide which
   stream is which, and no supported way to stop receiving one of them.

Points 2 and 3 are the strongest argument for acting now rather than later: the abstraction is already needed inside the
library, and its absence is being papered over per engine, which is exactly the kind of cross-platform behaviour
divergence [KLIP 0002](0002-web-rtc.md) set out to avoid.

# Current Solutions
[current-solutions]: #current-solutions

**Ktor today: tracks only.** `addTrack` returns an `RtpSender`, which is enough to call `replaceTrack` — so the
"replacing tracks" half of the issue is partly covered already — but not enough to read or set a direction, to obtain a
`mid`, or to create a media section without a local track.

**Ktor today: drop to the native object.** Every engine publishes a `getNative()` extension on
`WebRtcPeerConnection`: `web.rtc.RTCPeerConnection` on JS/Wasm, `WebRTC.RTCPeerConnection` on iOS,
`org.webrtc.PeerConnection` on Android and `dev.onvoid.webrtc.RTCPeerConnection` on the JVM (per the per-target API
dumps under
[`ktor-client-webrtc/api`](https://github.com/ktorio/ktor/tree/main/ktor-client/ktor-client-webrtc/api)).
An application can therefore reach the platform transceiver API today, at the cost of writing and testing a separate
`actual` per target — which is the cost the client exists to remove. **Assumption:** each of those native types exposes
transceiver creation and direction control; this is true of the browser API and of `webrtc-rs` (see
[`lib.rs`](https://github.com/ktorio/ktor/blob/main/ktor-client/ktor-client-webrtc/ktor-client-webrtc-rs/common/rust/lib.rs)),
and has not been verified in this draft for the Android, iOS and JVM bindings.

**Ktor today: rewrite the SDP.** Direction is an `a=sendrecv` / `a=recvonly` attribute of an `m=` section, so an
application can edit the string returned by `createOffer` before `setLocalDescription`. This works without any new API
and is how some JavaScript codebases predate the transceiver API. It is also unsupported, silently breaks when a codec
or extension changes the section layout, and cannot create a section that the engine did not offer.

**Other libraries.** The browser API, the Android and iOS builds of libwebrtc, `webrtc-java` and `webrtc-rs` all model
media as transceivers rather than as bare tracks; `addTrack` in those libraries is documented as a convenience that
reuses or creates one. Ktor is the outlier in exposing only the convenience.

# Design Overview
[design-overview]: #design-overview

The pain points from [Motivation](#motivation) reduce to one gap: the common API omits the object that owns direction
and identity for a media section. The proposal closes it in four additive steps and changes no existing behaviour.

1. **Name the pair.** Add `WebRtc.RtpReceiver` and `WebRtc.RtpTransceiver` next to the existing `WebRtc.RtpSender`, plus
   a `WebRtc.RtpTransceiverDirection` enum.
2. **Let users create one.** Add `WebRtcPeerConnection.addTransceiver`, by track or by media kind, with an options block
   carrying the initial direction and stream ids, and `getTransceivers()` to enumerate the current ones.
3. **Make direction settable.** `setDirection` on a transceiver, and `stop()` to retire a media section; both feed the
   existing `negotiationNeeded` flow so renegotiation stays under application control, as it is today.
4. **Attribute remote media.** Give the track events access to the transceiver that delivered the track, so `mid` and
   direction are available where remote tracks are handled.

Everything else is deliberately excluded: `sendEncodings` and simulcast, codec preferences, and per-encoding bandwidth
control. Those belong to the RTP-parameters area that [KLIP 0002](0002-web-rtc.md) already lists under its own open
questions, and folding them in here would make the surface far larger than the issue asks for.

## Alternatives considered

**A. Direction on the existing types instead of a new one.** Add a direction argument to `addTrack` and a `setDirection`
method to `RtpSender`. *For:* the smallest possible surface, no new public types, no new event shape. *Against:* it
cannot express a media section with no local track, which is the reporter's recv-only case; there is still no `mid` and
no receiver, so remote tracks remain unattributable; and it invents a Ktor-specific model that engine authors have to
map onto the transceiver model by hand, which is the situation that produced the `webrtc-rs` workaround. It is a cheaper
change that leaves most of the issue unsolved.

**B. No common API; document the native escape hatch.** Publish guidance for using `getNative()` per target. *For:* zero
new public API, zero maintenance, no per-engine capability matrix to keep honest. *Against:* it moves multiplatform code
into `expect`/`actual` for the one part of WebRTC that is most alike across platforms, and leaves the two
implementation-side problems — the hard-coded default transceivers and the inferred removal event — in place, because
neither can be fixed without a common notion of direction.

**C. The full RTP surface in one step.** Transceivers together with `sendEncodings`, simulcast and codec preferences.
*For:* one coherent design review instead of two; matches the spec exactly; serves broadcasting use cases that
simulcast unlocks. *Against:* a much larger surface to freeze while the module is still young, and engine support is
uneven — simulcast in particular would need per-engine verification well beyond what
[KTOR-9572](https://youtrack.jetbrains.com/issue/KTOR-9572/) asks for. It also blocks the small, clearly motivated part
of the change behind the large, speculative part.

This proposal follows the middle path: the transceiver object and direction now, the encoding-level surface later
(see [Future Directions](#future-directions)).

# Design Details
[design-details]: #design-details

The signatures below are illustrative — they show shape and naming for discussion, not a final API.

## Types

```kotlin
public object WebRtc {
    // Existing: RtpSender, RtpParameters, DtmfSender, ...

    public enum class RtpTransceiverDirection { SEND_RECV, SEND_ONLY, RECV_ONLY, INACTIVE, STOPPED }

    public interface RtpReceiver {
        public val track: WebRtcMedia.Track?
        public suspend fun getParameters(): RtpParameters
    }

    public interface RtpTransceiver {
        /** Media-section identifier, `null` until the first answer has been applied. */
        public val mid: String?
        public val sender: RtpSender
        public val receiver: RtpReceiver

        /** Direction requested locally; the initial value comes from [RtpTransceiverOptions]. */
        public val direction: RtpTransceiverDirection

        /** Direction actually in effect after negotiation; `null` before the first answer. */
        public val currentDirection: RtpTransceiverDirection?

        public suspend fun setDirection(direction: RtpTransceiverDirection)

        /** Stops sending and receiving; the section is reported as [RtpTransceiverDirection.STOPPED]. */
        public suspend fun stop()
    }
}

public class RtpTransceiverOptions {
    public var direction: WebRtc.RtpTransceiverDirection = WebRtc.RtpTransceiverDirection.SEND_RECV
    public var streamIds: List<String> = emptyList()
}
```

`STOPPED` is included in the enum because a transceiver that has been stopped has to report something, but it is not a
value an application sets; whether that is better modelled as a separate `isStopped` flag is
[Open Question 2](#open-questions).

## Connection methods

```kotlin
public abstract class WebRtcPeerConnection {
    // Existing: addTrack, removeTrack, createOffer, createAnswer, ...

    public suspend fun addTransceiver(
        track: WebRtcMedia.Track,
        block: RtpTransceiverOptions.() -> Unit = {}
    ): WebRtc.RtpTransceiver

    public suspend fun addTransceiver(
        kind: WebRtcMedia.TrackType,
        block: RtpTransceiverOptions.() -> Unit = {}
    ): WebRtc.RtpTransceiver

    public suspend fun getTransceivers(): List<WebRtc.RtpTransceiver>
}
```

The `kind` overload is the one that answers the reporter's recv-only case: it takes
`WebRtcMedia.TrackType.AUDIO` or `VIDEO` — the enum that already exists — and needs no local track.

## Usage

Receive-only audio, with no microphone permission requested and no local track created:

```kotlin
val pc = client.createPeerConnection()
pc.addTransceiver(WebRtcMedia.TrackType.AUDIO) { direction = RECV_ONLY }

val offer = pc.createOffer()
pc.setLocalDescription(offer)
// send offer.sdp via your signaling
```

Muting an outgoing direction without tearing the media section down, then restoring it:

```kotlin
val video = pc.addTransceiver(cameraTrack) { direction = SEND_RECV }

video.setDirection(RECV_ONLY)   // stop sending, keep receiving; negotiationNeeded fires
// ... later
video.setDirection(SEND_RECV)
```

Renegotiation stays explicit and unchanged: `setDirection` and `stop` make the connection emit on the existing
`negotiationNeeded` flow, and the application decides when to produce the next offer, exactly as it does after
`addTrack` today.

Attributing a remote track to its media section:

```kotlin
scope.launch {
    pc.trackEvents.collect { event ->
        when (event) {
            is TrackEvent.Add -> render(event.track, slot = event.transceiver?.mid)
            is TrackEvent.Remove -> release(event.track)
        }
    }
}
```

The `transceiver` property is nullable so that an engine which cannot supply it — or an event synthesised by the library
rather than by the platform, as the `webrtc-rs` removal path does today — remains representable. Whether nullability is
the right answer, or whether the correlation belongs in a separate flow, is
[Open Question 3](#open-questions).

## Errors

**Assumption:** the failure modes are the ones the platform APIs already produce, so no new exception type is needed —
`addTransceiver` on a closed connection fails the way other calls on a closed connection do, and an invalid direction
transition surfaces as the existing `WebRtc.SdpException`. This has not been verified against each engine and is
[Open Question 4](#open-questions).

# Technical Details
[technical-details]: #technical-details

## Interaction with the existing track API

`addTrack` and `removeTrack` keep their signatures and their behaviour, and are specified in terms of the new model:
`addTrack` reuses a compatible existing transceiver or creates one, and returns that transceiver's sender;
`removeTrack` stops the sender's contribution to its transceiver rather than deleting the media section. This mirrors
the convention of the platform libraries and means an application can use `addTrack` and `addTransceiver` on the same
connection. **Assumption:** the existing engines already delegate to native `addTrack`, whose specified behaviour is the
reuse-or-create one; the native-side reuse rules were not re-derived per engine for this draft.

## Compatibility

The public entry point `WebRtcClient` is annotated `@ExperimentalKtorApi`
([`WebRtcClient.kt`](https://github.com/ktorio/ktor/blob/main/ktor-client/ktor-client-webrtc/common/src/io/ktor/client/webrtc/WebRtcClient.kt)),
and it is the only way to obtain a `WebRtcEngine` and hence a connection — so in practice all use of this API is
already behind an opt-in. The declarations that this proposal touches, `WebRtcPeerConnection` and the members of the
`WebRtc` object, are not themselves annotated in the checked sources. Whether that makes a source-breaking change
acceptable here is a call for the team, not for this document ([Open Question 1](#open-questions)); the design below
avoids needing one.

Three specific compatibility points:

- **Adding members to `WebRtcPeerConnection`.** The class has a public constructor
  ([`WebRtcPeerConnection.kt`](https://github.com/ktorio/ktor/blob/main/ktor-client/ktor-client-webrtc/common/src/io/ktor/client/webrtc/WebRtcPeerConnection.kt)),
  so a third party can implement an engine against it. New `abstract` members would break such an implementation;
  declaring `addTransceiver` and `getTransceivers` as non-abstract members that fail with an "unsupported by this
  engine" error unless overridden keeps out-of-tree engines compiling, and gives in-tree engines a place to land one at
  a time.
- **Changing `TrackEvent`.** `TrackEvent.Add` and `TrackEvent.Remove` are `public class`es with a single
  constructor parameter
  ([`WebRtcPeerConnectionEvents.kt`](https://github.com/ktorio/ktor/blob/main/ktor-client/ktor-client-webrtc/common/src/io/ktor/client/webrtc/WebRtcPeerConnectionEvents.kt)).
  Adding a `transceiver` property therefore needs either a new constructor with the old one kept as a deprecated
  secondary — source-compatible, binary-incompatible for anyone who constructed the event, which is realistically only
  the library itself and its tests — or a separate flow that leaves `TrackEvent` untouched. Both options are on the
  table in [Open Question 3](#open-questions).
- **API dumps.** The module validates its public API through checked-in dumps for the klib targets, the JVM and Android
  ([`ktor-client-webrtc/api`](https://github.com/ktorio/ktor/tree/main/ktor-client/ktor-client-webrtc/api)), so every
  addition here lands as a reviewable diff in those files.

## Migration

No user-visible migration is required: every change is additive and existing code keeps compiling and behaving
identically. Applications that want the new capabilities opt in by calling `addTransceiver` instead of `addTrack`.

Two internal clean-ups become possible once direction exists in the common model, and each should be a separate change
with its own review rather than part of this one:

- The hard-coded `addDefaultTransceivers = true` in the `webrtc-rs` engine
  ([`Engine.kt`](https://github.com/ktorio/ktor/blob/main/ktor-client/ktor-client-webrtc/ktor-client-webrtc-rs/common/src/io/ktor/client/webrtc/rs/Engine.kt))
  exists to emulate browser behaviour for applications that only receive. Applications able to declare a recv-only
  transceiver no longer need it, so it could become conditional or be dropped. Doing so changes the SDP that engine
  offers, which is a behaviour change for existing users and needs its own discussion.
- The mute-plus-`current_direction()` inference that synthesises `TrackEvent.Remove` in the same engine
  ([`connection.rs`](https://github.com/ktorio/ktor/blob/main/ktor-client/ktor-client-webrtc/ktor-client-webrtc-rs/common/rust/connection.rs))
  could be replaced by an observable direction transition, which the comment in that file suggests would also address
  the race it describes. **Assumption:** a direction-based signal is race-free where the track-state read is not; the
  comment identifies the problem but does not confirm the remedy.

## Per-engine work

Five engine families exist in the module: JS/Wasm, Android, iOS, JVM (`JvmWebRtc`, added under
[KTOR-8953](https://youtrack.jetbrains.com/issue/KTOR-8953/)) and the `webrtc-rs` native engine. Each needs a mapping
from `RtpTransceiver` onto its native object, and the `webrtc-rs` engine additionally needs the transceiver operations
surfaced through its UniFFI boundary — its Rust layer already imports the native direction type
([`connection.rs`](https://github.com/ktorio/ktor/blob/main/ktor-client/ktor-client-webrtc/ktor-client-webrtc-rs/common/rust/connection.rs)),
so the concept is present but not exported. Because the new methods are non-abstract with an unsupported-operation
default, engines can be enabled one at a time. **Assumption:** all five native libraries support transceiver creation
and direction changes; verified here only for `webrtc-rs`, which is [Open Question 5](#open-questions).

# Drawbacks
[drawbacks]: #drawbacks

- **The surface grows in the least stable direction.** Two interfaces, an enum, an options class and three connection
  methods, in a module that is still experimental and whose RTP-level design is unsettled. If the encoding-level API
  arrives later with a different shape, the two will have to be reconciled.
- **It exposes negotiation mechanics.** `mid`, `direction` and `currentDirection` require the user to understand the
  offer/answer model to use them correctly; `addTrack` deliberately hid that. The mitigation is that the simple path
  stays exactly as it is.
- **It invites a per-engine capability matrix.** If some engine cannot honour `stop()` or cannot report
  `currentDirection`, the API has to admit that in a way users can discover, and "supported on four of five engines" is
  a worse promise than "not supported anywhere".
- **Nullable `mid` and `currentDirection` leak negotiation timing into types.** Both are unavoidable — neither value
  exists before an answer is applied — but both are easy to misuse.
- **It may not be enough.** A reviewer may judge that direction without `sendEncodings` serves too few real
  applications to justify a new public type, which is alternative C in
  [Design Overview](#design-overview).

# Advantages
[advantages]: #advantages

- It answers all four capabilities the issue names — direction, recv-only and send-only sections, predictable track
  replacement through a stable sender, and standard renegotiation — with one concept rather than four ad-hoc additions.
- It is the model the underlying libraries already use, so engine implementations become thinner and the mapping is
  mechanical rather than interpretive.
- It removes the need for the two engine-side workarounds documented in [Motivation](#motivation), which are the kind of
  silent cross-platform divergence [KLIP 0002](0002-web-rtc.md) aimed to prevent.
- It is fully additive, so it costs existing users nothing and can be reverted before the module stabilises if the
  design proves wrong.
- Declining it has a cost too: the workarounds stay, recv-only stays impossible in common code, and every application
  that needs direction control writes per-platform code against `getNative()`.

# Open Questions
[open-questions]: #open-questions

1. **Is a source-breaking change acceptable in this module?** `WebRtcClient` is `@ExperimentalKtorApi` but
   `WebRtcPeerConnection` and the `WebRtc` types are not. If breaking changes are acceptable, several compromises in
   [Technical Details](#technical-details) — non-abstract engine methods, a deprecated secondary constructor — become
   unnecessary and the result is cleaner.
2. **How should a stopped transceiver be represented?** `STOPPED` as an enum entry that users never set, or a separate
   flag with the enum restricted to settable values.
3. **How should a remote track be correlated with its transceiver?** Add a nullable `transceiver` to `TrackEvent`, add a
   separate transceiver-event flow, or expose `getTransceivers()` only and let the application match by track id. The
   first changes an existing type; the second duplicates event plumbing; the third is weakest but costs nothing.
4. **Are new error types needed?** [Design Details](#design-details) assumes the existing `WebRtc.SdpException` and the
   current closed-connection behaviour suffice. This needs checking per engine.
5. **Which engines can implement this, and how completely?** Transceiver support is verified in this draft only for
   `webrtc-rs`. Android, iOS, JVM and JS/Wasm need a capability check before the surface is frozen — in particular
   whether each can report `currentDirection` and honour `stop()`.
6. **Should `addTrack` be discouraged once `addTransceiver` exists?** Keeping both is friendlier; keeping both also
   means two ways to do the same thing, with different renegotiation consequences.
7. **Should the `webrtc-rs` default transceivers change in the same release?** Removing them is a behaviour change for
   existing users of that engine; keeping them means a recv-only application on that engine still gets two media
   sections it did not ask for.
8. **Is direction alone a coherent release, or should it ship with `sendEncodings`?** Out of scope here by construction,
   but the boundary is a review decision rather than a settled fact.

# Future Directions
[future-directions]: #future-directions

- **Encoding-level control.** `sendEncodings` on transceiver creation, simulcast and SVC, per-encoding bitrate and
  `degradationPreference`. `WebRtc.RtpParameters` and `WebRtc.DegradationPreference` already exist, and
  [KLIP 0002](0002-web-rtc.md) lists this area under its own open questions; transceivers are the object those
  parameters hang off, so this proposal is a prerequisite rather than a competitor.
- **Codec preferences.** `setCodecPreferences` is a transceiver-level operation in the platform APIs and would follow
  naturally.
- **Richer receiver surface.** Jitter-buffer and playout controls, and per-receiver statistics keyed by `mid`, which
  would tie into the statistics model rather than the media model.
- **Track events carrying full media-section context.** If [Open Question 3](#open-questions) is resolved in favour of
  a separate flow, that flow is the place where stream ids and section lifecycle would later be reported.
