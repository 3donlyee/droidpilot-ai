@rem
rem DroidPilot AI - minimal gradlew.bat stub (Windows)
rem
rem The real gradle-wrapper.jar is intentionally absent.
rem Run:  gradle wrapper --gradle-version 8.9   (or .\bootstrap.bat)
rem to regenerate the full wrapper.

@if "%DEBUG%"=="" @echo off
@rem ##########################################################################
@rem
@rem  DroidPilot AI — minimal Gradle wrapper fallback for Windows
@rem
@rem ##########################################################################

setlocal

set DIRNAME=%~dp0
if "%DIRNAME%"=="" set DIRNAME=.
@rem strip trailing backslash
if "%DIRNAME:~-1%"=="\" set DIRNAME=%DIRNAME:~0,-1%

if not exist "%DIRNAME%\gradle\wrapper\gradle-wrapper.jar" (
  echo [DroidPilot] gradle-wrapper.jar is missing.
  echo [DroidPilot] Run: gradle wrapper --gradle-version 8.9   (or .\bootstrap.bat^)
  echo [DroidPilot] Falling back to system 'gradle' if available...
  where gradle >nul 2>&1
  if %ERRORLEVEL%==0 (
    gradle %*
    goto end
  )
  echo [DroidPilot] ERROR: gradle not found on PATH. Install Gradle or run bootstrap.bat.
  exit /b 1
)

java -classpath "%DIRNAME%\gradle\wrapper\gradle-wrapper.jar" org.gradle.wrapper.GradleWrapperMain %*

:end
endlocal
