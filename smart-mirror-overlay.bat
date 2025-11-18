@echo off
setlocal

REM Go to the folder where this script lives (repo root)
cd /d "%~dp0"

REM ---------- Start MagicMirror ----------
start "MagicMirror" cmd /k "cd /d magicmirror && npm run start:windows"

REM Give MagicMirror a few seconds to come up (optional)
timeout /t 5 /nobreak >nul

REM ---------- Start Java overlay ----------
REM Run Main from the 'mirror' package, classes live under .\mirror
start "Overlay" cmd /k "cd /d %~dp0 && java -cp . mirror.Main"

endlocal
