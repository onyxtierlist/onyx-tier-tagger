@echo off
setlocal
where gradle >nul 2>nul
if errorlevel 1 (
  echo Gradle was not found on PATH.
  echo Install Gradle or open a terminal where the Gradle command works.
  exit /b 1
)
gradle %*
exit /b %ERRORLEVEL%
