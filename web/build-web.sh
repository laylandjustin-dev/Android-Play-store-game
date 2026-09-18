#!/usr/bin/env bash
# Compiles :sim to JavaScript, so the web build *is* the game rather than a port of it.
#
# Deliberately not the Kotlin/JS Gradle plugin: that pulls Node and Yarn through a toolchain
# download this environment cannot reach. The Kotlin compiler is already in the Gradle cache and
# the JS standard library is on Maven Central, so the compiler is invoked directly.
set -euo pipefail
cd "$(dirname "$0")/.."

OUT="${1:-build/web-game}"
# The Kotlin JS backend clears its output directory before writing, so the downloaded klibs and
# the intermediate klib must live outside it — the first attempt put them inside and the compiler
# deleted its own inputs mid-build.
WORK="$OUT/work"
JS="$OUT/js"
KOTLIN=$(grep -E '^kotlin *= *"' gradle/libs.versions.toml | sed -E 's/.*"(.*)".*/\1/')
SERIALIZATION=$(grep -E '^serialization *= *"' gradle/libs.versions.toml | sed -E 's/.*"(.*)".*/\1/')
LIBS="$WORK/libs"
mkdir -p "$LIBS" "$JS"

fetch() { # coordinate -> klib
  local path="$1" file="$LIBS/$(basename "$1")"
  # Maven Central answers 429 under load, and -f leaves a zero-byte file behind that the resolver
  # then reports as "could not find". Retry with backoff and never keep a failed download.
  if [ ! -s "$file" ]; then
    rm -f "$file"
    local delay=2 attempt=1
    until curl -sSf --max-time 120 -o "$file" "https://repo1.maven.org/maven2/$path"; do
      rm -f "$file"
      [ "$attempt" -lt 4 ] || { echo "failed to fetch $path" >&2; return 1; }
      sleep "$delay"; delay=$((delay * 2)); attempt=$((attempt + 1))
    done
  fi
  echo "$file"
}

STDLIB=$(fetch "org/jetbrains/kotlin/kotlin-stdlib-js/$KOTLIN/kotlin-stdlib-js-$KOTLIN.klib")
SER_CORE=$(fetch "org/jetbrains/kotlinx/kotlinx-serialization-core-js/$SERIALIZATION/kotlinx-serialization-core-js-$SERIALIZATION.klib")
SER_JSON=$(fetch "org/jetbrains/kotlinx/kotlinx-serialization-json-js/$SERIALIZATION/kotlinx-serialization-json-js-$SERIALIZATION.klib")

cache="${GRADLE_USER_HOME:-$HOME/.gradle}/caches/modules-2"
find_jar() { find "$cache" -name "$1" 2>/dev/null | head -1; }

COMPILER=$(find_jar "kotlin-compiler-embeddable-$KOTLIN.jar")
PLUGIN=$(find_jar "kotlin-serialization-compiler-plugin-embeddable-$KOTLIN.jar")
if [ -z "$COMPILER" ] || [ -z "$PLUGIN" ]; then
  echo "The Kotlin $KOTLIN compiler is not in the Gradle cache. Run a JVM build first:"
  echo "    ./gradlew -Ppixeltown.simOnly=true :sim:build"
  exit 1
fi
DEPS=$(find "$cache" -name "*.jar" \( \
  -name "kotlin-stdlib-$KOTLIN.jar" -o -name "kotlin-reflect-*.jar" -o \
  -name "kotlinx-coroutines-core-jvm-*.jar" -o -name "trove4j-*.jar" -o \
  -name "annotations-13.0.jar" -o -name "kotlin-daemon-embeddable-$KOTLIN.jar" \) | tr '\n' ':')

# SaveCompression.kt is the one JVM-only file in :sim (java.util.zip); the web build omits it.
SOURCES=$(find sim/src/main/kotlin web/src -name '*.kt' ! -name 'SaveCompression.kt' | tr '\n' ' ')

# K2 will not go from sources straight to JavaScript in one pass: it compiles sources to a klib,
# then compiles that klib to JavaScript.
echo "Compiling $(echo "$SOURCES" | wc -w) Kotlin files to a klib..."
java -cp "${COMPILER}:${DEPS}" org.jetbrains.kotlin.cli.js.K2JSCompiler \
  $SOURCES \
  -Xplugin="$PLUGIN" \
  -libraries "$STDLIB:$SER_CORE:$SER_JSON" \
  -Xir-produce-klib-file \
  -ir-output-dir "$WORK/klib" -ir-output-name pixeltown

echo "Generating JavaScript..."
java -cp "${COMPILER}:${DEPS}" org.jetbrains.kotlin.cli.js.K2JSCompiler \
  -Xinclude="$WORK/klib/pixeltown.klib" \
  -Xplugin="$PLUGIN" \
  -libraries "$STDLIB:$SER_CORE:$SER_JSON" \
  -Xir-produce-js \
  -module-kind umd \
  -ir-output-dir "$JS" -ir-output-name pixeltown

echo "Built:"
ls -lh "$JS"/*.js | awk '{print "  " $9 "  " $5}'
