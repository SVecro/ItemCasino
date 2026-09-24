@echo off
setlocal
cd /d "%~dp0"
rem Builds through the committed Gradle wrapper: the first run downloads Gradle 9.2.1 once
rem into %USERPROFILE%\.gradle\wrapper, nothing needs installing by hand.
del /q build-errors.txt 2>nul
echo RUNNING > build-status.txt
call gradlew.bat build --console=plain > build-errors.txt 2>&1
echo EXITCODE=%ERRORLEVEL% >> build-errors.txt
echo DONE %ERRORLEVEL% > build-status.txt
