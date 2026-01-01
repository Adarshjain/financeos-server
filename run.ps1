# FinanceOS Backend Runner for Windows
# Usage: .\run.ps1

# Change to script directory
Set-Location $PSScriptRoot

# Check for Java
$javaFound = $false
if (Get-Command java -ErrorAction SilentlyContinue) {
    $javaFound = $true
} elseif ($env:JAVA_HOME) {
    $javaPath = Join-Path $env:JAVA_HOME "bin\java.exe"
    if (Test-Path $javaPath) {
        $env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
        $javaFound = $true
    }
}

if (-not $javaFound) {
    Write-Host "ERROR: Java is not installed or not in PATH!" -ForegroundColor Red
    Write-Host ""
    Write-Host "Please install Java 21 from one of these sources:" -ForegroundColor Yellow
    Write-Host "  1. Eclipse Temurin: https://adoptium.net/temurin/releases/?version=21" -ForegroundColor Cyan
    Write-Host "  2. Oracle JDK: https://www.oracle.com/java/technologies/downloads/#java21" -ForegroundColor Cyan
    Write-Host ""
    Write-Host "After installation, either:" -ForegroundColor Yellow
    Write-Host "  - Add Java to your system PATH, OR" -ForegroundColor Cyan
    Write-Host "  - Set JAVA_HOME environment variable:" -ForegroundColor Cyan
    Write-Host "    `$env:JAVA_HOME='C:\Program Files\Java\jdk-21'" -ForegroundColor Cyan
    Write-Host ""
    exit 1
}

# Load .env if exists
if (Test-Path .env) {
    Get-Content .env | ForEach-Object {
        if ($_ -match '^\s*([^#][^=]+)=(.*)$') {
            $name = $matches[1].Trim()
            $value = $matches[2].Trim()
            [Environment]::SetEnvironmentVariable($name, $value, "Process")
        }
    }
}

# Defaults
$env:DB_HOST = if ($env:DB_HOST) { $env:DB_HOST } else { "localhost" }
$env:DB_PORT = if ($env:DB_PORT) { $env:DB_PORT } else { "5432" }
$env:DB_NAME = if ($env:DB_NAME) { $env:DB_NAME } else { "financeos" }
$env:DB_USERNAME = if ($env:DB_USERNAME) { $env:DB_USERNAME } else { $env:USERNAME }
$env:DB_PASSWORD = if ($env:DB_PASSWORD) { $env:DB_PASSWORD } else { "" }
$env:CORS_ORIGINS = if ($env:CORS_ORIGINS) { $env:CORS_ORIGINS } else { "http://localhost:3000" }

# Check encryption key
if (-not $env:ENCRYPTION_KEY) {
    Write-Host "⚠️  ENCRYPTION_KEY not set!" -ForegroundColor Yellow
    Write-Host ""
    Write-Host "Generate one with:" -ForegroundColor Yellow
    Write-Host "  openssl rand -base64 32" -ForegroundColor Cyan
    Write-Host ""
    Write-Host "Or using PowerShell:" -ForegroundColor Yellow
    Write-Host "  [Convert]::ToBase64String((1..32 | ForEach-Object { Get-Random -Minimum 0 -Maximum 256 }))" -ForegroundColor Cyan
    Write-Host ""
    Write-Host "Then either:" -ForegroundColor Yellow
    Write-Host "  1. Add to .env file: ENCRYPTION_KEY=your_key_here" -ForegroundColor Cyan
    Write-Host "  2. Or set it: `$env:ENCRYPTION_KEY='your_key_here'" -ForegroundColor Cyan
    Write-Host ""
    exit 1
}

Write-Host "🚀 Starting FinanceOS Backend..." -ForegroundColor Green
Write-Host "   Database: $($env:DB_NAME)@$($env:DB_HOST):$($env:DB_PORT)" -ForegroundColor Cyan
Write-Host "   CORS: $($env:CORS_ORIGINS)" -ForegroundColor Cyan
Write-Host ""

.\mvnw.cmd spring-boot:run

