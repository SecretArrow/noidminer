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
        versionCode = 3
        versionName = "1.2.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables.useSupportLibrary = true

        ndk {
            // Default: bangun semua ABI yang didukung (arm64-v8a, armeabi-v7a,
            // x86_64, x86) supaya release split-per-ABI otomatis. E2E workflow
            // override ke x86_64 saja via property:
            //   ./gradlew ... -Pnoid.abis=x86_64
            val abis = providers.gradleProperty("noid.abis").orNull
                ?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }
                ?: listOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86")
            abiFilters += abis
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
            // Keystore can arrive as a decoded file path (NOID_KEYSTORE_FILE)
            // or as raw base64 (NOID_KEYSTORE_BASE64). Accept both so a
            // workflow wiring mistake can never silently produce an
            // unsigned APK again (the v1.0.0 install bug).
            val ksFile = (System.getenv("NOID_KEYSTORE_FILE")
                ?: providers.gradleProperty("NOID_KEYSTORE_FILE").orNull)?.takeIf { it.isNotBlank() }
            val ksB64 = (System.getenv("NOID_KEYSTORE_BASE64")
                ?: providers.gradleProperty("NOID_KEYSTORE_BASE64").orNull)?.takeIf { it.isNotBlank() }
            val ksPass = (System.getenv("NOID_KEYSTORE_PASSWORD")
                ?: providers.gradleProperty("NOID_KEYSTORE_PASSWORD").orNull)?.takeIf { it.isNotBlank() }
            val alias = (System.getenv("NOID_KEY_ALIAS")
                ?: providers.gradleProperty("NOID_KEY_ALIAS").orNull)?.takeIf { it.isNotBlank() }
            val keyPass = (System.getenv("NOID_KEY_PASSWORD")
                ?: providers.gradleProperty("NOID_KEY_PASSWORD").orNull)?.takeIf { it.isNotBlank() }
            val store: File? = when {
                ksFile != null -> File(ksFile)
                ksB64 != null -> File.createTempFile("noidminer", ".jks").apply {
                    writeBytes(Base64.getDecoder().decode(ksB64))
                    deleteOnExit()
                }
                else -> null
            }
            if (store != null && ksPass != null && alias != null && keyPass != null) {
                require(store.length() > 0) { "keystore file is empty" }
                storeFile = store
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
            // Sign with the CI-injected keystore. If the keystore is absent
            // the build fails loudly in the release workflow (hard gate),
            // never shipping an unsigned APK.
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

    splits {
        abi {
            isEnable = true
            isUniversalApk = false
            reset()
            // arm64-v8a  = hampir semua HP modern (2016+)
            // armeabi-v7a = HP lawas 32-bit (fallback PMULL tidak ada, software path)
            // x86_64      = emulator & tablet Intel/AMD
            // x86         = emulator lawas (jarang dipakai, tapi kecil biaya)
            include("arm64-v8a", "armeabi-v7a", "x86_64", "x86")
            isEnable = true
        }
    }

    packaging {
        resources.excludes += setOf(
            "/META-INF/{AL2.0,LGPL2.1}",
            "/META-INF/DEPENDENCIES",
            "/META-INF/LICENSE*",
            "/META-INF/NOTICE*",
        )
        // 16 KB page-size alignment for Android 15+ — dijamin sama untuk Rust
        // (linker flag) dan CMake (cppFlags).
        jniLibs {
            useLegacyPackaging = false
        }
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
        unitTests.isIncludeAndroidResources = true
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
    implementation(libs.androidx.work.runtime.ktx)
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
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.work.testing)
    testImplementation(libs.mockk)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.espresso.core)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
    debugImplementation(libs.compose.ui.test.manifest)
}
