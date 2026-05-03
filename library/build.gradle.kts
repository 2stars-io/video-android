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

    // AndroidX core (consumers usually already pull these).
    implementation(libs.androidx.core.ktx)

    // Tests.
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.espresso)
}

publishing {
    publications {
        register<MavenPublication>("release") {
            groupId = "io.twostars"
            artifactId = "sdk"
            version = "0.1.0"

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
