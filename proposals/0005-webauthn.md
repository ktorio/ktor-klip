|             |                                     |
|-------------|-------------------------------------|
| Feature     | Native Passkey and WebAuthn Support |
| Submitted   | 2026-03-20                          |
| Accepted    | No                                  |
| Issue       | KTOR-5561 Support WebAuthn (FIDO2)  |
| Preceded by |                                     |
| Followed by |                                     |

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

# Summary

[summary]: #summary

This KLIP outlines an API design for a native passkey authentication provider in Ktor.
The design targets both the Ktor Server and the Ktor Client (leveraging Kotlin Multiplatform). It aims
to provide an idiomatic Kotlin DSL for passwordless authentication, abstracting the complexities of the FIDO2 and Web
Authentication (WebAuthn) standards.

# Motivation

[motivation]: #motivation

The software industry is rapidly deprecating shared-secret authentication (passwords) in favor of asymmetric
cryptographic proofs, driven by FIDO2 and WebAuthn. Traditional passwords account for the vast majority of
hacking-related breaches, whereas passkeys offer phishing-resistant, cryptographic security bound to a user's device.

# Current Solutions

[current-solutions]: #current-solutions

| Framework / Library    | Ecosystem            | State Abstraction                       | Route Handling           | Developer Experience / Architecture                                                                                 |
|------------------------|----------------------|-----------------------------------------|--------------------------|---------------------------------------------------------------------------------------------------------------------|
| Spring Security (6.4+) | Java                 | PublicKeyCredentialUserEntityRepository | Automatic via filter     | Highly automated but heavily coupled to Spring's internal session management and Jackson serialization.             |
| SimpleWebAuthn         | Node.js / TypeScript | None (Purely functional)                | Manual                   | Stateless and developer-friendly. Exports clear functions; leaves database integration entirely to the implementer. |
| Go-WebAuthn            | Golang               | webauthn.User interface                 | Manual via HTTP Handlers | Requires domain models to implement specific WebAuthn interfaces, coupling business logic to auth logic.            |

An idiomatic Ktor API should avoid heavy automation and interface-coupling. Instead, it should adopt functional purity
while using Ktor's AuthenticationProvider DSL to securely intercept requests.

# Design Overview

[design-overview]: #design-overview

The design proposes a native passkey provider. To ensure maximum security and FIDO2 conformance without reinventing
the wheel, the core cryptographic implementation will be built as a wrapper around Yubico's proven 
`java-webauthn-server` library.

What the Yubico library provides:

- Generating request objects (options)
- FIDO2 conformance
- CBOR parsing
- Attestation validation
- Signature verification

What Ktor will implement:

- The idiomatic Kotlin DSL
- HTTP request/response `kotlinx.serialization` (JSON handling)
- Session/challenge state management
- Populating the Ktor `AuthenticationContext`

### Authentication Process Diagram

The following sequence illustrates the handshake ceremony across the Ktor stack:

```text
+----------+          +-------------+          +-------------+          +------------+
|  Client  |          | Ktor Server |          | Yubico Core |          | Credential |
| (Browser)|          |             |          |             |          | Repository |
+----+-----+          +------+------+          +------+------+          +-----+------+
     |                       |                        |                       |
     | 1. Request options    |                        |                       |
     +---------------------->|                        |                       |
     | (GET /login/options)  |                        |                       |
     |                       | 2. Generate options    |                       |
     |                       +----------------------->|                       |
     |                       |                        |                       |
     |                       | 3. RequestOptions      |                       |
     |                       |<-----------------------+                       |
     |                       |                        |                       |
     |                       | 4. Save challenge      |                       |
     |                       +----------------------------------------------->|
     |                       |                        |                       |
     | 5. Return JSON options|                        |                       |
     |<----------------------+                        |                       |
     |                       |                        |                       |
     | 6. OS Biometrics/PIN  |                        |                       |
     +-------+               |                        |                       |
     |       |               |                        |                       |
     |<------+               |                        |                       |
     |                       |                        |                       |
     | 7. Signed Assertion   |                        |                       |
     +---------------------->|                        |                       |
     | (POST /login/verify)  |                        |                       |
     |                       | 8. Retrieve Challenge  |                       |
     |                       +----------------------------------------------->|
     |                       |                        |                       |
     |                       | 9. Verify Signature    |                       |
     |                       +----------------------->|                       |
     |                       |                        |                       |
     |                       | 10. Validation Result  |                       |
     |                       |<-----------------------+                       |
     |                       |                        |                       |
     |                       | 11. Update signCount   |                       |
     |                       +----------------------------------------------->|
     |                       |                        |                       |
     | 12. Success (Token)   |                        |                       |
     |<----------------------+                        |                       |
     |                       |                        |                       |
+----+-----+          +------+------+          +------+------+          +-----+------+
```

# Design Details

[design-details]: #design-details

### Server-Side Configuration DSL

The proposed passkey DSL configures the `Relying Party (RP)` identity and links the necessary persistence interfaces.

