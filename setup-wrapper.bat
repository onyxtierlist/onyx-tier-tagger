@echo off
cd /d "%~dp0"
where gradle >nul 2>nul
if errorlevel 1 (
  echo Gradle was not found on PATH.
  pause
  exit /b 1
)
REM This project uses the installed Gradle command through gradlew.bat.
REM A binary gradle-wrapper.jar is intentionally not fabricated or bundled.
gradle wrapper --gradle-version 9.2.0
pause
