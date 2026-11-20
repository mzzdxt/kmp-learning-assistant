#!/bin/sh

set -eu

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
LAB_ROOT=$(CDPATH= cd -- "$SCRIPT_DIR/.." && pwd)
REPORT=$(mktemp "${TMPDIR:-/tmp}/mobile-strategy.XXXXXX")
trap 'rm -f "$REPORT"' EXIT

ruby -c "$SCRIPT_DIR/evaluate_strategy.rb"
ruby "$SCRIPT_DIR/evaluate_strategy.rb" --check
ruby "$SCRIPT_DIR/evaluate_strategy.rb" --markdown > "$REPORT"

for candidate in \
    'Candidate: **KMP with native UI**' \
    'Candidate: **Flutter**' \
    'Candidate: **React Native**' \
    'Candidate: **Pure native**'
do
    rg -F -q "$candidate" "$REPORT" || {
        echo "Mobile strategy gate failed: missing $candidate" >&2
        exit 1
    }
done

rg -q 'not a universal framework score' "$LAB_ROOT/decision-model.json" || {
    echo 'Mobile strategy gate failed: model scope warning is missing' >&2
    exit 1
}

echo "Mobile strategy report rendered."
echo "Hard gates -> scenario weights -> candidate -> implementation spike"
