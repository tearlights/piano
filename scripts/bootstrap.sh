#!/usr/bin/env bash
set -euo pipefail

echo 'Gpiano development environment check'
command -v java >/dev/null && java -version || echo 'WARN: Java is not installed.'
command -v adb >/dev/null && adb version | head -1 || echo 'INFO: Android SDK platform-tools not found.'
if [ -f ./gradlew ]; then
  ./gradlew --version
else
  echo 'INFO: Android Gradle project has not been created yet.'
fi

echo 'Read PROJECT_CHARTER.md and .codex/project-context.md before development.'
