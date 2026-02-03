@echo off
setlocal enabledelayedexpansion
REM PostgreSQL Setup Script for FinanceOS
REM This script checks, installs (if needed), and configures PostgreSQL

cd /d "%~dp0"

echo ========================================
echo FinanceOS PostgreSQL Setup
echo ========================================
echo.

REM Check if PostgreSQL is installed
where psql >nul 2>&1
if !ERRORLEVEL! EQU 0 (
    echo [OK] PostgreSQL is installed
    psql --version
    echo.
    goto :check_service
)

REM Check common PostgreSQL installation paths
set "PG_INSTALLED=0"
if exist "C:\Program Files\PostgreSQL\16\bin\psql.exe" (
    set "PG_PATH=C:\Program Files\PostgreSQL\16\bin"
    set "PG_INSTALLED=1"
) else if exist "C:\Program Files\PostgreSQL\15\bin\psql.exe" (
    set "PG_PATH=C:\Program Files\PostgreSQL\15\bin"
    set "PG_INSTALLED=1"
) else if exist "C:\Program Files\PostgreSQL\14\bin\psql.exe" (
    set "PG_PATH=C:\Program Files\PostgreSQL\14\bin"
    set "PG_INSTALLED=1"
) else if exist "C:\Program Files (x86)\PostgreSQL\16\bin\psql.exe" (
    set "PG_PATH=C:\Program Files (x86)\PostgreSQL\16\bin"
    set "PG_INSTALLED=1"
) else if exist "C:\Program Files (x86)\PostgreSQL\15\bin\psql.exe" (
    set "PG_PATH=C:\Program Files (x86)\PostgreSQL\15\bin"
    set "PG_INSTALLED=1"
) else if exist "C:\Program Files (x86)\PostgreSQL\14\bin\psql.exe" (
    set "PG_PATH=C:\Program Files (x86)\PostgreSQL\14\bin"
    set "PG_INSTALLED=1"
)

if !PG_INSTALLED! EQU 1 (
    echo [OK] PostgreSQL found at !PG_PATH!
    set "PATH=!PG_PATH!;!PATH!"
    echo.
    goto :check_service
)

echo [ERROR] PostgreSQL is not installed or not found in PATH
echo.
echo Please install PostgreSQL:
echo   1. Download from: https://www.postgresql.org/download/windows/
echo   2. Or use: https://www.enterprisedb.com/downloads/postgres-postgresql-downloads
echo   3. During installation:
echo      - Remember the password you set for the 'postgres' user
echo      - Default port: 5432
echo      - Make sure to add PostgreSQL to PATH
echo.
echo After installation, run this script again.
echo.
pause
exit /b 1

:check_service
echo Checking PostgreSQL service...
echo.

REM Try to find PostgreSQL service
set "PG_SERVICE="
for /f "tokens=*" %%s in ('sc query state^= all ^| findstr /i "postgres"') do (
    for /f "tokens=1" %%t in ("%%s") do (
        set "PG_SERVICE=%%t"
    )
)

if "!PG_SERVICE!"=="" (
    REM Try common service names
    sc query "postgresql-x64-16" >nul 2>&1
    if !ERRORLEVEL! EQU 0 set "PG_SERVICE=postgresql-x64-16"
    if "!PG_SERVICE!"=="" (
        sc query "postgresql-x64-15" >nul 2>&1
        if !ERRORLEVEL! EQU 0 set "PG_SERVICE=postgresql-x64-15"
    )
    if "!PG_SERVICE!"=="" (
        sc query "postgresql-x64-14" >nul 2>&1
        if !ERRORLEVEL! EQU 0 set "PG_SERVICE=postgresql-x64-14"
    )
    if "!PG_SERVICE!"=="" (
        sc query "postgresql-x64-13" >nul 2>&1
        if !ERRORLEVEL! EQU 0 set "PG_SERVICE=postgresql-x64-13"
    )
)

if "!PG_SERVICE!"=="" (
    echo [WARNING] Could not find PostgreSQL service name automatically
    echo Please start PostgreSQL manually or check Services (services.msc)
    echo.
) else (
    echo Found service: !PG_SERVICE!
    sc query "!PG_SERVICE!" | findstr /i "RUNNING" >nul 2>&1
    if !ERRORLEVEL! EQU 0 (
        echo [OK] PostgreSQL service is running
        echo.
    ) else (
        echo [INFO] Starting PostgreSQL service...
        net start "!PG_SERVICE!" >nul 2>&1
        if !ERRORLEVEL! EQU 0 (
            echo [OK] PostgreSQL service started successfully
            echo.
            timeout /t 3 /nobreak >nul
        ) else (
            echo [ERROR] Failed to start PostgreSQL service
            echo You may need to run this script as Administrator
            echo Or start it manually from Services (Win+R, type: services.msc)
            echo.
            pause
            exit /b 1
        )
    )
)

