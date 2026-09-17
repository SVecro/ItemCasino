@echo off
setlocal
cd /d "%~dp0"
set "V=9.2.1"
set "D=%USERPROFILE%\gradle-dist"
title Item Casino - Minecraft dev client
echo Lancement de Minecraft 1.21.11 + NeoForge avec Item Casino...
echo (la premiere fois, compte 1-2 minutes avant la fenetre du jeu)
echo.
call "%D%\gradle-%V%\bin\gradle.bat" runClient --console=plain
echo.
echo === Le jeu s est ferme (code %ERRORLEVEL%). Fenetre gardee ouverte pour lire les erreurs. ===
pause
