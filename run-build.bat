@echo off
setlocal
cd /d "%~dp0"
set "V=9.2.1"
set "D=%USERPROFILE%\gradle-dist"
del /q build-errors.txt 2>nul
echo RUNNING > build-status.txt
rem The Gradle wrapper (gradlew.bat and gradle\wrapper\) lets anyone build the project without this
rem local Gradle install. Generated once, by the local Gradle, the first time it is missing.
if not exist gradlew.bat call "%D%\gradle-%V%\bin\gradle.bat" wrapper --gradle-version %V% --distribution-type bin --console=plain > build-errors.txt 2>&1
call "%D%\gradle-%V%\bin\gradle.bat" build --console=plain >> build-errors.txt 2>&1
echo EXITCODE=%ERRORLEVEL% >> build-errors.txt
echo DONE %ERRORLEVEL% > build-status.txt
