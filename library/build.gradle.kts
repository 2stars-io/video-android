plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
    `maven-publish`
}

android {
    namespace = "io.twostars.sdk"
    compileSdk = 34

    defaultConfig {
        minSdk = 24
        consumerProguardFiles("consumer-rules.pro")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
        // Coroutines + serialization need this off for some APIs.
        freeCompilerArgs = freeCompilerArgs + "-Xjvm-default=all"
    }

    publishing {
        singleVariant("release") { withSourcesJar() }
    }
}

dependencies {
    // Async + JSON.
    api(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)
    api(libs.kotlinx.serialization.json)

    // Transport.
    api(libs.socketio.client)
    implementation(libs.okhttp)

    // WebRTC + Mediasoup stack. libmediasoup-android bundles the
    // native libmediasoupclient_so.so AND the org.webrtc.* classes
    // (statically linked against libwebrtc internally), so adding a
    // separate org.webrtc artifact would duplicate-class the build.
    // Exposed as `api` so consumers can reference org.webrtc.* /
    // org.mediasoup.droid.* / io.github.crow_misia.webrtc.* types
    // without re-declaring the deps themselves.
    api(libs.libwebrtc.ktx)
    api(libs.libmediasoup.android)

    // AndroidX core (consumers usually already pull these).
    implementation(libs.androidx.core.ktx)
    // E7 — ProcessLifecycleOwner so Room can observe the host app's
    // background/foreground transitions and react (visibility event,
    // optional pause-video-on-background).
    implementation(libs.androidx.lifecycle.process)

    // A8.2 — virtual background. MediaPipe Tasks Vision provides the
    // ImageSegmenter with the SelfieSegmentation model. The model file
    // (`selfie_segmenter.tflite`) ships with the artifact under
    // assets/, so consumers don't need to bundle it themselves.
    implementation(libs.mediapipe.tasks.vision)
    // A8.3 — auto-frame. ML Kit Face Detection picks the largest face
    // per frame; we crop+scale around it. ML Kit over MediaPipe here
    // because Play Services already ships ML Kit on most devices →
    // smaller download, faster cold start.
    implementation(libs.mlkit.face.detection)

    // Tests.
    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.espresso)
}

publishing {
    publications {
        register<MavenPublication>("release") {
            groupId = "io.twostars"
            artifactId = "sdk"
            // 0.5.0 — full A2 + A4 + A5 + A6 surface (media plane, E2E
            // messaging, screen share, AI in-call) shipped. Bump major
            // when the public API breaks; minor for new APIs; patch
            // for fixes only.
            //
            // NB: When published via JitPack (com.github.2stars-io:video-android),
            // the groupId + artifactId above are IGNORED — JitPack auto-derives
            // them from the GitHub repo path. The values stay here for direct
            // mavenLocal() consumers + future Maven Central publishing.
            version = "0.5.1"

            afterEvaluate {
                from(components["release"])
            }

            pom {
                name.set("2Stars Android SDK")
                description.set("Android client SDK for the 2Stars Video Platform.")
                url.set("https://2stars.io")
                licenses {
                    license {
                        name.set("MIT License")
                        url.set("https://opensource.org/licenses/MIT")
                    }
                }
            }
        }
    }
}
