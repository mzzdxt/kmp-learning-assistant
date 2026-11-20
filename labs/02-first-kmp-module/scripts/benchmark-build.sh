#!/bin/sh

set -eu

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
PROJECT_ROOT=$(CDPATH= cd -- "$SCRIPT_DIR/.." && pwd)

if [ -z "${GRADLE_USER_HOME:-}" ]; then
    GRADLE_USER_HOME="$PROJECT_ROOT/../.gradle-user-home"
    export GRADLE_USER_HOME
fi

SCENARIO=${1:-dev-noop}
ITERATIONS=${2:-3}

case "$ITERATIONS" in
    ''|*[!0-9]*)
        echo "Iterations must be a positive integer." >&2
        exit 2
        ;;
    0)
        echo "Iterations must be greater than zero." >&2
        exit 2
        ;;
esac

case "$SCENARIO" in
    config-only)
        TASKS="help"
        ;;
    dev-noop)
        TASKS=":domain:jvmTest :shared:jvmTest :androidApp:testDebugUnitTest"
        ;;
    native-noop)
        TASKS=":shared:linkDebugFrameworkIosX64"
        ;;
    dev-clean-cache)
        TASKS=":domain:jvmTest :shared:jvmTest :androidApp:testDebugUnitTest"
        ;;
    *)
        echo "Usage: $0 [config-only|dev-noop|native-noop|dev-clean-cache] [iterations]" >&2
        exit 2
        ;;
esac

command -v ruby >/dev/null 2>&1 || {
    echo "Ruby is required for monotonic millisecond timing." >&2
    exit 2
}

command -v rg >/dev/null 2>&1 || {
    echo "ripgrep is required to summarize Gradle output." >&2
    exit 2
}

now_ms() {
    ruby -e 'puts((Process.clock_gettime(Process::CLOCK_MONOTONIC) * 1000).round)' \
        2>/dev/null
}

RUN_ID="${SCENARIO}-$(date -u +%Y%m%dT%H%M%SZ)-$$"
RESULT_ROOT="$PROJECT_ROOT/build/benchmarks/$RUN_ID"
TEMP_ROOT=$(mktemp -d "${TMPDIR:-/tmp}/pocketlab-build-benchmark.XXXXXX")
RAW_RESULT="$TEMP_ROOT/results.tsv"

cleanup() {
    rm -rf "$TEMP_ROOT"
}
trap cleanup EXIT HUP INT TERM

printf 'case\titeration\telapsed_ms\tcache_state\tgradle_summary\ttask_summary\n' > "$RAW_RESULT"

run_gradle() {
    case_name=$1
    iteration=$2
    cache_flag=$3
    label=$4
    build_cache_flag=${5:-}
    log_file="$TEMP_ROOT/${case_name}-${iteration}.log"

    start_ms=$(now_ms)
    set +e
    # TASKS is an allowlisted, space-separated list selected above.
    ./gradlew $TASKS \
        "$cache_flag" \
        $build_cache_flag \
        --configuration-cache-problems=fail \
        --console=plain \
        -Pkotlin.build.report.label="$label" \
        > "$log_file" 2>&1
    exit_code=$?
    set -e
    end_ms=$(now_ms)

    if [ "$exit_code" -ne 0 ]; then
        cat "$log_file" >&2
        echo "Benchmark case '$case_name' failed." >&2
        exit "$exit_code"
    fi

    cache_state=disabled
    if rg -q 'Configuration cache entry stored' "$log_file"; then
        cache_state=stored
    elif rg -q 'Configuration cache entry reused|Reusing configuration cache' "$log_file"; then
        cache_state=reused
    elif [ "$cache_flag" = "--configuration-cache" ]; then
        cache_state=enabled-unknown
    fi

    gradle_summary=$(rg 'BUILD SUCCESSFUL in ' "$log_file" | tail -n 1 | tr '\t' ' ')
    task_summary=$(rg '[0-9]+ actionable tasks:' "$log_file" | tail -n 1 | tr '\t' ' ')
    elapsed_ms=$((end_ms - start_ms))

    printf '%s\t%s\t%s\t%s\t%s\t%s\n' \
        "$case_name" \
        "$iteration" \
        "$elapsed_ms" \
        "$cache_state" \
        "$gradle_summary" \
        "$task_summary" \
        >> "$RAW_RESULT"
}

cd "$PROJECT_ROOT"

