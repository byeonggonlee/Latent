#!/usr/bin/env bash
# Checks what PRIVACY.md promises, on the release build: no analytics, crash reporting or Google services among its
# libraries, and no permission beyond those listed in app/expected-permissions.txt (which PRIVACY.md explains).
# Run it after `./gradlew :app:processReleaseManifest`; CI does. A change that is meant is made in both files.
set -euo pipefail
cd "$(dirname "$0")/.."

status=0

banned='play-services|firebase|crashlytics|com\.google\.android\.gms|analytics|datatransport|mlkit|sentry|appcenter|bugsnag|amplitude|mixpanel'
# The list is read first and on its own: a listing that failed, or came back empty, proves nothing about the libraries.
if ! libraries=$(./gradlew -q :app:dependencies --configuration releaseRuntimeClasspath); then
  echo "::error::Could not list the release build's libraries, so they were not checked."
  exit 1
fi
if [ -z "$libraries" ]; then
  echo "::error::The list of the release build's libraries came back empty, so they were not checked."
  exit 1
fi
# grep says 1 for no match, which is what is hoped for, and 2 or more for a failure of its own.
found=$(printf '%s\n' "$libraries" | grep -Ei "$banned") || [ $? -eq 1 ]
if [ -n "$found" ]; then
  echo "::error::The release build has a library that reports or services that PRIVACY.md says it has none of:"
  echo "$found"
  status=1
fi

manifest=app/build/intermediates/merged_manifests/release/processReleaseManifest/AndroidManifest.xml
actual=$(grep -o '<uses-permission[^>]*android:name="[^"]*"' "$manifest" | sed 's/.*android:name="//; s/"$//' | sort -u)
expected=$(sort -u app/expected-permissions.txt)
if [ "$actual" != "$expected" ]; then
  echo "::error::The permissions of the release build are not those in app/expected-permissions.txt (< expected, > actual):"
  diff <(echo "$expected") <(echo "$actual") || true
  status=1
fi

[ "$status" -eq 0 ] && echo "Privacy checks passed: no tracking libraries, and the permissions are as expected."
exit "$status"
