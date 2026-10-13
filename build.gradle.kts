plugins {
    alias(libs.plugins.android.application) apply false
    // Never applied: AGP's built-in Kotlin support compiles the code, this only pins the Kotlin version it uses.
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
}
