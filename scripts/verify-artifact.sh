#!/usr/bin/env bash
set -euo pipefail

PROJECT_ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
APK_PATH="${1:-$PROJECT_ROOT/app/build/outputs/apk/debug/app-debug.apk}"
ANDROID_SDK_ROOT="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-/Users/jin/Library/Android/sdk}}"
AAPT_BIN="$ANDROID_SDK_ROOT/build-tools/36.0.0/aapt"
APKSIGNER_BIN="$ANDROID_SDK_ROOT/build-tools/36.0.0/apksigner"
export ANDROID_HOME="$ANDROID_SDK_ROOT"
export ANDROID_SDK_ROOT
export JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home}"
export PATH="$ANDROID_SDK_ROOT/platform-tools:$ANDROID_SDK_ROOT/build-tools/36.0.0:$JAVA_HOME/bin:$PATH"

die() {
    printf 'verify-artifact: %s\n' "$*" >&2
    exit 1
}

[[ -x "$PROJECT_ROOT/gradlew" ]] || die "Gradle wrapper is missing"
[[ -x "$AAPT_BIN" ]] || die "aapt was not found at $AAPT_BIN"
[[ -x "$APKSIGNER_BIN" ]] || die "apksigner was not found at $APKSIGNER_BIN"

cd "$PROJECT_ROOT"
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug

[[ -f "$APK_PATH" ]] || die "APK does not exist: $APK_PATH"
badging="$($AAPT_BIN dump badging "$APK_PATH")"
printf '%s\n' "$badging" | rg -q "^package: name='io.github.uuremotedark.debug' versionCode='1'" \
    || die "debug APK manifest package/version does not match"

module_prop="$(unzip -p "$APK_PATH" META-INF/xposed/module.prop)"
scope="$(unzip -p "$APK_PATH" META-INF/xposed/scope.list)"
java_init="$(unzip -p "$APK_PATH" META-INF/xposed/java_init.list)"
printf '%s\n' "$module_prop" | rg -q '^minApiVersion=101$' || die "module min API is not 101"
printf '%s\n' "$module_prop" | rg -q '^targetApiVersion=101$' || die "module target API is not 101"
printf '%s\n' "$module_prop" | rg -q '^staticScope=true$' || die "module is not static-scope"
[[ "$scope" == 'com.netease.uuremote' ]] || die "scope.list is not UU-only"
[[ "$java_init" == 'io.github.uuremotedark.hook.ModuleEntry' ]] \
    || die "java_init.list does not point to ModuleEntry"

"$APKSIGNER_BIN" verify --verbose "$APK_PATH" >/dev/null \
    || die "APK signature verification failed"

sha256="$(shasum -a 256 "$APK_PATH" | awk '{print $1}')"
size="$(wc -c < "$APK_PATH" | tr -d ' ')"
printf 'artifact=%s\nsha256=%s\nsizeBytes=%s\n' "$APK_PATH" "$sha256" "$size"