if [ "$SCENARIO" = "dev-clean-cache" ]; then
    echo "Warming the Gradle daemon"
    ./gradlew help --no-configuration-cache --console=plain \
        > "$TEMP_ROOT/warmup.log" 2>&1

    echo "Cleaning generated project outputs"
    ./gradlew clean --no-configuration-cache --console=plain \
        > "$TEMP_ROOT/clean-before-compile.log" 2>&1

    echo "Measuring clean compilation without the build cache"
    run_gradle \
        "clean-compile" \
        "1" \
        "--no-configuration-cache" \
        "$RUN_ID-clean" \
        "--no-build-cache"

    echo "Cleaning generated project outputs again"
    ./gradlew clean --no-configuration-cache --console=plain \
        > "$TEMP_ROOT/clean-before-restore.log" 2>&1

    echo "Measuring cache population after a clean build"
    run_gradle \
        "cache-populate-after-clean" \
        "1" \
        "--configuration-cache" \
        "$RUN_ID-restore" \
        "--build-cache"

    echo "Cleaning generated outputs before the warm cache restore"
    ./gradlew clean --no-configuration-cache --console=plain \
        > "$TEMP_ROOT/clean-before-warm-restore.log" 2>&1

    echo "Measuring a warm build-cache restore"
    run_gradle \
        "build-cache-warm-restore" \
        "1" \
        "--configuration-cache" \
        "$RUN_ID-restore" \
        "--build-cache"

    echo "Measuring no-op after the warm restore"
    run_gradle \
        "restored-noop" \
        "1" \
        "--configuration-cache" \
        "$RUN_ID-restore" \
        "--build-cache"
else
    echo "Warming daemon and task outputs for scenario: $SCENARIO"
    ./gradlew $TASKS \
        --no-configuration-cache \
        --console=plain \
        -Pkotlin.build.report.label="$RUN_ID-warmup" \
        > "$TEMP_ROOT/warmup.log" 2>&1

    index=1
    while [ "$index" -le "$ITERATIONS" ]; do
        echo "Measuring configuration-cache disabled: $index/$ITERATIONS"
        run_gradle "cache-disabled" "$index" "--no-configuration-cache" "$RUN_ID-baseline"
        index=$((index + 1))
    done

    echo "Measuring configuration-cache store"
    run_gradle "cache-store" "1" "--configuration-cache" "$RUN_ID-candidate"

    index=1
    while [ "$index" -le "$ITERATIONS" ]; do
        echo "Measuring configuration-cache reuse: $index/$ITERATIONS"
        run_gradle "cache-reuse" "$index" "--configuration-cache" "$RUN_ID-candidate"
        index=$((index + 1))
    done
fi

mkdir -p "$RESULT_ROOT/logs"
cp "$RAW_RESULT" "$RESULT_ROOT/results.tsv"
cp "$TEMP_ROOT"/*.log "$RESULT_ROOT/logs/"

SUMMARY="$RESULT_ROOT/summary.md"
{
    echo "# PocketLab Gradle benchmark"
    echo
    echo "- Scenario: \`$SCENARIO\`"
    echo "- Tasks: \`$TASKS\`"
    echo "- Iterations: $ITERATIONS"
    echo "- UTC run id: \`$RUN_ID\`"
    echo "- Host: \`$(uname -sm)\`"
    echo "- Java: \`$(java -version 2>&1 | head -n 1)\`"
    echo
    echo '| Case | Samples | Average ms | Min ms | Max ms |'
    echo '|---|---:|---:|---:|---:|'
    awk -F '\t' '
        NR > 1 {
            key = $1
            if (!(key in seen)) {
                seen[key] = 1
                key_count += 1
                order[key_count] = key
            }
            count[key] += 1
            sum[key] += $3
            if (!(key in min) || $3 < min[key]) min[key] = $3
            if (!(key in max) || $3 > max[key]) max[key] = $3
        }
        END {
            for (i = 1; i <= key_count; i++) {
                key = order[i]
                printf "| %s | %d | %.0f | %d | %d |\n", \
                    key, count[key], sum[key] / count[key], min[key], max[key]
            }
        }
    ' "$RAW_RESULT"
    echo
    echo 'Raw timings and Gradle output are stored beside this summary.'
} > "$SUMMARY"

cat "$SUMMARY"
echo
echo "Benchmark evidence: $RESULT_ROOT"
