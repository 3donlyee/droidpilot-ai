#!/usr/bin/env bash
# DroidPilot AI — bootstrap.sh
#
# Regenerates the Gradle wrapper (gradlew + gradle-wrapper.jar) using a
# system-installed Gradle. This is needed ONCE before the first build
# because the wrapper jar cannot be shipped in this repo.
#
# Requirements: Gradle 8.5+ installed and on PATH.
#   macOS:    brew install gradle
#   Linux:    sdk install gradle 8.9   (via sdkman.io)
#   Windows:  choco install gradle    (or use Android Studio's bundled gradle)
#
# Alternative: open this project in Android Studio — it will offer to
# generate the wrapper automatically.

set -e
cd "$(dirname "$0")"

if [ -f "gradle/wrapper/gradle-wrapper.jar" ]; then
  echo "[bootstrap] gradle-wrapper.jar already exists — nothing to do."
  exit 0
fi

if ! command -v gradle >/dev/null 2>&1; then
  cat <<'MSG'

[bootstrap] Gradle was not found on PATH.
            Install Gradle 8.5+ and re-run, OR open this folder in
            Android Studio (it will offer to generate the wrapper
            automatically).

            Quick install options:
              macOS:   brew install gradle
              Linux:   curl -s "https://get.sdkman.io" | bash && sdk install gradle 8.9
              Windows: choco install gradle

            After install, run:  gradle wrapper --gradle-version 8.9
MSG
  exit 1
fi

echo "[bootstrap] Gradle found: $(gradle -v | grep -i 'gradle ' | head -n1)"
echo "[bootstrap] Generating wrapper (Gradle 8.9) ..."
gradle wrapper --gradle-version 8.9 --distribution-type bin
echo "[bootstrap] Done. You can now run:  ./gradlew assembleDebug"
