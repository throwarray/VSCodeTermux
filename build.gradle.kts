// Root build script: declares plugin *versions* (via gradle/libs.versions.toml,
// which Gradle auto-imports as `libs` — no explicit settings.gradle wiring
// needed for that part) without applying them here. Each module applies
// what it needs. This is the file that was missing before — app/build.gradle.kts
// referenced these plugin ids with no version anywhere for Gradle to resolve
// against, which is the same "no toml" failure mode as the RN/Expo case.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
}