:setup_database
echo Setting up database...
echo.

REM Load .env if exists to get database credentials
if exist .env (
    for /f "eol=# tokens=1* delims==" %%a in (.env) do set "%%a=%%b"
)

REM Set defaults
if "%DB_NAME%"=="" set DB_NAME=financeos
if "%DB_USERNAME%"=="" set DB_USERNAME=postgres
if "%DB_PASSWORD%"=="" set DB_PASSWORD=postgres
if "%DB_PORT%"=="" set DB_PORT=5432

echo Database configuration:
echo   Name: %DB_NAME%
echo   User: %DB_USERNAME%
echo   Port: %DB_PORT%
echo.

REM Test connection
echo Testing connection to PostgreSQL...
set "PGPASSWORD=%DB_PASSWORD%"
psql -h localhost -p %DB_PORT% -U %DB_USERNAME% -d postgres -c "SELECT version();" >nul 2>&1
if !ERRORLEVEL! NEQ 0 (
    echo [ERROR] Cannot connect to PostgreSQL
    echo.
    echo Please check:
    echo   1. PostgreSQL service is running
    echo   2. Password is correct (current: %DB_PASSWORD%)
    echo   3. Port is correct (current: %DB_PORT%)
    echo.
    echo You can test manually with:
    echo   psql -U %DB_USERNAME% -d postgres
    echo.
    echo If password is incorrect, update your .env file with:
    echo   DB_PASSWORD=your_postgres_password
    echo.
    pause
    exit /b 1
)

echo [OK] Connected to PostgreSQL successfully
echo.

REM Check if database exists
echo Checking if database '%DB_NAME%' exists...
psql -h localhost -p %DB_PORT% -U %DB_USERNAME% -d postgres -tAc "SELECT 1 FROM pg_database WHERE datname='%DB_NAME%'" | findstr /i "1" >nul 2>&1
if !ERRORLEVEL! EQU 0 (
    echo [OK] Database '%DB_NAME%' already exists
    echo.
) else (
    echo Creating database '%DB_NAME%'...
    psql -h localhost -p %DB_PORT% -U %DB_USERNAME% -d postgres -c "CREATE DATABASE %DB_NAME%;" >nul 2>&1
    if !ERRORLEVEL! EQU 0 (
        echo [OK] Database '%DB_NAME%' created successfully
        echo.
    ) else (
        echo [ERROR] Failed to create database '%DB_NAME%'
        echo.
        pause
        exit /b 1
    )
)

REM Check if user exists (if not using postgres user)
if /i not "%DB_USERNAME%"=="postgres" (
    echo Checking if user '%DB_USERNAME%' exists...
    psql -h localhost -p %DB_PORT% -U postgres -d postgres -tAc "SELECT 1 FROM pg_user WHERE usename='%DB_USERNAME%'" | findstr /i "1" >nul 2>&1
    if !ERRORLEVEL! EQU 0 (
        echo [OK] User '%DB_USERNAME%' already exists
        echo.
    ) else (
        echo Creating user '%DB_USERNAME%'...
        psql -h localhost -p %DB_PORT% -U postgres -d postgres -c "CREATE USER %DB_USERNAME% WITH PASSWORD '%DB_PASSWORD%';" >nul 2>&1
        if !ERRORLEVEL! EQU 0 (
            echo [OK] User '%DB_USERNAME%' created successfully
            echo.
            echo Granting privileges...
            psql -h localhost -p %DB_PORT% -U postgres -d postgres -c "GRANT ALL PRIVILEGES ON DATABASE %DB_NAME% TO %DB_USERNAME%;" >nul 2>&1
            psql -h localhost -p %DB_PORT% -U postgres -d %DB_NAME% -c "GRANT ALL ON SCHEMA public TO %DB_USERNAME%;" >nul 2>&1
        ) else (
            echo [WARNING] Failed to create user '%DB_USERNAME%'
            echo Continuing with postgres user...
            echo.
        )
    )
)

echo ========================================
echo PostgreSQL setup complete!
echo ========================================
echo.
echo Database '%DB_NAME%' is ready to use.
echo.
echo You can now run: run.bat
echo.
pause


