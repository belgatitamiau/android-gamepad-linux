@echo off
title Gamepad Bridge
cd /d "%~dp0"

echo ========================================
echo   Gamepad Bridge
echo ========================================
echo.

:check_python
python --version >nul 2>&1
if %errorlevel% neq 0 (
    echo Python not found. Install it from https://www.python.org/downloads/
    echo Make sure to check "Add Python to PATH" during installation.
    echo.
    echo Press any key to open the download page...
    pause >nul
    start https://www.python.org/downloads/
    echo Press any key after installing Python...
    pause >nul
    goto check_python
)

echo Installing dependencies...
python -m pip install --upgrade pip -q
python -m pip install "qrcode[pil]" vgamepad -q

if %errorlevel% neq 0 (
    echo Failed to install dependencies. Try running as Administrator.
    pause
    exit /b 1
)
echo Dependencies installed.
echo.

python -u server.py

if %errorlevel% neq 0 (
    echo.
    echo Server closed with error code %errorlevel%.
    pause
)
