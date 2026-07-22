// Top-level (root) build script.
// It does NOT build anything itself — it only DECLARES which plugins the
// project may use. `apply false` means: make the plugin available to modules,
// but don't apply it here at the root. The :app module applies what it needs.
// Versions live in gradle/libs.versions.toml (the `libs.plugins.*` aliases).

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
}
