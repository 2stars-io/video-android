# 2stars-android-sdk

Android client SDK for the [2Stars Video Platform](https://2stars.io) —
real-time video, voice, and end-to-end encrypted messaging delivered as a
hosted API.

> **Status: A1 (skeleton + auth)** — connection + room presence are wired
> up. Media plane (camera / mic / screen share), E2E messaging, and the AI
> features land in subsequent stages.

## Install

Distribution is via [JitPack](https://jitpack.io). Add the repository to
your project's `settings.gradle.kts`:

```kotlin
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}
```

Then add the SDK to your app's `build.gradle.kts`:

```kotlin
dependencies {
    // <YOUR-GITHUB-ORG> is set when the repository is published.
    implementation("com.github.<YOUR-GITHUB-ORG>:2stars-android-sdk:0.1.0")
}
```

Min SDK 24 (Android 7.0). The SDK is written in Kotlin and exposes a
coroutine-friendly API.

## Quick start

You authenticate users by handing the SDK a short-lived **participant
token** that your backend obtains from the 2Stars API. Never embed your
2Stars API key in the app.

```kotlin
import io.twostars.sdk.StarsClient
import kotlinx.coroutines.flow.collectLatest

class CallActivity : ComponentActivity() {
    private val client = StarsClient(baseUrl = "https://video-api.2stars.io")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lifecycleScope.launch {
            // 1. Get a participant token from your own backend.
            val token = api.getParticipantToken()

            // 2. Connect.
            val room = client.connect(token)

            // 3. Join a room. The roomCode comes from your app flow.
            room.join("abc-defg-hij")

            // 4. Observe peers.
            room.peers.collectLatest { peers ->
                // Render the participant list, attach video/audio (A2+).
            }
        }
    }
}
```

## Module layout

| Module      | What it is                                                        |
|-------------|-------------------------------------------------------------------|
| `library/`  | The SDK itself. Published as the JitPack artifact.               |
| `sample/`   | A minimal demo app for manual testing on a real device.          |

## Build locally

The repo ships a Gradle config but not the wrapper jar. Generate it once:

```bash
gradle wrapper --gradle-version 8.7
./gradlew :library:assembleDebug
./gradlew :sample:installDebug    # if a device or emulator is connected
```

(If you don't have Gradle installed:
[`brew install gradle`](https://gradle.org/install/) on macOS,
[scoop / chocolatey](https://gradle.org/install/) on Windows.)

## License

MIT — see [LICENSE](./LICENSE).

## Roadmap

- **A1 — skeleton + auth** *(current)* — connection lifecycle, `Room` API,
  peer-joined / peer-left events.
- **A2 — P2P media plane** — camera + mic publish/subscribe, up to 4
  participants.
- **A3 — SFU upgrade** — libmediasoupclient-android for >4-participant
  rooms.
- **A4 — E2E messaging** — AES-256-GCM messaging matching the JS SDK.
- **A5 — Screen share + recording-aware events**.
- **A6 — AI features** — transcription, wake word ("Hey 2Stars"), AI
  participant, summaries, whiteboard, virtual backgrounds, avatar.
