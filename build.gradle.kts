plugins {
    // Modules apply the rest via build-logic convention plugins
    // (mubox.android.library, mubox.android.compose, mubox.jvm.library).
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.ksp) apply false
}
