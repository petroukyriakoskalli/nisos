plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "app.nisos"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.nisos"
        // API 26 is where java.time lives without desugaring, and everything
        // below that is a phone from 2017. Nothing here needs to run on one.
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.5.0"
    }

    buildTypes {
        debug {
            // The debug APK is the deliverable: this is sideloaded from a CI
            // artifact, not shipped through a store, so it is never minified
            // and never signed with anything but the debug key.
            isMinifyEnabled = false
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

    testImplementation(libs.junit)
    testImplementation(libs.json)
}
