// Plugins are declared per-module, not here: the root project must configure without touching
// Google's Maven repository so that `:sim` stays buildable and testable on any JVM-only machine
// (CI runners, this sandbox) where the Android Gradle Plugin cannot be resolved.
