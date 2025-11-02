@echo off
echo Cleaning NeuraSys build artifacts...
echo.

cd /d "%~dp0\.."

REM Remove Maven build directory
if exist "target" (
    echo Removing target\...
    rmdir /s /q "target"
)

REM Remove native DLLs from resources
if exist "lib\windows\neurasys_monitor.dll" (
    echo Removing lib\windows\neurasys_monitor.dll...
    del /f /q "lib\windows\neurasys_monitor.dll"
)

REM Remove distribution folder
if exist "dist" (
    echo Removing dist\...
    rmdir /s /q "dist"
)

REM Remove logs
if exist "logs" (
    echo Removing logs\...
    rmdir /s /q "logs"
)

echo.
echo ✓ Clean complete!
pause
