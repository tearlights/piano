#!/usr/bin/env bash
set -euo pipefail

required_files=(README.md PROJECT_CHARTER.md AGENTS.md .codex/project-context.md)
for file in "${required_files[@]}"; do
  [ -f "$file" ] || { echo "Missing required project file: $file"; exit 1; }
done

if [ -f ./gradlew ]; then
  ./gradlew check
else
  echo 'Project documentation structure is valid. Android project not created yet.'
fi
