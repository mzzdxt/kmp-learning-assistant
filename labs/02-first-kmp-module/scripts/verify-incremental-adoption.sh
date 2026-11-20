#!/bin/sh

set -eu

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
PROJECT_ROOT=$(CDPATH= cd -- "$SCRIPT_DIR/.." && pwd)

if [ -z "${GRADLE_USER_HOME:-}" ]; then
    GRADLE_USER_HOME="$PROJECT_ROOT/../.gradle-user-home"
    export GRADLE_USER_HOME
fi

fail() {
    echo "Incremental adoption gate failed: $1" >&2
    exit 1
}

cd "$PROJECT_ROOT"

./gradlew \
    :androidApp:testDebugUnitTest \
    :shared:linkDebugFrameworkIosX64 \
    --configuration-cache-problems=fail \
    --console=plain

ANDROID_CONTRACT="androidApp/src/main/kotlin/dev/kmpjourney/pocketlab/android/ExperimentListGateway.kt"
ANDROID_ADAPTER="androidApp/src/main/kotlin/dev/kmpjourney/pocketlab/android/KmpExperimentListGateway.kt"
ANDROID_VIEW_MODEL="androidApp/src/main/kotlin/dev/kmpjourney/pocketlab/android/ExperimentListViewModel.kt"
ANDROID_SCREEN="androidApp/src/main/kotlin/dev/kmpjourney/pocketlab/android/ui/ExperimentListScreen.kt"
ANDROID_GRAPH="androidApp/src/main/kotlin/dev/kmpjourney/pocketlab/android/PocketLabApplication.kt"
SWIFT_GATEWAY="iosApp/PocketLabIos/ExperimentListGateway.swift"
SWIFT_VIEW_MODEL="iosApp/PocketLabIos/ExperimentListViewModel.swift"
SWIFT_SCREEN="iosApp/PocketLabIos/ExperimentListScreen.swift"
SWIFT_APP="iosApp/PocketLabIos/PocketLabIosApp.swift"
XCODE_PROJECT="iosApp/PocketLabIos.xcodeproj/project.pbxproj"

rg -q 'interface ExperimentListGateway' "$ANDROID_CONTRACT" || \
    fail 'Android host contract is missing'
if rg -n '^import dev\.kmpjourney\.pocketlab\.' "$ANDROID_CONTRACT"; then
    fail 'Android host contract imports the KMP implementation graph'
fi
rg -q 'class KmpExperimentListGateway' "$ANDROID_ADAPTER" || \
    fail 'Android KMP adapter is missing'
rg -q 'KmpExperimentListGateway' "$ANDROID_GRAPH" || \
    fail 'Android composition root does not select the KMP adapter'

if rg -n \
    'ExperimentLocalStore|ExperimentRecord|ExperimentSyncState|RefreshExperimentsUseCase|RefreshFailureReason' \
    "$ANDROID_VIEW_MODEL" "$ANDROID_SCREEN"; then
    fail 'Android ViewModel or UI depends directly on KMP feature types'
fi

rg -q 'protocol ExperimentListGateway' "$SWIFT_GATEWAY" || \
    fail 'Swift host contract is missing'
rg -q 'class KmpExperimentListGateway' "$SWIFT_GATEWAY" || \
    fail 'Swift KMP adapter is missing'
rg -q 'KmpExperimentListGateway' "$SWIFT_APP" || \
    fail 'Swift composition root does not select the KMP adapter'
rg -q 'ExperimentListGateway\.swift in Sources' "$XCODE_PROJECT" || \
    fail 'the Swift migration adapter is absent from the Xcode application target'

if rg -n \
    'PocketLabShared|PocketLabFeature|ExperimentListClient|ExperimentSnapshot|RefreshReport' \
    "$SWIFT_VIEW_MODEL" "$SWIFT_SCREEN"; then
    fail 'Swift ViewModel or UI depends directly on the KMP framework'
fi

if rg -n 'LegacyExperimentListGateway' \
    androidApp/src/main iosApp/PocketLabIos; then
    fail 'a legacy migration fixture entered production sources'
fi

if rg -n 'mergeServerSnapshot|pendingUploads|save\(' \
    "$ANDROID_ADAPTER" "$SWIFT_GATEWAY"; then
    fail 'a migration adapter performs a second data write'
fi

FRAMEWORK_DIR="shared/build/bin/iosX64/debugFramework"
xcrun --sdk iphonesimulator swiftc \
    -typecheck \
    -warnings-as-errors \
    -swift-version 6 \
    -strict-concurrency=complete \
    -target x86_64-apple-ios17.0-simulator \
    -F "$FRAMEWORK_DIR" \
    iosApp/PocketLabIos/ExperimentListAsyncSequence.swift \
    iosApp/PocketLabIos/ExperimentListGateway.swift \
    iosApp/PocketLabIos/ExperimentListScreen.swift \
    iosApp/PocketLabIos/ExperimentListViewModel.swift \
    iosApp/PocketLabIos/PocketLabIosApp.swift \
    scripts/incremental-adoption-smoke.swift

echo "Incremental adoption contract passed."
echo "Android host ViewModel/UI -> Android-owned contract -> KMP adapter"
echo "Swift host ViewModel/UI -> Swift-owned contract -> KMP adapter"
echo "Legacy rollback fixtures: test/type-check only"
echo "Production dual writes: 0"
