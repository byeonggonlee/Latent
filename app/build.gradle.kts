import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.byeonggon.latent"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.byeonggon.latent"
        minSdk = 35
        targetSdk = 37
        // The nightly and stable builds on GitHub pass the number of commits and a name with the date (see docs/RELEASES.md);
        // a build on this computer is version 1 of the base name, as it always was.
        versionCode = (project.findProperty("latentVersionCode") as String?)?.toInt() ?: 1
        versionName = (project.findProperty("latentVersionName") as String?) ?: (project.findProperty("latent.versionBase") as String)
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // The builds for the phones of today carry the code of 64-bit ARM only (-PlatentAbis=arm64-v8a), which is a third
        // smaller; any other build, an emulator's say, carries all the kinds.
        (project.findProperty("latentAbis") as String?)?.let { abis -> ndk { abiFilters += abis.split(',').map(String::trim) } }
    }

    // The upload key stays out of the repository: keystore.properties (storeFile, storePassword,
    // keyAlias, keyPassword) next to this project's settings.gradle.kts turns signing on.
    val keystoreProperties = Properties().apply {
        rootProject.file("keystore.properties").takeIf { it.isFile }?.inputStream()?.use(::load)
    }
    signingConfigs {
        if (keystoreProperties.isNotEmpty()) {
            create("release") {
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = when {
                keystoreProperties.isNotEmpty() -> signingConfigs.getByName("release")
                // For trying the release build on a phone that has the debug build: same key, so it installs over it.
                // Play refuses a package signed like this, which is why it is not the default.
                project.hasProperty("debugSignedRelease") -> signingConfigs.getByName("debug")
                else -> null
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            all {
                // Raw development works on whole 24-megapixel frames.
                it.maxHeapSize = "2g"
            }
        }
    }
}

configurations.configureEach {
    // LiteRT can fetch models as Play AI packs, which Latent does not use; that library would bring
    // Google Play services along, and with them code that talks to Google.
    exclude(group = "com.google.android.play", module = "ai-delivery")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.exifinterface)
    implementation(libs.androidx.room.runtime)
    ksp(libs.androidx.room.compiler)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.core)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.coil.compose)
    implementation(libs.coil.video)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.ui)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.gson)
    // Subject masks, worked out on the phone by a model in the app's assets: on the graphics processor, or the cores where it cannot.
    implementation(libs.litert)
    implementation(libs.litert.gpu)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)
    debugImplementation(libs.compose.ui.test.manifest)

    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.junit)
}
