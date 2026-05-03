@echo off
REM Windows ADB Server Startup Script
REM Run this script on Windows (with Android phone connected via USB)
REM This starts ADB server and allows WSL to connect to it

setlocal enabledelayedexpansion

echo ================================================
echo   Windows ADB Server for WSL Connection
echo ================================================
echo.

REM Try to find ADB in common locations
set "ADB_PATH="
set "ADB_LOCATIONS=%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe;%ANDROID_HOME%\platform-tools\adb.exe;%ANDROID_SDK_ROOT%\platform-tools\adb.exe;C:\Android\Sdk\platform-tools\adb.exe"

for %%i in (%ADB_LOCATIONS:;= %) do (
    if exist "%%i" (
        set "ADB_PATH=%%i"
        goto :found_adb
    )
)

REM Try to find adb in PATH
where adb >nul 2>&1
if !errorlevel! equ 0 (
    set "ADB_PATH=adb"
    goto :found_adb
)

echo [ERROR] ADB not found!
echo Please install Android SDK or set ANDROID_HOME environment variable.
echo Common paths:
echo   - %LOCALAPPDATA%\Android\Sdk\platform-tools\
echo   - C:\Android\Sdk\platform-tools\
pause
exit /b 1

:found_adb
echo [INFO] Found ADB: %ADB_PATH%
echo.

REM Get Windows IP address (for WSL to connect to)
echo [INFO] Windows IP Addresses:
for /f "tokens=2 delims=:" %%i in ('ipconfig ^| findstr "IPv4"') do (
    set "ip=%%i"
    set "ip=!ip:~1!"
    echo    !ip!
)
echo.

REM Kill any existing ADB server
echo [INFO] Stopping any existing ADB server...
"%ADB_PATH%" kill-server

REM Start ADB server
echo [INFO] Starting ADB server for WSL access on port 5037...
powershell -NoProfile -ExecutionPolicy Bypass -Command "Start-Process -FilePath '%ADB_PATH%' -ArgumentList '-a','-P','5037','nodaemon','server','start' -WindowStyle Hidden" >nul 2>&1
timeout /t 2 /nobreak >nul

if !errorlevel! neq 0 (
    echo [ERROR] Failed to start ADB server
    pause
    exit /b 1
)

echo [INFO] ADB server started successfully!
echo.

REM Check connected devices
echo [INFO] Connected devices:
"%ADB_PATH%" devices -l

echo.
echo ================================================
echo   SETUP COMPLETE
echo ================================================
echo.
echo Next steps:
echo 1. Note your Windows IP address from above
echo 2. In WSL, run: ./scripts/connect_adb_wsl.sh WINDOWS_IP
echo    (Replace WINDOWS_IP with the IP shown above)
echo    This uses the Windows ADB server on port 5037.
echo.
echo Make sure your Android phone is:
echo   - Connected via USB
echo   - USB debugging is enabled
echo   - Authorized on the phone if prompted
echo.
echo To check connection from WSL, run:
echo   adb devices
echo.
pause
