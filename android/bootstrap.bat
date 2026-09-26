@echo off
rem DroidPilot AI - bootstrap.bat (Windows)
rem Regenerates the Gradle wrapper using a system-installed Gradle.

cd /d "%~dp0"

if exist "gradle\wrapper\gradle-wrapper.jar" (
  echo [bootstrap] gradle-wrapper.jar already exists - nothing to do.
  exit /b 0
)

where gradle >nul 2>&1
if errorlevel 1 (
  echo [bootstrap] Gradle was not found on PATH.
  echo [bootstrap] Install Gradle 8.5+ and re-run, OR open this folder
  echo [bootstrap] in Android Studio ^(it will offer to generate the wrapper^).
  echo.
  echo [bootstrap] Quick install options:
  echo    choco install gradle
  echo    ^(or use Android Studio's bundled Gradle^)
  exit /b 1
)

echo [bootstrap] Gradle found. Generating wrapper (Gradle 8.9) ...
call gradle wrapper --gradle-version 8.9 --distribution-type bin
echo [bootstrap] Done. You can now run:  .\gradlew assembleDebug
