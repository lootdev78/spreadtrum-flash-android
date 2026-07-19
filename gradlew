#!/usr/bin/env sh
set -eu
APP_HOME=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
JAR="$APP_HOME/gradle/wrapper/gradle-wrapper.jar"
EXPECTED_SHA256="81a82aaea5abcc8ff68b3dfcb58b3c3c429378efd98e7433460610fecd7ae45f"
URL="https://raw.githubusercontent.com/gradle/gradle/v8.13.0/gradle/wrapper/gradle-wrapper.jar"

sha256_file() {
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$1" | awk '{print $1}'
  elif command -v shasum >/dev/null 2>&1; then
    shasum -a 256 "$1" | awk '{print $1}'
  else
    echo "Neither sha256sum nor shasum is available." >&2
    exit 1
  fi
}

verify_wrapper() {
  actual=$(sha256_file "$1")
  if [ "$actual" != "$EXPECTED_SHA256" ]; then
    echo "Gradle 8.13 wrapper integrity check failed: $actual" >&2
    return 1
  fi
}

if [ -f "$JAR" ] && ! verify_wrapper "$JAR"; then
  rm -f "$JAR"
fi

if [ ! -f "$JAR" ]; then
  mkdir -p "$(dirname "$JAR")"
  tmp="$JAR.tmp.$$"
  trap 'rm -f "$tmp"' EXIT HUP INT TERM
  echo "Downloading the official Gradle 8.13 wrapper and verifying its SHA-256…" >&2
  if command -v curl >/dev/null 2>&1; then
    curl -fL --retry 3 --connect-timeout 20 "$URL" -o "$tmp"
  elif command -v wget >/dev/null 2>&1; then
    wget -O "$tmp" "$URL"
  else
    echo "curl or wget is missing. Open the project in Android Studio instead." >&2
    exit 1
  fi
  verify_wrapper "$tmp"
  mv "$tmp" "$JAR"
  trap - EXIT HUP INT TERM
fi

exec java -classpath "$JAR" org.gradle.wrapper.GradleWrapperMain "$@"
