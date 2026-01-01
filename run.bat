@echo off
setlocal enabledelayedexpansion
REM FinanceOS Backend Runner for Windows
REM Usage: run.bat

cd /d "%~dp0"

REM Check for Java
where java >nul 2>&1
if !ERRORLEVEL! NEQ 0 (
    if "!JAVA_HOME!"=="" (
        echo ERROR: Java is not installed or not in PATH!
        echo.
        echo Please install Java 21 from:
        echo   https://adoptium.net/temurin/releases/?version=21
        echo.
        echo Or set JAVA_HOME: set JAVA_HOME=C:\Program Files\Java\jdk-21
        echo.
        exit /b 1
    )
    if exist "!JAVA_HOME!\bin\java.exe" (
        set "PATH=!JAVA_HOME!\bin;!PATH!"
    ) else (
        echo ERROR: Java not found at !JAVA_HOME!\bin\java.exe
        exit /b 1
    )
)

REM Load .env if exists
if exist .env (
    for /f "eol=# tokens=1* delims==" %%a in (.env) do set "%%a=%%b"
)

REM Set defaults if not already set
if "%DB_HOST%"=="" set DB_HOST=localhost
if "%DB_PORT%"=="" set DB_PORT=5432
if "%DB_NAME%"=="" set DB_NAME=financeos
if "%DB_USERNAME%"=="" set DB_USERNAME=%USERNAME%
if "%DB_PASSWORD%"=="" set DB_PASSWORD=
if "%CORS_ORIGINS%"=="" set CORS_ORIGINS=http://localhost:3000

REM Check encryption key
if "%ENCRYPTION_KEY%"=="" (
    echo WARNING: ENCRYPTION_KEY not set!
    echo.
    echo Generate one with PowerShell:
    echo   [Convert]::ToBase64String((1..32 ^| ForEach-Object { Get-Random -Minimum 0 -Maximum 256 }))
    echo.
    echo Then add to .env file: ENCRYPTION_KEY=your_key_here
    echo.
    exit /b 1
)

echo Starting FinanceOS Backend...
echo    Database: %DB_NAME%@%DB_HOST%:%DB_PORT%
echo    CORS: %CORS_ORIGINS%
echo.

call mvnw.cmd spring-boot:run
