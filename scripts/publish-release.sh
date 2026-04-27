#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 1 ]]; then
  printf 'Usage: %s <version>\n' "$0" >&2
  printf 'Example: %s 0.0.1\n' "$0" >&2
  exit 64
fi

VERSION="$1"
TAG="v${VERSION}"
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
APK_SOURCE="$ROOT_DIR/app/build/outputs/apk/release/app-release.apk"
APK_OUTPUT="$ROOT_DIR/app/build/outputs/apk/release/ShareHelper-${TAG}.apk"

require_env() {
  local name="$1"
  if [[ -z "${!name:-}" ]]; then
    printf 'Missing required environment variable: %s\n' "$name" >&2
    exit 65
  fi
}

require_env SHAREHELPER_RELEASE_STORE_FILE
require_env SHAREHELPER_RELEASE_STORE_PASSWORD
require_env SHAREHELPER_RELEASE_KEY_ALIAS
require_env SHAREHELPER_RELEASE_KEY_PASSWORD

cd "$ROOT_DIR"

./gradlew clean :app:assembleRelease
cp "$APK_SOURCE" "$APK_OUTPUT"

GIT_MASTER=1 git diff --quiet || {
  printf 'Working tree has uncommitted changes. Commit them before publishing.\n' >&2
  exit 66
}

GIT_MASTER=1 git rev-parse "$TAG" >/dev/null 2>&1 || GIT_MASTER=1 git tag -a "$TAG" -m "$TAG"
GIT_MASTER=1 git push origin HEAD
GIT_MASTER=1 git push origin "$TAG"

gh release view "$TAG" >/dev/null 2>&1 || \
  gh release create "$TAG" --title "$TAG" --generate-notes
gh release delete-asset "$TAG" "sharehelper-${TAG}.apk" -y >/dev/null 2>&1 || true
gh release upload "$TAG" "$APK_OUTPUT" --clobber

printf 'Published %s with %s\n' "$TAG" "$APK_OUTPUT"
