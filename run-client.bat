@echo off
setlocal
cd /d "%~dp0"
title Item Casino - Minecraft dev client
echo Starting Minecraft 1.21.11 + NeoForge with Item Casino...
echo (the first time, allow a minute or two before the game window appears)
echo.
call gradlew.bat runClient --console=plain
echo.
echo === The game has closed (exit code %ERRORLEVEL%). This window stays open so errors can be read. ===
pause
