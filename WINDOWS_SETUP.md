# Windows Setup Guide for FinanceOS Backend

This guide will help you set up and run the FinanceOS backend on Windows.

## Prerequisites Installation

### 1. Install Java 21

**Option A: Eclipse Temurin (Recommended)**
1. Visit https://adoptium.net/temurin/releases/?version=21
2. Download the Windows x64 installer (`.msi`)
3. Run the installer and follow the prompts
4. **Important**: Check "Add to PATH" during installation

**Option B: Oracle JDK**
1. Visit https://www.oracle.com/java/technologies/downloads/#java21
2. Download the Windows x64 installer
3. Run the installer and follow the prompts
4. Add Java to PATH manually (see below)

**Verify Installation:**
Open a new Command Prompt or PowerShell and run:
```cmd
java -version
```

You should see something like:
```
openjdk version "21.0.x" ...
```

### 2. Set JAVA_HOME (If Java is not in PATH)

If `java -version` doesn't work, you need to set JAVA_HOME:

1. Find your Java installation directory (common locations):
   - `C:\Program Files\Eclipse Adoptium\jdk-21.x.x-hotspot`
   - `C:\Program Files\Java\jdk-21`
   - `C:\Program Files\Eclipse Foundation\jdk-21.x.x-hotspot`

2. Set JAVA_HOME environment variable:

   **Using PowerShell (Current Session):**
   ```powershell
   $env:JAVA_HOME="C:\Program Files\Eclipse Adoptium\jdk-21.x.x-hotspot"
   ```

   **Using System Settings (Permanent):**
   1. Press `Win + X` and select "System"
   2. Click "Advanced system settings"
   3. Click "Environment Variables"
   4. Under "User variables" or "System variables", click "New"
   5. Variable name: `JAVA_HOME`
   6. Variable value: Your Java installation path (e.g., `C:\Program Files\Eclipse Adoptium\jdk-21.x.x-hotspot`)
   7. Click "OK" to save
   8. Also add `%JAVA_HOME%\bin` to your PATH variable

### 3. Install PostgreSQL 14+

1. Visit https://www.postgresql.org/download/windows/
2. Download the PostgreSQL installer
3. Run the installer:
   - Remember the password you set for the `postgres` user
   - Default port is 5432 (keep this unless you have a conflict)
4. During installation, make sure PostgreSQL is added to PATH

**Verify Installation:**
```cmd
psql --version
```

### 4. Create Database

Open Command Prompt or PowerShell and run:

```cmd
psql -U postgres -c "CREATE DATABASE financeos;"
```

You'll be prompted for the password you set during PostgreSQL installation.

**Alternative:** If `psql` is not in PATH, use pgAdmin:
1. Open pgAdmin
2. Connect to your PostgreSQL server
3. Right-click "Databases" → "Create" → "Database"
4. Name it `financeos` and click "Save"

## Configuration

### 1. Generate Encryption Key

**Using PowerShell:**
```powershell
[Convert]::ToBase64String((1..32 | ForEach-Object { Get-Random -Minimum 0 -Maximum 256 }))
```

**Using OpenSSL (if installed):**
```cmd
openssl rand -base64 32
```

Copy the generated key - you'll need it in the next step.

### 2. Create .env File

Create a file named `.env` in the project root directory with the following content:

```env
DB_HOST=localhost
DB_PORT=5432
DB_NAME=financeos
DB_USERNAME=postgres
DB_PASSWORD=your_postgres_password_here
ENCRYPTION_KEY=your_generated_encryption_key_here
CORS_ORIGINS=http://localhost:3000
```

Replace:
- `your_postgres_password_here` with your PostgreSQL password
- `your_generated_encryption_key_here` with the key you generated in step 1

## Running the Application

### Option 1: Using the Batch File (Recommended)

```cmd
run.bat
```

### Option 2: Using PowerShell Script

```powershell
.\run.ps1
```

### Option 3: Using Maven Wrapper Directly

```cmd
.\mvnw.cmd spring-boot:run
```

The application will start on `http://localhost:8080`

## Troubleshooting

### "java is not recognized"

**Solution 1:** Add Java to PATH
1. Find your Java installation directory
2. Add `\bin` folder to your system PATH (see JAVA_HOME setup above)

**Solution 2:** Set JAVA_HOME
```cmd
set JAVA_HOME=C:\Program Files\Eclipse Adoptium\jdk-21.x.x-hotspot
```

### "psql is not recognized"

PostgreSQL is not in your PATH. Either:
- Add PostgreSQL `bin` directory to PATH, OR
- Use pgAdmin to create the database

### "Connection refused" or Database Errors

1. Make sure PostgreSQL is running:
   - Open Services (`Win + R`, type `services.msc`)
   - Find "postgresql-x64-XX" service
   - Make sure it's "Running"

2. Verify database credentials in your `.env` file

3. Test connection:
   ```cmd
   psql -U postgres -d financeos
   ```

### Port 8080 Already in Use

Change the port in your `.env` file:
```env
SERVER_PORT=8081
```

Or stop the application using port 8080.

## Default Login Credentials

After first run, you can login with:
- **Email:** admin@financeos.local
- **Password:** changeme

**⚠️ Change this password immediately after first login!**

