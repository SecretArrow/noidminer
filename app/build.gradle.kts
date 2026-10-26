import java.util.Base64

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "org.secretarrow.noidminer"
    compileSdk = 35

    defaultConfig {
        applicationId = "org.secretarrow.noidminer"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables.useSupportLibrary = true

        ndk {
            // arm64-v8a covers virtually all modern Android hardware.
            // Other ABIs fall back to the pure-Kotlin Poseidon2b engine.
            abiFilters += listOf("arm64-v8a")
        }

        externalNativeBuild {
            cmake {
                cppFlags += listOf("-std=c++17", "-O2", "-fvisibility=hidden", "-fexceptions")
                arguments += listOf("-DANDROID_STL=c++_static")
            }
        }
    }

    signingConfigs {
        create("release") {
            val ksB64 = (System.getenv("NOID_KEYSTORE_BASE64")
                ?: providers.gradleProperty("NOID_KEYSTORE_BASE64").orNull)?.takeIf { it.isNotBlank() }
            val ksPass = (System.getenv("NOID_KEYSTORE_PASSWORD")
                ?: providers.gradleProperty("NOID_KEYSTORE_PASSWORD").orNull)?.takeIf { it.isNotBlank() }
            val alias = (System.getenv("NOID_KEY_ALIAS")
                ?: providers.gradleProperty("NOID_KEY_ALIAS").orNull)?.takeIf { it.isNotBlank() }
            val keyPass = (System.getenv("NOID_KEY_PASSWORD")
                ?: providers.gradleProperty("NOID_KEY_PASSWORD").orNull)?.takeIf { it.isNotBlank() }
            if (ksB64 != null && ksPass != null && alias != null && keyPass != null) {
                val tmp = File.createTempFile("noidminer", ".jks").apply {
                    writeBytes(Base64.getDecoder().decode(ksB64))
                    deleteOnExit()
                }
                storeFile = tmp
                storePassword = ksPass
                keyAlias = alias
                keyPassword = keyPass
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // Sign with the CI-injected keystore when present; otherwise the
            // release workflow produces a debug-signed artifact (installable).
            if (signingConfigs.getByName("release").storeFile != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    ndkVersion = "26.3.11579264"

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    packaging {
        resources.excludes += setOf(
            "/META-INF/{AL2.0,LGPL2.1}",
            "/META-INF/DEPENDENCIES",
            "/META-INF/LICENSE*",
            "/META-INF/NOTICE*",
        )
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.security.crypto)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.coroutines.android)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    debugImplementation(libs.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.org.json)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.espresso.core)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
    debugImplementation(libs.compose.ui.test.manifest)
}
