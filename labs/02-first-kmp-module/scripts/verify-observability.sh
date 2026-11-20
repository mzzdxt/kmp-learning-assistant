#!/bin/sh

set -eu

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
PROJECT_ROOT=$(CDPATH= cd -- "$SCRIPT_DIR/.." && pwd)

if [ -z "${GRADLE_USER_HOME:-}" ]; then
    GRADLE_USER_HOME="$PROJECT_ROOT/../.gradle-user-home"
    export GRADLE_USER_HOME
fi

fail() {
    echo "Observability gate failed: $1" >&2
    exit 1
}

cd "$PROJECT_ROOT"

./gradlew \
    :domain:jvmTest \
    :shared:jvmTest \
    :shared:iosX64Test \
    :shared:linkDebugFrameworkIosX64 \
    :androidApp:assembleRelease \
    --configuration-cache-problems=fail \
    --console=plain

MAPPING="$PROJECT_ROOT/androidApp/build/outputs/mapping/release/mapping.txt"
RELEASE_APK="$PROJECT_ROOT/androidApp/build/outputs/apk/release/androidApp-release-unsigned.apk"
ANDROID_BUILD="$PROJECT_ROOT/androidApp/build.gradle.kts"
DIAGNOSTICS_SOURCE="$PROJECT_ROOT/shared/src/commonMain/kotlin/dev/kmpjourney/pocketlab/diagnostics/RefreshObservability.kt"

[ -s "$RELEASE_APK" ] || fail 'minified Android Release APK is missing'
[ -s "$MAPPING" ] || fail 'R8 mapping.txt is missing'
rg -q 'versionCode = 16' "$ANDROID_BUILD" || \
    fail 'Android Release build number is not 16'
rg -q 'versionName = "0\.16\.0"' "$ANDROID_BUILD" || \
    fail 'Android Release version is not 0.16.0'
rg -q '^# compiler_version: 9\.1\.29$' "$MAPPING" || \
    fail 'Android Release did not use the Kotlin 2.4-compatible R8 version'
rg -q 'ExperimentListViewModel ->' "$MAPPING" || \
    fail 'Android application classes are absent from mapping.txt'
rg -q 'fileName":"RefreshExperimentsUseCase\.kt"' "$MAPPING" || \
    fail 'domain source/line metadata is absent from mapping.txt'
rg -q 'fileName":"RefreshObservability\.kt"' "$MAPPING" || \
    fail 'shared diagnostics source/line metadata is absent from mapping.txt'

rg -q 'TimeSource\.Monotonic' "$DIAGNOSTICS_SOURCE" || \
    fail 'refresh duration is not measured with a monotonic clock'
rg -q 'event=pocketlab\.refresh' "$DIAGNOSTICS_SOURCE" || \
    fail 'the stable refresh event schema is missing'
if rg -q \
    'failure\.message|experiment\.(title|hypothesis|notes)|baseUrl|databaseName|Authorization' \
    "$DIAGNOSTICS_SOURCE"; then
    fail 'a sensitive or unbounded value entered the diagnostic schema'
fi

APK_SHA256=$(shasum -a 256 "$RELEASE_APK" | awk '{ print $1 }')
MAPPING_SHA256=$(shasum -a 256 "$MAPPING" | awk '{ print $1 }')
PG_MAP_ID=$(awk '/^# pg_map_id:/ { print $3; exit }' "$MAPPING")
[ -n "$PG_MAP_ID" ] || fail 'R8 mapping identity is missing'

NATIVE_BINARY="$PROJECT_ROOT/shared/build/bin/iosX64/debugTest/test.kexe"
NATIVE_DSYM="$NATIVE_BINARY.dSYM"
NATIVE_DWARF="$NATIVE_DSYM/Contents/Resources/DWARF/test.kexe"

[ -x "$NATIVE_BINARY" ] || fail 'Kotlin/Native test executable is missing'
[ -d "$NATIVE_DSYM" ] || fail 'Kotlin/Native dSYM is missing'

BINARY_UUID=$(dwarfdump --uuid "$NATIVE_BINARY" | awk 'NR == 1 { print $2 }')
DSYM_UUID=$(dwarfdump --uuid "$NATIVE_DSYM" | awk 'NR == 1 { print $2 }')
[ -n "$BINARY_UUID" ] || fail 'Kotlin/Native binary UUID is empty'
[ "$BINARY_UUID" = "$DSYM_UUID" ] || \
    fail 'Kotlin/Native binary and dSYM UUIDs do not match'

SYMBOL_ADDRESS=$(nm -nm "$NATIVE_BINARY" | \
    awk '/StructuredRefreshDiagnosticsTest#appliedRefreshUsesOneCorrelationIdAndMonotonicDuration/ { print $1; exit }')
[ -n "$SYMBOL_ADDRESS" ] || fail 'diagnostic test symbol is absent from Native binary'
atos -arch x86_64 -o "$NATIVE_DWARF" "$SYMBOL_ADDRESS" | \
    rg -q 'StructuredRefreshDiagnosticsTest#appliedRefreshUsesOneCorrelationIdAndMonotonicDuration' || \
    fail 'atos could not restore the Kotlin function name from the dSYM'

XCODE_PROJECT="$PROJECT_ROOT/iosApp/PocketLabIos.xcodeproj/project.pbxproj"
XCODE_GENERATOR="$PROJECT_ROOT/iosApp/generate_xcode_project.rb"
rg -q 'DEBUG_INFORMATION_FORMAT = "dwarf-with-dsym"' "$XCODE_PROJECT" || \
    fail 'the iOS App Release configuration does not request a dSYM'
rg -q '"dwarf-with-dsym"' "$XCODE_GENERATOR" || \
    fail 'the Xcode project generator does not preserve the Release dSYM setting'

APPLE_HEADER="$PROJECT_ROOT/shared/build/bin/iosX64/debugFramework/PocketLabShared.framework/Headers/PocketLabShared.h"
[ -s "$APPLE_HEADER" ] || fail 'Apple framework header is missing'
if rg -q \
    'RefreshDiagnostics|RefreshDiagnosticRecord|StructuredRefreshDiagnostics|createObservedRefreshExperimentsUseCase' \
    "$APPLE_HEADER"; then
    fail 'internal observability implementation leaked into the Apple API'
fi

echo "Observability contract passed."
echo "Structured refresh schema: privacy allowlist + monotonic duration"
echo "Android Release: 0.16.0 (16), R8 9.1.29, pg_map_id=$PG_MAP_ID"
echo "Android APK SHA-256: $APK_SHA256"
echo "Android mapping SHA-256: $MAPPING_SHA256"
echo "Kotlin/Native UUID: $BINARY_UUID"
echo "Kotlin/Native symbolication: atos restored the diagnostic test function"
echo "Apple App Release dSYM setting: configured"
