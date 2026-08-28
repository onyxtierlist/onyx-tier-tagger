#!/bin/sh
if ! command -v gradle >/dev/null 2>&1; then
  echo "Gradle was not found on PATH."
  exit 1
fi
exec gradle "$@"
