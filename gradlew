#!/bin/sh
set -eu
APP_HOME=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
JAR="$APP_HOME/gradle/wrapper/gradle-wrapper.jar"
EXPECTED="81a82aaea5abcc8ff68b3dfcb58b3c3c429378efd98e7433460610fecd7ae45f"
if [ ! -f "$JAR" ]; then
  command -v curl >/dev/null 2>&1 || { echo "Install curl to retrieve the official Gradle wrapper" >&2; exit 1; }
  curl --fail --location --silent --show-error "https://services.gradle.org/distributions/gradle-8.13-wrapper.jar" -o "$JAR.download"
  mv "$JAR.download" "$JAR"
fi
if command -v sha256sum >/dev/null 2>&1; then
  ACTUAL=$(sha256sum "$JAR" | cut -d ' ' -f 1)
else
  ACTUAL=$(shasum -a 256 "$JAR" | cut -d ' ' -f 1)
fi
if [ "$ACTUAL" != "$EXPECTED" ]; then
  echo "Gradle wrapper integrity verification failed. Delete $JAR and retry." >&2
  exit 1
fi
exec java -cp "$JAR" org.gradle.wrapper.GradleWrapperMain "$@"
