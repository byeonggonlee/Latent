import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

ksp {
    // Room writes the schema of every version here; they are kept so that a migration can be tried against the real one.
    arg("room.schemaLocation", "$projectDir/schemas")
}

android {
    namespace = "com.byeonggon.latent"
    compileSdk = 37

    defaultConfig {
        // The package on the phones and on Play, the owner's choice of 2026-10-22 (the developer on Play is Latent
        // Image). The code keeps its namespace above, which no one sees.
        applicationId = "com.latentimage.latent"
        minSdk = 35
        targetSdk = 37
        // The nightly and stable builds on GitHub pass the number of commits and a name with the date (see docs/RELEASES.md);
        // a build on this computer is version 1 of the base name, as it always was.
        versionCode = (project.findProperty("latentVersionCode") as String?)?.toInt() ?: 1
        versionName = (project.findProperty("latentVersionName") as String?) ?: (project.findProperty("latent.versionBase") as String)
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // Every build carries the native code (LiteRT's) of 64-bit ARM only, the kind of every phone the app is for:
        // the owner's choice, 2026-10-21. An emulator of another kind takes -PlatentAbis=x86_64.
        val abis = (project.findProperty("latentAbis") as String?) ?: "arm64-v8a"
        ndk { abiFilters += abis.split(',').map(String::trim) }
        // The library's folder in DCIM. The debug build keeps its own (below), so that it never moves or deletes the
        // photos of the installed app.
        buildConfigField("String", "LIBRARY_FOLDER", "\"Latent\"")
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
        // The debug build is another app beside the nightly or the one from Play, "Latent Debug": it installs next to
        // them although its key differs, keeps its own database, and its tests and `run-as` leave theirs alone.
        debug {
            applicationIdSuffix = ".debug"
            buildConfigField("String", "LIBRARY_FOLDER", "\"Latent Debug\"")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = when {
                keystoreProperties.isNotEmpty() -> signingConfigs.getByName("release")
                // For trying the release build on a phone without the nightly or Play's (their key differs, so it
                // cannot install over them). Play refuses a package signed like this, which is why it is not the default.
                project.hasProperty("debugSignedRelease") -> signingConfigs.getByName("debug")
                else -> null
            }
        }
    }

    // The app bundle for Play keeps every language in the one download: the language is chosen in the app (Settings →
    // Language, the phone's per-app list), and a split would leave a phone in Korean without the Japanese strings it
    // was switched to.
    bundle {
        language { enableSplit = false }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        // Also as text, which CI prints into its log.
        textReport = true
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }

    // The committed schemas, for the migration tests to build a database from: MigrationTestHelper reads them from the
    // assets of the variant under test, and a unit test's own assets are not among those. Debug builds only.
    sourceSets.getByName("debug").assets.srcDir("$projectDir/schemas")
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            all {
                // Raw development works on whole 24-megapixel frames.
                it.maxHeapSize = "2g"
                // A failure says what it was in the log (the CI's, too), not only where.
                it.testLogging {
                    events("failed")
                    exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
                    showCauses = true
                }
                // CI's shaders, written by a test for glslang: an output of the task, so that a result taken from the
                // build cache, as after a change to the docs only, brings them back too.
                System.getenv("SHADER_EXPORT_DIR")?.let { dir -> it.outputs.dir(dir) }
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
    // Expert RAW DNGs, compressed with JPEG XL.
    implementation(project(":jxlatte"))

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    // Builds a database from a committed schema (app/schemas), to test the migration from it.
    testImplementation(libs.androidx.room.testing)
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)
    debugImplementation(libs.compose.ui.test.manifest)

    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.junit)
}
