#!/bin/sh

set -eu

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
PROJECT_ROOT=$(CDPATH= cd -- "$SCRIPT_DIR/.." && pwd)

if [ -z "${GRADLE_USER_HOME:-}" ]; then
    GRADLE_USER_HOME="$PROJECT_ROOT/../.gradle-user-home"
    export GRADLE_USER_HOME
fi

cd "$PROJECT_ROOT"

./gradlew :shared:linkDebugFrameworkIosX64

HEADER="shared/build/bin/iosX64/debugFramework/PocketLabShared.framework/Headers/PocketLabShared.h"
FRAMEWORK_DIR="shared/build/bin/iosX64/debugFramework"

expect_header() {
    pattern=$1
    description=$2
    if ! rg -q -- "$pattern" "$HEADER"; then
        echo "Missing Apple API contract: $description" >&2
        exit 1
    fi
}

reject_header() {
    pattern=$1
    description=$2
    if rg -q -- "$pattern" "$HEADER"; then
        echo "Leaked Apple API implementation detail: $description" >&2
        exit 1
    fi
}

expect_header 'swift_name\("ExperimentListClient"\)' 'Swift client name'
expect_header 'swift_name\("ExperimentSnapshot"\)' 'Apple snapshot name'
expect_header 'swift_name\("RefreshReport"\)' 'refresh report name'
expect_header 'swift_name\("InteropFailure"\)' 'typed interop failure name'
expect_header 'swift_name\("PocketLabFeature"\)' 'composition root name'
expect_header 'swift_name\("CancellationToken"\)' 'cancellation token name'
expect_header 'swift_private' 'raw callbacks refined out of autocomplete'

reject_header 'swift_name\("ExperimentRecord"\)' 'shared database record'
reject_header 'RefreshExperimentsResult' 'shared sealed result hierarchy'
reject_header 'initWithLocalStore' 'bridge construction dependencies'

xcrun --sdk iphonesimulator swiftc \
    -typecheck \
    -swift-version 6 \
    -strict-concurrency=complete \
    -target x86_64-apple-ios17.0-simulator \
    -F "$FRAMEWORK_DIR" \
    iosApp/PocketLabIos/ExperimentListAsyncSequence.swift \
    iosApp/PocketLabIos/ExperimentListScreen.swift \
    iosApp/PocketLabIos/ExperimentListViewModel.swift \
    iosApp/PocketLabIos/PocketLabIosApp.swift

echo "Apple API contract and Swift 6 type-check passed."
