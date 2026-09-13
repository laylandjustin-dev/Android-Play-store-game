#!/usr/bin/env bash
# Checks that this machine can build :app, and says exactly what is missing if it cannot.
#
# The simulation (:sim) builds anywhere with a JDK. The Android app additionally needs the
# Android SDK and network access to Google's Maven repository (dl.google.com).
set -uo pipefail

fail=0
say()  { printf '%s\n' "$*"; }
ok()   { printf '  ok    %s\n' "$*"; }
bad()  { printf '  MISSING %s\n' "$*"; fail=1; }

say "Pixel Town — Android build environment check"
say ""

say "Java:"
if command -v java >/dev/null 2>&1; then
  # Match the version line itself: JAVA_TOOL_OPTIONS prints a banner above it on some setups.
  version=$(java -version 2>&1 | grep -E '(openjdk|java) version' | head -1)
  major=$(printf '%s' "$version" | grep -oE '"[0-9]+' | tr -d '"')
  if [ "${major:-0}" -ge 17 ]; then
    ok "${version:-java found}"
  else
    bad "JDK 17 or newer (found: ${version:-unknown})"
  fi
else
  bad "a JDK (17 or newer)"
fi

say "Android SDK:"
sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
if [ -z "$sdk" ] && [ -f local.properties ]; then
  sdk=$(grep -E '^sdk\.dir=' local.properties | cut -d= -f2- || true)
fi
if [ -n "$sdk" ] && [ -d "$sdk" ]; then
  ok "SDK at $sdk"
  [ -d "$sdk/platforms/android-36" ] && ok "platforms;android-36" || bad "platforms;android-36  (sdkmanager 'platforms;android-36')"
  compgen -G "$sdk/build-tools/36*" >/dev/null && ok "build-tools 36.x" || bad "build-tools;36.0.0  (sdkmanager 'build-tools;36.0.0')"
else
  bad "the Android SDK — set ANDROID_HOME, or put sdk.dir=/path/to/sdk in local.properties"
fi

say "Network (Google's Maven, where the Android Gradle Plugin lives):"
if curl -sS -o /dev/null --max-time 15 "https://dl.google.com/dl/android/maven2/com/android/application/com.android.application.gradle.plugin/maven-metadata.xml" 2>/dev/null; then
  ok "dl.google.com reachable"
else
  bad "dl.google.com is unreachable — :app cannot resolve the Android Gradle Plugin here"
fi

say ""
if [ "$fail" -eq 0 ]; then
  say "Ready. Build with:  ./gradlew :app:assembleDebug"
else
  say "Not ready for :app. The simulation still builds and tests anywhere:"
  say "    ./gradlew -Ppixeltown.simOnly=true :sim:build"
fi
exit "$fail"
