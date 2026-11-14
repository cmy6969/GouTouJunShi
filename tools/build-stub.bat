@echo off
rem Builds the compile-only Xposed API stub jar used by app/libs/xposed-api-stub.jar
rem Requires a JDK (11+). The output jar is not shipped inside the module APK.
setlocal
set HERE=%~dp0
if "%JAVA_HOME%"=="" (
  echo [stub] JAVA_HOME is not set
  exit /b 1
)
set OUT=%HERE%..\app\libs
if not exist "%OUT%" mkdir "%OUT%"
set TMP=%HERE%..\build\stub-classes
if exist "%TMP%" rmdir /s /q "%TMP%"
mkdir "%TMP%"
"%JAVA_HOME%\bin\javac.exe" -source 8 -target 8 -nowarn -d "%TMP%" ^
  "%HERE%xposed-stub\de\robv\android\xposed\*.java" ^
  "%HERE%xposed-stub\de\robv\android\xposed\callbacks\*.java"
if errorlevel 1 exit /b 1
"%JAVA_HOME%\bin\jar.exe" cf "%OUT%\xposed-api-stub.jar" -C "%TMP%" .
echo [stub] wrote %OUT%\xposed-api-stub.jar
endlocal
