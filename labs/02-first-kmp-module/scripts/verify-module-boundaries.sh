#!/bin/sh

set -eu

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
PROJECT_ROOT=$(CDPATH= cd -- "$SCRIPT_DIR/.." && pwd)
DOMAIN_SOURCE="$PROJECT_ROOT/domain/src"
DOMAIN_BUILD="$PROJECT_ROOT/domain/build.gradle.kts"
SHARED_BUILD="$PROJECT_ROOT/shared/build.gradle.kts"
HEADER="$PROJECT_ROOT/shared/build/bin/iosX64/debugFramework/PocketLabShared.framework/Headers/PocketLabShared.h"

if [ -z "${GRADLE_USER_HOME:-}" ]; then
    GRADLE_USER_HOME="$PROJECT_ROOT/../.gradle-user-home"
    export GRADLE_USER_HOME
fi

fail() {
    echo "Module boundary gate failed: $1" >&2
    exit 1
}

cd "$PROJECT_ROOT"

./gradlew \
    :domain:jvmTest \
    :domain:testAndroidHostTest \
    :domain:iosX64Test \
    :shared:linkDebugFrameworkIosX64

if rg -n '^import (android\.|platform\.|io\.ktor\.|app\.cash\.sqldelight\.)' \
    "$DOMAIN_SOURCE"; then
    fail "domain imports a platform or infrastructure API"
fi

if rg -n 'project\(":shared"\)|binaries\.framework|XCFramework' "$DOMAIN_BUILD"; then
    fail "domain depends on the composition module or publishes an Apple framework"
fi

rg -q 'api\(project\(":domain"\)\)' "$SHARED_BUILD" || \
    fail "shared does not expose domain to Kotlin consumers"

if rg -n 'export\(project\(":domain"\)\)|transitiveExport' "$SHARED_BUILD"; then
    fail "domain is exported into the Apple framework"
fi

[ -f "$HEADER" ] || fail "Apple framework header was not generated"
rg -q 'swift_name\("ExperimentListClient"\)' "$HEADER" || \
    fail "the Apple facade is missing from the umbrella framework"

if rg -n \
    'StudyStreakPolicy|DraftValidator|SaveDraftUseCase|ExperimentRepository|ExperimentLocalStore|RemoteExperimentsResult|DraftEditorStore' \
    "$HEADER"; then
    fail "a non-exported domain declaration leaked into the Apple header"
fi

HEADER_LINES=$(wc -l < "$HEADER" | tr -d ' ')

echo "Module boundary contract passed."
echo "domain -> platform/infrastructure imports: 0"
echo "shared -> domain for Kotlin consumers: api"
echo "shared -> domain for Apple consumers: linked, not exported"
echo "Apple header lines: $HEADER_LINES"
