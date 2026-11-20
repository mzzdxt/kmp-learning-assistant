#!/bin/sh

set -eu

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
PROJECT_ROOT=$(CDPATH= cd -- "$SCRIPT_DIR/.." && pwd)

if [ -z "${GRADLE_USER_HOME:-}" ]; then
    GRADLE_USER_HOME="$PROJECT_ROOT/../.gradle-user-home"
    export GRADLE_USER_HOME
fi

XCFRAMEWORK="$PROJECT_ROOT/shared/build/XCFrameworks/release/PocketLabShared.xcframework"
DEVICE_FRAMEWORK="$XCFRAMEWORK/ios-arm64/PocketLabShared.framework"
SIMULATOR_FRAMEWORK="$XCFRAMEWORK/ios-arm64_x86_64-simulator/PocketLabShared.framework"
PACKAGE_TEMPLATE="$PROJECT_ROOT/distribution/apple/Package.swift"
STAGING_ROOT="$PROJECT_ROOT/build/apple-distribution"
PACKAGE_ROOT="$STAGING_ROOT/SwiftPackage"
ARCHIVE="$STAGING_ROOT/PocketLabShared-0.14.0.xcframework.zip"

cd "$PROJECT_ROOT"

./gradlew :shared:assemblePocketLabSharedReleaseXCFramework

fail() {
    echo "Apple distribution gate failed: $1" >&2
    exit 1
}

[ -d "$DEVICE_FRAMEWORK" ] || fail "missing ios-arm64 framework"
[ -d "$SIMULATOR_FRAMEWORK" ] || fail "missing universal Simulator framework"

DEVICE_ARCHS=$(lipo -archs "$DEVICE_FRAMEWORK/PocketLabShared")
SIMULATOR_ARCHS=$(lipo -archs "$SIMULATOR_FRAMEWORK/PocketLabShared")

[ "$DEVICE_ARCHS" = "arm64" ] || fail "device slice is not arm64: $DEVICE_ARCHS"
echo "$SIMULATOR_ARCHS" | rg -q '(^| )arm64( |$)' || fail "Simulator slice lacks arm64"
echo "$SIMULATOR_ARCHS" | rg -q '(^| )x86_64( |$)' || fail "Simulator slice lacks x86_64"

for FRAMEWORK in "$DEVICE_FRAMEWORK" "$SIMULATOR_FRAMEWORK"; do
    PLIST="$FRAMEWORK/Info.plist"
    [ "$(plutil -extract CFBundleIdentifier raw "$PLIST")" = \
        "dev.kmpjourney.pocketlab.shared" ] || fail "unexpected bundle identifier"
    [ "$(plutil -extract CFBundleShortVersionString raw "$PLIST")" = \
        "0.14.0" ] || fail "unexpected marketing version"
    [ "$(plutil -extract CFBundleVersion raw "$PLIST")" = \
        "14" ] || fail "unexpected bundle version"
    file "$FRAMEWORK/PocketLabShared" | rg -q 'archive' || \
        fail "framework binary is not static"
done

HEADER="$SIMULATOR_FRAMEWORK/Headers/PocketLabShared.h"
rg -q 'swift_name\("ExperimentListClient"\)' "$HEADER" || \
    fail "Swift-friendly API name is missing"
if rg -q \
    'swift_name\("ExperimentRecord"\)|RefreshExperimentsResult|initWithLocalStore|StudyStreakPolicy|DraftValidator|SaveDraftUseCase|ExperimentRepository|RemoteExperimentsResult|DraftEditorStore' \
    "$HEADER"; then
    fail "an internal Kotlin type leaked into the distributed header"
fi

xcrun --sdk iphonesimulator swiftc \
    -typecheck \
    -swift-version 6 \
    -strict-concurrency=complete \
    -target x86_64-apple-ios17.0-simulator \
    -F "$(dirname -- "$SIMULATOR_FRAMEWORK")" \
    "$SCRIPT_DIR/apple-distribution-smoke.swift"

rm -rf "$STAGING_ROOT"
mkdir -p "$PACKAGE_ROOT/Artifacts"
cp "$PACKAGE_TEMPLATE" "$PACKAGE_ROOT/Package.swift"
cp -R "$XCFRAMEWORK" "$PACKAGE_ROOT/Artifacts/PocketLabShared.xcframework"

swift package --package-path "$PACKAGE_ROOT" dump-package > "$STAGING_ROOT/package.json"
swift package --package-path "$PACKAGE_ROOT" describe --type json > \
    "$STAGING_ROOT/package-description.json"

ditto -c -k --sequesterRsrc --keepParent "$XCFRAMEWORK" "$ARCHIVE"
swift package compute-checksum "$ARCHIVE" > "$STAGING_ROOT/checksum.txt"

echo "Apple distribution contract passed."
echo "Device architectures: $DEVICE_ARCHS"
echo "Simulator architectures: $SIMULATOR_ARCHS"
echo "Swift package: $PACKAGE_ROOT"
echo "Remote artifact checksum: $(cat "$STAGING_ROOT/checksum.txt")"
