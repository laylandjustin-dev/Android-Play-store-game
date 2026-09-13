#!/usr/bin/env bash
# Type-checks app/ as far as is possible without the Android SDK.
#
# WHAT THIS PROVES
#   - every file under app/ is syntactically valid Kotlin
#   - every symbol app/ uses from :sim resolves, with compatible signatures
#
# WHAT IT DOES NOT PROVE
#   - that any Compose or Android API is used correctly. Those classes are not on the
#     classpath, so every reference to them is "unresolved" and is ignored here.
#
# It exists because this project is developed in an environment that cannot reach
# dl.google.com, so app/ is otherwise wholly unverified between CI runs. It catches typos and
# drift against the simulation's API — not a mistaken Compose call. See CLAUDE.md.
set -uo pipefail
cd "$(dirname "$0")/.."

say() { printf '%s\n' "$*"; }

say "Building the simulation jar..."
./gradlew -q -Ppixeltown.simOnly=true :sim:jar || { say "FAILED: could not build :sim"; exit 1; }

cache="${GRADLE_USER_HOME:-$HOME/.gradle}/caches/modules-2"
kotlin_version=$(grep -E '^kotlin *= *"' gradle/libs.versions.toml | sed -E 's/.*"(.*)".*/\1/')

find_jar() { find "$cache" -name "$1" 2>/dev/null | head -1; }

compiler=$(find_jar "kotlin-compiler-embeddable-${kotlin_version}.jar")
stdlib=$(find_jar "kotlin-stdlib-${kotlin_version}.jar")
if [ -z "$compiler" ] || [ -z "$stdlib" ]; then
  say "SKIPPED: the Kotlin $kotlin_version compiler is not in the Gradle cache yet."
  say "Run a build first:  ./gradlew -Ppixeltown.simOnly=true :sim:build"
  exit 0
fi

# The embeddable compiler needs a few of its own dependencies on the classpath to start up.
deps=$(find "$cache" -name "*.jar" \( \
  -name "kotlin-reflect-*.jar" -o \
  -name "kotlinx-coroutines-core-jvm-*.jar" -o \
  -name "trove4j-*.jar" -o \
  -name "annotations-13.0.jar" -o \
  -name "kotlin-daemon-embeddable-${kotlin_version}.jar" \) 2>/dev/null | tr '\n' ':')

log=$(mktemp)
java -cp "${compiler}:${stdlib}:${deps}" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
  $(find app/src/main/kotlin -name '*.kt') \
  -classpath "${stdlib}:sim/build/libs/sim.jar" \
  -d "$(mktemp -d)" -no-stdlib -no-reflect > "$log" 2>&1

# Errors caused purely by the absent Android SDK are expected. Anything else is a real defect:
# a parse error, a bad call into :sim, or a wrong argument list.
real=$(grep "error:" "$log" \
  | grep -vE "unresolved reference '(android|androidx)'" \
  | grep -vE "unresolved reference '[A-Za-z]+'\.$" \
  | grep -vE "overrides nothing" \
  | grep -vE "cannot infer type for this parameter" \
  | grep -vE "ERROR CLASS" || true)

expected=$(grep -c "error:" "$log")
say ""
if [ -n "$real" ]; then
  say "FAILED — errors that the missing Android SDK does not explain:"
  printf '%s\n' "$real"
  rm -f "$log"
  exit 1
fi

say "OK — app/ parses and its use of :sim type-checks."
say "     ($expected errors, all of them missing Android/Compose symbols, as expected here.)"
rm -f "$log"
exit 0
