plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

// Compiled to Java 17 bytecode so the Android app can consume it, while building on whatever
// JDK (17+) the developer or CI runner happens to have.
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlin.test)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
    testLogging { events("passed", "skipped", "failed") }
}

// The simulation must stay pure Kotlin: fail the build if an Android import sneaks in.
val checkNoAndroidImports by tasks.registering {
    group = "verification"
    description = "Asserts that :sim contains no Android imports."
    val sources = fileTree("src/main/kotlin") { include("**/*.kt") }
    inputs.files(sources)
    outputs.upToDateWhen { false }
    doLast {
        val offenders = sources.files.filter { f ->
            f.readLines().any { it.trimStart().startsWith("import android") }
        }
        if (offenders.isNotEmpty()) {
            throw GradleException("Android imports found in :sim: ${offenders.joinToString { it.name }}")
        }
    }
}

tasks.named("check") { dependsOn(checkNoAndroidImports) }
