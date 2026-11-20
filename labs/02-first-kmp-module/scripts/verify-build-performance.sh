#!/bin/sh

set -eu

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
PROJECT_ROOT=$(CDPATH= cd -- "$SCRIPT_DIR/.." && pwd)
PROPERTIES="$PROJECT_ROOT/gradle.properties"

if [ -z "${GRADLE_USER_HOME:-}" ]; then
    GRADLE_USER_HOME="$PROJECT_ROOT/../.gradle-user-home"
    export GRADLE_USER_HOME
fi

fail() {
    echo "Build performance gate failed: $1" >&2
    exit 1
}

require_property() {
    property=$1
    description=$2
    rg -q "^${property}$" "$PROPERTIES" || fail "$description"
}

require_property 'org\.gradle\.caching=true' 'Gradle build cache is disabled'
require_property 'org\.gradle\.configuration-cache=true' 'configuration cache is disabled'
require_property 'kotlin\.build\.report\.output=file,json' 'Kotlin file/JSON reports are disabled'

sh -n "$SCRIPT_DIR/benchmark-build.sh"

TEMP_ROOT=$(mktemp -d "${TMPDIR:-/tmp}/pocketlab-performance-gate.XXXXXX")
cleanup() {
    rm -rf "$TEMP_ROOT"
}
trap cleanup EXIT HUP INT TERM

cd "$PROJECT_ROOT"

DEFAULT_REPORT_DIR="$PROJECT_ROOT/build/reports/kotlin-build"
JSON_BEFORE=$(find "$DEFAULT_REPORT_DIR" -type f -name '*.json' 2>/dev/null | \
    sort | tail -n 1 || true)

./gradlew :domain:compileKotlinJvm \
    --rerun-tasks \
    --configuration-cache \
    --configuration-cache-problems=fail \
    --console=plain \
    -Pkotlin.build.report.output=file,json \
    -Pkotlin.build.report.file.output_dir="$TEMP_ROOT/kotlin-reports" \
    -Pkotlin.build.report.label=performance-gate \
    > "$TEMP_ROOT/compile.log" 2>&1

find "$TEMP_ROOT/kotlin-reports" -type f -name '*.txt' | rg -q . || \
    fail 'human-readable Kotlin build report was not generated'

# Kotlin 2.4.21 writes JSON to the default report directory even when the
# documented file output directory override moves the text report.
JSON_AFTER=$(find "$DEFAULT_REPORT_DIR" -type f -name '*.json' 2>/dev/null | \
    sort | tail -n 1 || true)
[ -n "$JSON_AFTER" ] || fail 'JSON Kotlin build report was not generated'
[ "$JSON_AFTER" != "$JSON_BEFORE" ] || fail 'no new JSON Kotlin build report was generated'
rg -q 'performance-gate' "$JSON_AFTER" || fail 'JSON report is missing the gate label'

DEV_TASKS=":domain:jvmTest :shared:jvmTest :androidApp:testDebugUnitTest"

# TASKS is a fixed allowlisted list owned by this script.
./gradlew $DEV_TASKS \
    --configuration-cache \
    --configuration-cache-problems=fail \
    --console=plain \
    -Pkotlin.build.report.label=performance-gate-dev \
    > "$TEMP_ROOT/cache-first.log" 2>&1

./gradlew $DEV_TASKS \
    --configuration-cache \
    --configuration-cache-problems=fail \
    --console=plain \
    -Pkotlin.build.report.label=performance-gate-dev \
    > "$TEMP_ROOT/cache-second.log" 2>&1

rg -q 'Reusing configuration cache|Configuration cache entry reused' \
    "$TEMP_ROOT/cache-second.log" || \
    fail 'the repeated developer task did not reuse configuration cache'

echo "Build performance contract passed."
echo "Gradle build cache: enabled"
echo "Gradle configuration cache: stored and reused"
echo "Kotlin compiler reports: file + JSON"
echo "Benchmark harness: shell syntax valid"
