@echo off
setlocal
set "APP_HOME=%~dp0"
set "JAR=%APP_HOME%gradle\wrapper\gradle-wrapper.jar"
set "TMP=%JAR%.tmp"
set "EXPECTED_SHA256=81a82aaea5abcc8ff68b3dfcb58b3c3c429378efd98e7433460610fecd7ae45f"
set "URL=https://raw.githubusercontent.com/gradle/gradle/v8.13.0/gradle/wrapper/gradle-wrapper.jar"

if exist "%JAR%" call :verify "%JAR%" || del /q "%JAR%"
if not exist "%JAR%" (
  echo Downloading the official Gradle 8.13 wrapper and verifying its SHA-256...
  if not exist "%APP_HOME%gradle\wrapper" mkdir "%APP_HOME%gradle\wrapper"
  powershell -NoProfile -ExecutionPolicy Bypass -Command ^
    "$ProgressPreference='SilentlyContinue'; Invoke-WebRequest -UseBasicParsing '%URL%' -OutFile '%TMP%'"
  if errorlevel 1 exit /b 1
  call :verify "%TMP%" || (del /q "%TMP%" & exit /b 1)
  move /y "%TMP%" "%JAR%" >nul
)

java -classpath "%JAR%" org.gradle.wrapper.GradleWrapperMain %*
exit /b %errorlevel%

:verify
for /f "tokens=*" %%H in ('powershell -NoProfile -Command "(Get-FileHash -Algorithm SHA256 -LiteralPath '%~1').Hash.ToLower()"') do set "ACTUAL=%%H"
if /i not "%ACTUAL%"=="%EXPECTED_SHA256%" (
  echo Gradle 8.13 wrapper integrity check failed: %ACTUAL%
  exit /b 1
)
exit /b 0
