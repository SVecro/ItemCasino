@echo off
setlocal
cd /d "%~dp0"
del /q gametest-out.txt 2>nul
echo RUNNING > gametest-status.txt
call gradlew.bat build runGameTestServer --console=plain > gametest-out.txt 2>&1
echo EXITCODE=%ERRORLEVEL% >> gametest-out.txt
echo DONE %ERRORLEVEL% > gametest-status.txt
