plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

/**
 * A signing key that does not change between builds.
 *
 * Without this, every CI build was signed by a *different* key. Runners are
 * ephemeral and carry no `~/.android/debug.keystore`, so the Android Gradle
 * Plugin generated a fresh one on demand every run -- same alias and password,
 * new key pair -- and Android then refuses each build as an update to the last.
 * That is not a theory: the APKs from run 10 and run 12 were checked and their
 * signing keys differ, which is why the phone would not take the update.
 *
 * Restored from a repository secret rather than committed. A signing key in a
 * public repository would let anyone build an APK that Android accepts as an
 * update to yours, silently, which matters rather more now the app holds an API
 * key and can send messages.
 *
 * **Absent, the build still works.** A fork or a clean clone has no keystore
 * here and falls through to the plugin's own generated debug key -- it simply
 * cannot update an install signed by this one.
 */
val sharedKeystore = rootProject.file("nisos.jks")

android {
    namespace = "app.nisos"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.nisos"
        // API 26 is where java.time lives without desugaring, and everything
        // below that is a phone from 2017. Nothing here needs to run on one.
        minSdk = 26
        targetSdk = 35
        // Taken from the CI run number so every build is a distinct version.
        // Android will happily reinstall over an equal versionCode, but an
        // updater watching for a *newer* one needs the number to actually
        // move -- and "which build is on the phone" should be answerable.
        versionCode = (System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 0) + 10
        versionName = "0.7.0"
    }

    // Declared before buildTypes, which refers to it below.
    val shared = if (sharedKeystore.exists()) {
        signingConfigs.create("shared") {
            storeFile = sharedKeystore
            // The standard debug credentials. The secret being protected is the
            // keystore *file*; a password here would be a second factor that
            // only matters once the file has already leaked, and paying for it
            // with a second repository secret is not worth it at this tier.
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    } else {
        null
    }

    buildTypes {
        debug {
            // The debug APK is the deliverable: this is sideloaded from a
            // release asset, not shipped through a store, so it is never
            // minified and never signed with a release key.
            isMinifyEnabled = false
            // But it does need to be signed with the *same* key every time, or
            // "install over the top" is a claim rather than a fact.
            if (shared != null) signingConfig = shared
        }
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }

    testOptions {
        unitTests {
            // Everything in core/ is deliberately free of Android imports, so
            // the tests that matter run on a plain JVM in about a second. The
            // few Android classes that do get touched return defaults rather
            // than throwing, which keeps a stray reference from failing a test
            // about the router.
            isReturnDefaultValues = true

            all {
                // Say which test failed and why, in the log.
                //
                // Gradle's default is to print "18 failed" and a path to an
                // HTML report -- which on a CI runner is inside an artifact
                // you have to download, unzip and parse before you know
                // whether the problem is one bug or eighteen. It was one.
                it.testLogging {
                    events("failed")
                    exceptionFormat =
                        org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
                    showStackTraces = false
                }
            }
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    val composeBom = platform(libs.androidx.compose.bom)
    implementation(composeBom)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.biometric)
    // Must come with biometric: it ships fragment 1.2.5, which crashes on every
    // Activity Result launcher call. See the note in libs.versions.toml.
    implementation(libs.androidx.fragment)

    testImplementation(libs.junit)
    testImplementation(libs.json)
}