```kotlin
install(Authentication) {
    passkey("webauthn") {
        relyingParty {
            id = "example.com"
            name = "Ktor Secure App"
            origins = setOf("https://example.com")
        }

        // to be implemented by user, we can provide a default in-memory implementation
        challengeStore = RedisChallengeStore(redisClient)
        credentialRepository = dependencies.resolve<PasskeyCredentialRepository>()

        validate { credentialId, userHandle ->
            UserIdPrincipal(userHandle.decodeToString())
        }
    }
}
```

### Data Persistence Interfaces

Developers must implement two interfaces to handle database operations and temporary challenge storage.

**The Credential Repository:**

```kotlin
interface PasskeyCredentialRepository {
    suspend fun getCredentialsByUser(userHandle: ByteArray): List<PasskeyCredential>
    suspend fun getCredentialById(credentialId: ByteArray): PasskeyCredential?
    suspend fun saveCredential(userHandle: ByteArray, credential: PasskeyCredential)
    suspend fun updateSignatureCount(credentialId: ByteArray, newCount: Long)
}
```

*Note on `ByteArray`: This API strictly uses `ByteArray` rather than Base64 encoded Strings to pass data in the core
domain layer. This avoids constant encoding and decoding overhead when interfacing directly with the underlying
cryptographic library's raw binary requirements.*

**The Challenge Store:**

```kotlin
interface PasskeyChallengeStore {
    suspend fun saveChallenge(sessionId: String, challenge: ByteArray, ttl: Duration)
    suspend fun consumeChallenge(sessionId: String): ByteArray?
}
```

### Full Route Implementation Example

Rather than hiding route generation, developers explicitly map the endpoints, giving them full control over the HTTP
layer, error handling, and parameter parsing.

```kotlin
routing {
    route("/auth/passkey") {

        // 1. Generate Registration Options
        get("/register/options") {
            val user = call.sessions.get<UserSession>() ?: return@get call.respond(HttpStatusCode.Unauthorized)
            // The library extension function generates the challenge and uses the injected ChallengeStore
            val options = call.generatePasskeyRegistrationOptions(
                userHandle = user.id.toByteArray(),
                username = user.email
            )
            call.respond(options)
        }

        // 2. Verify Registration
        post("/register/verify") {
            val response = call.receive<PasskeyRegistrationResponse>()
            // The library verifies the payload against the Yubico core and saves via CredentialRepository
            val result = call.verifyPasskeyRegistration(response)
            if (result.isSuccess) call.respond(HttpStatusCode.OK) else call.respond(HttpStatusCode.BadRequest)
        }

        // 3. Generate Login Options
        get("/login/options") {
            val options = call.generatePasskeyLoginOptions()
            call.respond(options)
        }

        // 4. Verify Login (Intercepted by the Authentication Provider)
        authenticate("webauthn") {
            post("/login/verify") {
                // If we reach here, the passkey provider successfully verified the signature
                // against the challenge, checked the signCount, and populated the Principal.
                val principal = call.principal<UserIdPrincipal>()
                call.sessions.set(UserSession(principal!!.name))
                call.respondText("Successfully logged in!")
            }
        }
    }
}
```

# Technical Details

[technical-details]: #technical-details

### Client-Side API Proposal (Kotlin Multiplatform)

The Ktor Client Auth plugin can abstract the multistep client ceremony, using Kotlin Multiplatform to bridge native
APIs.

```kotlin
val client = HttpClient(CIO) {
    install(Auth) {
        passkey {
            authBasePath = "https://api.example.com/auth/passkey"
            promptAutomatically = true
        }
    }
}
```

The KMP actual implementations interface directly with the OS:

- **Android (`androidMain`)**: Maps to `CredentialManager.getCredential()`.
- **iOS (`iosMain`)**: Delegates to the Apple Secure Enclave via `AuthenticationServices`.
- **Web (`wasmJsMain` / `jsMain`)**: Invokes `window.navigator.credentials.get()`.

# Drawbacks

[drawbacks]: #drawbacks

- **Cryptographic Weight:** Pulling in the `java-webauthn-server` library adds external cryptographic dependencies to
  the Ktor server application.
- **Developer Responsibility:** Ktor's unopinionated nature means developers are still forced to manually implement database persistence for public keys and
  signature counters.

# Advantages

[advantages]: #advantages

- **Unparalleled Security:** Introduces out-of-the-box phishing resistance and passwordless capabilities to the Ktor
  ecosystem.
- **Multiplatform Synergy:** Radically simplifies client-side mobile and web development by bridging the native passkey
  prompts into a unified Ktor Client HTTP call.
- **Performance:** Avoiding string-conversions in the core domain by defaulting to `ByteArray` improves throughput
  during cryptographic validation.

# Open Questions

[open-questions]: #open-questions

- Should we export default `kotlinx.serialization` serializers for `ByteArray` to Base64URL internally to ensure
  developers don't have to write their own custom JSON mappers for the endpoints?

# Future Directions

[future-directions]: #future-directions

- **Automated Route Plugins:** While explicit routing is preferred, we could eventually offer a higher-level feature (
  e.g., `webAuthnRoutes()`) that automatically generates the standard four FIDO2 endpoints.
- **KMP Client Support:** Start with browser support and expand to iOS and Android.
