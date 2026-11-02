#!/bin/sh
# Every check, in one run: exits non-zero if any failed. Set SKIP_GOLANGCI=1 where golangci-lint isn't installed.
# Usage: scripts/check.sh [extra gradle args, e.g. --offline]
# IDELINK_IDE_DIR: an installed IDE to build against; unset, Gradle downloads PyCharm platformVersion.
set -u
root=$(cd "$(dirname "$0")/.." && pwd)
failed=""
if [ -n "${IDELINK_IDE_DIR+set}" ]; then
    set -- "-PideDir=$IDELINK_IDE_DIR" "$@"
else
    echo "IDELINK_IDE_DIR unset: building against PyCharm platformVersion from JetBrains (downloaded on first use)"
fi

step() {
    name=$1; shift
    echo "== $name"
    if ! "$@"; then
        failed="$failed $name"
    fi
}

step gradle sh -c 'cd "$1/plugin" && shift && ./gradlew --continue "$@" buildPlugin :backend:test :frontend:test :terminal:test :python:test verifyPluginStructure verifyPlugin detekt' \
    sh "$root" "$@"
step tools-doc python3 "$root/scripts/tools_doc.py" check
step go-vet sh -c 'cd "$1/bridge" && go vet ./...' sh "$root"
step go-test sh -c 'cd "$1/bridge" && go test ./...' sh "$root"
if [ "${SKIP_GOLANGCI:-}" = 1 ]; then
    echo "== golangci-lint SKIPPED (SKIP_GOLANGCI=1)"
elif command -v golangci-lint >/dev/null; then
    step golangci-lint sh -c 'cd "$1/bridge" && golangci-lint run ./...' sh "$root"
else
    echo "golangci-lint not found: install it or set SKIP_GOLANGCI=1"
    failed="$failed golangci-lint"
fi
mkdir -p "$root/tmp"
step comment-lint sh -c 'cd "$1" && python3 scripts/comment_lint.py plugin bridge' sh "$root"
step comment-lint-tests sh -c 'cd "$1/scripts" && python3 -m unittest -q test_comment_lint' sh "$root"

if [ -n "$failed" ]; then
    echo "FAILED:$failed"
    exit 1
fi
echo "all checks passed"
