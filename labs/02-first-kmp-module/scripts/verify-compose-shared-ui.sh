#!/bin/sh

set -eu

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
PROJECT_ROOT=$(CDPATH= cd -- "$SCRIPT_DIR/.." && pwd)
ROOT_BUILD="$PROJECT_ROOT/build.gradle.kts"
SHARED_UI_BUILD="$PROJECT_ROOT/sharedUi/build.gradle.kts"
COMMON_SOURCE="$PROJECT_ROOT/sharedUi/src/commonMain"
ANDROID_TEST_RESULT="$PROJECT_ROOT/sharedUi/build/test-results/testAndroidHostTest/TEST-dev.kmpjourney.pocketlab.sharedui.ExperimentSummaryStatusTest.xml"
AAR="$PROJECT_ROOT/sharedUi/build/outputs/aar/sharedUi.aar"
DEVICE_FRAMEWORK="$PROJECT_ROOT/sharedUi/build/bin/iosArm64/releaseFramework/PocketLabSharedUi.framework"
SIMULATOR_FRAMEWORK="$PROJECT_ROOT/sharedUi/build/bin/iosSimulatorArm64/releaseFramework/PocketLabSharedUi.framework"
HEADER="$DEVICE_FRAMEWORK/Headers/PocketLabSharedUi.h"
SWIFT_SMOKE="$PROJECT_ROOT/scripts/compose-shared-ui-smoke.swift"

if [ -z "${GRADLE_USER_HOME:-}" ]; then
    GRADLE_USER_HOME="$PROJECT_ROOT/../.gradle-user-home"
    export GRADLE_USER_HOME
fi

fail() {
    echo "Compose shared UI gate failed: $1" >&2
    exit 1
}

cd "$PROJECT_ROOT"

rg -q 'id\("org\.jetbrains\.compose"\) version "1\.11\.1"' "$ROOT_BUILD" || \
    fail "the spike is not pinned to the validated Compose Multiplatform 1.11.1 baseline"
rg -q 'compileSdk = 36' "$SHARED_UI_BUILD" || \
    fail "the spike no longer proves coexistence with the current compileSdk 36 baseline"
if rg -n 'iosX64' "$SHARED_UI_BUILD"; then
    fail "Compose Multiplatform 1.11.1 no longer publishes Apple x86_64 artifacts"
fi
if rg -n 'material3' "$SHARED_UI_BUILD"; then
    fail "the bounded spike must not silently add the 1.11.x alpha Material 3 dependency"
fi

./gradlew \
    :sharedUi:allTests \
    :sharedUi:bundleAndroidMainAar \
    :sharedUi:linkReleaseFrameworkIosArm64 \
    :sharedUi:linkReleaseFrameworkIosSimulatorArm64 \
    --max-workers=1 \
    --configuration-cache-problems=fail \
    --console=plain

[ -f "$ANDROID_TEST_RESULT" ] || fail "the Android host test report was not generated"
rg -q 'tests="2" skipped="0" failures="0" errors="0"' "$ANDROID_TEST_RESULT" || \
    fail "the two shared UI contract tests did not pass"
[ -f "$AAR" ] || fail "the Android AAR was not generated"
[ -f "$DEVICE_FRAMEWORK/PocketLabSharedUi" ] || fail "the iOS device Release framework was not generated"
[ -f "$SIMULATOR_FRAMEWORK/PocketLabSharedUi" ] || fail "the arm64 Simulator Release framework was not generated"
[ -f "$HEADER" ] || fail "the iOS framework header was not generated"

rg -q 'swift_name\("PocketLabSharedUi"\)' "$HEADER" || \
    fail "the Swift shared UI factory is missing"
rg -Fq 'experimentSummaryViewController(title:hypothesis:status:onOpen:)' "$HEADER" || \
    fail "the UIViewController factory is missing from the Apple API"
if rg -n 'ExperimentSummaryUiModel|ExperimentSummaryStatus' "$HEADER"; then
    fail "internal shared presentation models leaked into the Apple API"
fi

if rg -n \
    '^import (android\.|platform\.|io\.ktor\.|app\.cash\.sqldelight\.|dev\.kmpjourney\.pocketlab\.(data|network|sync|interop|diagnostics))' \
    "$COMMON_SOURCE"; then
    fail "common shared UI imports platform, data, network, or business implementation APIs"
fi
if rg -n \
    'ViewModel|NavController|ExperimentRepository|ExperimentRecord|RemoteDataSource|SqlDelight|HttpClient|CoroutineScope' \
    "$COMMON_SOURCE"; then
    fail "the leaf component absorbed navigation, lifecycle, persistence, or network ownership"
fi
if rg -n 'project\(":(domain|shared|androidApp)"\)' "$SHARED_UI_BUILD"; then
    fail "the presentation spike depends on the production business/data graph"
fi
if rg -n 'project\(":sharedUi"\)' \
    "$PROJECT_ROOT/domain/build.gradle.kts" \
    "$PROJECT_ROOT/shared/build.gradle.kts" \
    "$PROJECT_ROOT/androidApp/build.gradle.kts"; then
    fail "the optional spike entered a production module dependency"
fi

mkdir -p "$PROJECT_ROOT/build/swift-module-cache"
xcrun --sdk iphoneos swiftc \
    -typecheck \
    -warnings-as-errors \
    -swift-version 6 \
    -strict-concurrency=complete \
    -module-cache-path "$PROJECT_ROOT/build/swift-module-cache" \
    -target arm64-apple-ios15.0 \
    -F "$PROJECT_ROOT/sharedUi/build/bin/iosArm64/releaseFramework" \
    "$SWIFT_SMOKE"

HEADER_LINES=$(wc -l < "$HEADER" | tr -d ' ')
AAR_BYTES=$(wc -c < "$AAR" | tr -d ' ')
FRAMEWORK_BYTES=$(wc -c < "$DEVICE_FRAMEWORK/PocketLabSharedUi" | tr -d ' ')
AAR_SHA=$(shasum -a 256 "$AAR" | awk '{print $1}')
FRAMEWORK_SHA=$(shasum -a 256 "$DEVICE_FRAMEWORK/PocketLabSharedUi" | awk '{print $1}')

echo "Compose shared UI contract passed."
echo "Compose Multiplatform: 1.11.1 on AGP 8.13.2 / compileSdk 36"
echo "Android host contract tests: 2/2"
echo "Android AAR: $AAR_BYTES bytes, sha256=$AAR_SHA"
echo "iOS device Release framework binary: $FRAMEWORK_BYTES bytes, sha256=$FRAMEWORK_SHA"
echo "iOS arm64 Simulator framework: cross-compiled; runtime execution depends on an Apple Silicon host"
echo "Swift 6 strict-concurrency consumer: passed"
echo "Apple header lines: $HEADER_LINES; presentation-model leaks: 0"
echo "Scope: one leaf card; navigation/lifecycle/data/network ownership: native or shared logic, not shared UI"
