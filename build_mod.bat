@echo off
cd /d "%~dp0"
echo Building Onyx Tier Tagger...
gradle clean build
if errorlevel 1 (
  echo.
  echo BUILD FAILED.
  pause
  exit /b 1
)
echo.
echo BUILD SUCCESSFUL!
echo JAR files are in build\libs\
pause
