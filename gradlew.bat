@echo off
setlocal
set "ROOT=%~dp0"
set "JAR=%ROOT%gradle\wrapper\gradle-wrapper.jar"
set "EXPECTED=81a82aaea5abcc8ff68b3dfcb58b3c3c429378efd98e7433460610fecd7ae45f"
if not exist "%JAR%" (
  powershell -NoProfile -Command "$ErrorActionPreference='Stop'; Invoke-WebRequest -Uri 'https://services.gradle.org/distributions/gradle-8.13-wrapper.jar' -OutFile '%JAR%'"
  if errorlevel 1 exit /b 1
)
for /f %%H in ('powershell -NoProfile -Command "(Get-FileHash -Algorithm SHA256 -LiteralPath '%JAR%').Hash.ToLower()"') do set "ACTUAL=%%H"
if /I not "%EXPECTED%"=="%ACTUAL%" (
  echo Gradle wrapper integrity verification failed.
  exit /b 1
)
java -cp "%JAR%" org.gradle.wrapper.GradleWrapperMain %*
exit /b %ERRORLEVEL%
