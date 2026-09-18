@echo off
rem ---------------------------------------------------------------------------
rem  Item Casino - one round trip, from source to a playable jar.
rem
rem  Builds, runs the in-world game tests, and copies the jar into releases\
rem  with its SHA-256 -- but ONLY if both the build and the tests came back
rem  clean. A release that ships a failing test is worse than no release, so
rem  the copy is guarded rather than unconditional.
rem
rem  Reads the version from gradle.properties, so the only thing to change
rem  before a release is mod_version there.
rem
rem  Leaves release-status.txt (the summary) and release-out.txt (the whole log).
rem ---------------------------------------------------------------------------
setlocal enabledelayedexpansion
cd /d "%~dp0"
set "V=9.2.1"
set "D=%USERPROFILE%\gradle-dist"

rem --- the version to release, straight from gradle.properties ---------------
set "MODVER="
for /f "usebackq tokens=1,2 delims==" %%a in ("gradle.properties") do (
    if "%%a"=="mod_version" set "MODVER=%%b"
)
if not defined MODVER (
    echo FAILED no mod_version in gradle.properties> release-status.txt
    echo Could not read mod_version from gradle.properties.
    pause
    exit /b 1
)
rem Trim a trailing space, which a line written as "mod_version=0.3.0 " would leave.
if "!MODVER:~-1!"==" " set "MODVER=!MODVER:~0,-1!"

echo Releasing Item Casino !MODVER!
echo   building and running the game tests, this takes a minute...
echo RUNNING !MODVER!> release-status.txt
del /q release-out.txt 2>nul

call "%D%\gradle-%V%\bin\gradle.bat" build runGameTestServer --console=plain > release-out.txt 2>&1
set "RC=!ERRORLEVEL!"
echo EXITCODE=!RC!>> release-out.txt

if not "!RC!"=="0" (
    echo   BUILD FAILED ^(exit code !RC!^) - see release-out.txt
    echo FAILED build exit !RC!> release-status.txt
    pause
    exit /b 1
)

rem --- the tests have to say so themselves -----------------------------------
set "TESTLINE="
for /f "delims=" %%t in ('findstr /C:"required tests" release-out.txt') do set "TESTLINE=%%t"
echo !TESTLINE! | findstr /C:"passed" >nul
if errorlevel 1 (
    echo   GAME TESTS DID NOT PASS - see release-out.txt
    echo FAILED tests> release-status.txt
    if defined TESTLINE echo !TESTLINE!>> release-status.txt
    pause
    exit /b 1
)

rem --- the jar itself --------------------------------------------------------
set "JAR=build\libs\itemcasino-!MODVER!.jar"
if not exist "!JAR!" (
    echo   NO JAR AT !JAR! - is mod_version right?
    echo FAILED no jar !JAR!> release-status.txt
    pause
    exit /b 1
)

if not exist releases mkdir releases
set "OUT=releases\itemcasino-!MODVER!.jar"
copy /y "!JAR!" "!OUT!" >nul
if errorlevel 1 (
    echo   COULD NOT COPY THE JAR into releases\
    echo FAILED copy> release-status.txt
    pause
    exit /b 1
)

rem certutil prints a header line, the hash, then a footer; skip=1 takes the hash.
set "HASH="
for /f "skip=1 delims=" %%h in ('certutil -hashfile "!OUT!" SHA256') do (
    if not defined HASH set "HASH=%%h"
)
rem Older certutil spaces the hex out in pairs; sha256sum never does.
set "HASH=!HASH: =!"
echo !HASH!  itemcasino-!MODVER!.jar> "!OUT!.sha256"

echo DONE 0> release-status.txt
echo VERSION=!MODVER!>> release-status.txt
echo JAR=!OUT!>> release-status.txt
echo SHA256=!HASH!>> release-status.txt
if defined TESTLINE echo TESTS=!TESTLINE!>> release-status.txt

echo.
echo   Released !MODVER!
echo   !OUT!
echo   sha256 !HASH!
if defined TESTLINE echo   !TESTLINE!
echo.
echo   Drop that jar into the mods folder of a NeoForge 21.11.42 / MC 1.21.11 instance.
echo.
pause
