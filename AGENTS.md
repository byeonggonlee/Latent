# Repository Guidelines

## Project Structure & Module Organization

Latent is a Kotlin/Compose Android app for USB imports, editing, and geotagging.

- `app/src/main/java/com/byeonggon/latent/`: application code; `camera/` handles MTP sync, `data/` storage and Room, `raw/` decoding, `enhance/` image processing, and `ui/` screens and view models.
- `app/src/main/res/`: English, Korean, and Japanese resources. `app/src/main/assets/`: subject-mask model and licenses.
- `app/src/test/`: JVM tests; `app/src/androidTest/`: device tests; `app/schemas/`: committed Room schemas.
- `third_party/jxlatte/`: vendored Java JPEG XL decoder, built as `:jxlatte`.
- `docs/`: releases, backup format, roadmap, and device checklists. `store/`: store graphics.

## Build, Test, and Development Commands

Run the Gradle wrapper from the repository root. CI uses JDK 21; compile SDK is 37; devices require Android 15/API 35 or newer.

- `./gradlew assembleDebug`: build the debug APK.
- `./gradlew installDebug`: install on a device or emulator; launch Latent there.
- `./gradlew testDebugUnitTest`: run JVM tests.
- `./gradlew lintDebug lintRelease assembleDebugAndroidTest`: run Android Lint and compile instrumentation tests, matching CI checks.
- `git diff --exit-code -- app/schemas`: verify committed schemas after building.
- `./gradlew assembleRelease -PdebugSignedRelease`: build a minified release APK signed with the local debug key.

## Coding Style & Naming Conventions

Follow official Kotlin style (`kotlin.code.style=official`), four-space indentation, and surrounding code. Use PascalCase for classes and composables, camelCase for functions and properties, and lowercase underscore names for Android resources. Keep feature code in its existing package. Android Lint is configured; no standalone formatter is configured.

## Testing Guidelines

Tests use JUnit 4, Robolectric, and Compose UI testing. Name classes `*Test` or `*OnDeviceTest`, with descriptive camelCase test methods. Add regression coverage for changed behavior; no numeric coverage threshold is configured. Room changes require a version bump, migration, and committed schema. Consult `docs/DEVICE_CHECKS.md` for GPU, USB, and real-photo checks. Run device tests through `adb shell am instrument`; see README for sample arguments. Avoid `connectedAndroidTest` on personal installations: its cleanup uninstalls the app and removes its database.

## Commit & Pull Request Guidelines

History uses descriptive imperative subjects such as “Compile the tests that run on a phone in CI”; follow that style. Keep commits focused. PRs should explain the problem, resulting behavior, and checks performed; link relevant issues and include screenshots for UI changes. State which device checks remain unverified.

## Privacy & Configuration

Keep photos and location data on-device; online maps require explicit opt-in. Never commit signing keys, `keystore.properties`, or `local.properties`. Preserve original photos and migration paths when changing storage.
