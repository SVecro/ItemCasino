@echo off
setlocal
cd /d "%~dp0"
set "V=9.2.1"
set "D=%USERPROFILE%\gradle-dist"
del /q gametest-out.txt 2>nul
echo RUNNING > gametest-status.txt
call "%D%\gradle-%V%\bin\gradle.bat" build runGameTestServer --console=plain > gametest-out.txt 2>&1
echo EXITCODE=%ERRORLEVEL% >> gametest-out.txt
echo DONE %ERRORLEVEL% > gametest-status.txt
