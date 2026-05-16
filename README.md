# Video AI Android SDK

Android client SDK for the [2Stars Video Platform](https://2stars.io) — real-time video, voice, and end-to-end encrypted messaging delivered as a hosted API.

```kotlin
val client = StarsClient(baseUrl = "https://api.2stars.io")
val room = client.connect(participantToken)
room.join("abc-defg-hij")

room.peers.collectLatest { peers -> render(peers) }
room.publishCamera()
room.publishMic()
```

## Install

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}

// app/build.gradle.kts
dependencies {
    implementation("com.github.2stars-io:video-android:0.5.1")
}
```

Min SDK: **24** · Kotlin: **1.9+** · coroutines-friendly throughout.

## Auth

You authenticate users by handing the SDK a short-lived **participant token** that your backend obtains from the 2Stars API. Never embed your 2Stars API key in the app.

```kotlin
// Backend mints a token via POST /video/v1/tokens
// → SDK consumes it on connect()
val token = myBackend.getParticipantToken()
val room  = client.connect(token)
```

## What's in the box

| Module | Use it for |
|---|---|
| `StarsClient` | Connection + room lifecycle |
| `Room`, `Peer`, `RoomEvent` | Room state, peer events |
| `LocalMedia` | Camera + mic publish |
| `ScreenShareSession` | Screen share |
| `Message` | E2E-encrypted chat |
| `Transcript` | Live speech-to-text |
| `Translation` | Real-time translation |
| `Assistant` | AI participant ("Hebbs") chat + voice |
| `WakeWord` | "Hey Hebbs" detection |
| `Correction`, `FactCheck` | Pre-send correction + inline fact-check |
| `BackgroundProcessor`, `AutoFrameProcessor`, `TintFrameProcessor` | Virtual backgrounds + auto-frame + simple tinting |
| `RemoteControl` | Remote desktop control over a shared screen |
| `CallForegroundService` | Keep the call alive when the user backgrounds the app (Doze-safe) |

## Module layout

| Module | What it is |
|---|---|
| `library/` | The SDK itself — published as the JitPack artifact |
| `sample/` | Minimal demo app for manual testing on a real device |

## Build locally

```bash
./gradlew :library:assembleDebug
./gradlew :sample:installDebug    # if a device / emulator is connected
```

Requires JDK 17 (set `JAVA_HOME` to a JBR 17 or any OpenJDK 17 — AGP 8.2 is incompatible with JDK 21).

## Companion SDKs

- [`@2stars/video-js`](https://github.com/2stars-io/video-js) — core JS SDK
- [`@2stars/video-react`](https://github.com/2stars-io/video-react) — React Provider + hooks
- [`com.github.2stars-io:verifai-android`](https://github.com/2stars-io/verifai-android) — VerifAI device-trust SDK for Android
- [OpenAPI spec](https://api.2stars.io/openapi/video.json) — generate a client in any language

## Versioning

Current: **0.5.1** — A2 + A4 + A5 + A6 capabilities shipped (media plane, E2E messaging, screen share, AI in-call). A7 / Whiteboard / Avatar are not yet implemented on Android. **E2E media-frame encryption (SFrame) is deferred to v0.6.0** — needs a native JNI bridge; the JS and Web SDKs have the full implementation today.

## License

MIT — see [LICENSE](./LICENSE).
