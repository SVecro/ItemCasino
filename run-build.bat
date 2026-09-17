@echo off
setlocal
cd /d "%~dp0"
set "V=9.2.1"
set "D=%USERPROFILE%\gradle-dist"
del /q build-errors.txt 2>nul
echo RUNNING > build-status.txt
call "%D%\gradle-%V%\bin\gradle.bat" build --console=plain > build-errors.txt 2>&1
echo EXITCODE=%ERRORLEVEL% >> build-errors.txt
echo DONE %ERRORLEVEL% > build-status.txt
