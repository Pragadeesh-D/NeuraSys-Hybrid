@echo off
setlocal enabledelayedexpansion

REM ================================================
REM NeuraSys - Complete Hybrid Build Script (Windows)
REM Version: 1.0.0
REM ================================================

echo.
echo ================================================
echo    NeuraSys Hybrid Build System (Windows)
echo    Version 1.0.0
echo ================================================
echo.

REM Change to project root directory
cd /d "%~dp0\.."

REM ================================================
REM STEP 1: Check Prerequisites
REM ================================================
echo [1/5] Checking prerequisites...
echo.

REM Check Java
java -version >nul 2>&1
if %errorlevel% neq 0 (
    echo [ERROR] Java JDK not found! Please install Java 17+.
    pause
    exit /b 1
)
echo ✓ Java found

REM Check Maven
call mvn --version >nul 2>&1
if %errorlevel% neq 0 (
    echo [ERROR] Maven not found! Please install Maven 3.8+.
    pause
    exit /b 1
)
echo ✓ Maven found

REM Check GCC (for C monitor)
gcc --version >nul 2>&1
if %errorlevel% neq 0 (
    echo [WARNING] GCC not found! C monitor will NOT be built.
    echo           Install MinGW-w64 to compile native monitor.
    set BUILD_C=0
) else (
    echo ✓ GCC found
    set BUILD_C=1
)

REM Check MySQL
mysql --version >nul 2>&1
if %errorlevel% neq 0 (
    echo [WARNING] MySQL not found! Make sure MySQL is installed and running before starting the app.
) else (
    echo ✓ MySQL found
)

echo.
echo ================================================
echo   All prerequisites checked
echo ================================================
echo.

REM ================================================
REM STEP 2: Create Required Directories
REM ================================================
echo [2/5] Creating directories...
echo.

if not exist "lib\windows" mkdir "lib\windows"
if not exist "lib\linux" mkdir "lib\linux"
if not exist "logs" mkdir "logs"
if not exist "target" mkdir "target"
if not exist "dist" mkdir "dist"

echo ✓ Directories created
echo.

REM ================================================
REM STEP 3: Build C Native Monitor (Windows)
REM ================================================
echo [3/5] Building C Native Monitor (Windows)...
echo.

if %BUILD_C%==1 (
    pushd 02_C_Source\Windows

    echo Compiling monitor.c...
    gcc -shared -o ..\..\lib\windows\neurasys_monitor.dll monitor.c -lpthread -O2 -Wall -Wextra

    if %errorlevel% neq 0 (
        echo [ERROR] Failed to compile C monitor.
        popd
        pause
        exit /b 1
    )

    echo ✓ C monitor built successfully
    popd
) else (
    echo ⊘ C monitor build skipped (GCC not available)
)

echo.

REM ================================================
REM STEP 4: Build Java Application with Maven
REM ================================================
echo [4/5] Building Java Application...
echo.

echo Running Maven clean package...
call mvn clean package -DskipTests

if %errorlevel% neq 0 (
    echo [ERROR] Maven build failed!
    pause
    exit /b 1
)

echo ✓ Java application built successfully
echo.

REM ================================================
REM STEP 5: Create Distribution Package
REM ================================================
echo [5/5] Creating distribution package...
echo.

REM Copy JAR
if exist "target\neurasys-desktop-1.0.0.jar" (
    copy /Y "target\neurasys-desktop-1.0.0.jar" "dist\neurasys.jar" >nul
    echo ✓ JAR copied to dist\
) else (
    echo [ERROR] JAR not found in target.
    pause
    exit /b 1
)

REM Copy native libraries
if exist "lib\windows\neurasys_monitor.dll" (
    xcopy /Y /I "lib\windows" "dist\lib\windows\" >nul 2>&1
    echo ✓ Native DLL copied
)

REM Copy configuration files
xcopy /Y /I "05_Configuration" "dist\config\" >nul 2>&1
echo ✓ Config files copied

REM Create logs directory
if not exist "dist\logs" mkdir "dist\logs"
echo ✓ Logs directory created

REM Create run script
echo @echo off > dist\run.bat
echo echo Starting NeuraSys... >> dist\run.bat
echo java -jar neurasys.jar >> dist\run.bat
echo pause >> dist\run.bat
echo ✓ Run script created

echo.
echo ================================================
echo   BUILD COMPLETE!
echo ================================================
echo.
echo Distribution created in: dist\
echo.
echo To run the application:
echo   cd dist
echo   run.bat
echo.
pause
