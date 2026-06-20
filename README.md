# FinanceOS Backend

Production-grade personal finance backend built with Java 21 and Spring Boot.

## Features

- **Account Management**: Bank accounts, credit cards, stocks, mutual funds
- **Transaction Tracking**: Manual and Gmail-imported transactions
- **Investment Portfolio**: Buy/sell tracking with FIFO position calculation
- **Field-Level Encryption**: AES-256-GCM for sensitive data
- **Session-Based Auth**: Secure HTTP-only cookies

## Tech Stack

- Java 21
- Spring Boot 3.2
- PostgreSQL
- Flyway migrations
- Spring Security (session-based)
- Spring Data JPA

## Prerequisites

- Java 21+
- PostgreSQL 14+
- Maven 3.9+

## Quick Start

### 1. Database Setup

```bash
# Create PostgreSQL database
createdb financeos

# Or using psql
psql -c "CREATE DATABASE financeos;"
```

### 2. Generate Encryption Key

```bash
# Generate a 32-byte base64-encoded key for AES-256
openssl rand -base64 32
```

### 3. Configure Environment

```bash
export DB_HOST=localhost
export DB_PORT=5432
export DB_NAME=financeos
export DB_USERNAME=your_username
export DB_PASSWORD=your_password
export ENCRYPTION_KEY=your-32-byte-base64-key
export CORS_ORIGINS=http://localhost:3001
```

### 4. Run the Application

```bash
./mvnw spring-boot:run
```

The API will be available at `http://localhost:8080`.

## Running Against Oracle Locally (prod parity)

Production runs on **Oracle**, while local defaults to **PostgreSQL**. The two
schemas differ in real ways (`RAW(16)` vs `uuid`, `CLOB` vs `JSONB`,
`NUMBER(1)` vs `BOOLEAN`, `VARCHAR2` vs `TEXT`), so to catch Oracle-specific
issues before they reach prod, run a local Oracle that mirrors it.

Requires Docker. The `oracle-local` Spring profile points at a local Oracle
23ai Free container (this is separate from the `prod` profile, which connects to
Oracle Cloud via a TNS wallet).

```bash
# 1. Start a local Oracle 23ai Free container (multi-arch; works on Apple Silicon)
docker compose -f docker-compose.oracle.yml up -d

# 2. Wait until it reports healthy
docker compose -f docker-compose.oracle.yml ps

# 3. Run the app against Oracle (uses the oracle-local profile + db/migration/oracle)
./run-oracle.sh
```

The container creates an app user `financeos/financeos` in PDB `FREEPDB1` on
`localhost:1521`. Flyway applies the Oracle migration set on first boot.

```bash
docker compose -f docker-compose.oracle.yml down      # stop (data persists in a volume)
docker compose -f docker-compose.oracle.yml down -v   # stop and wipe the database
```

> Note: the default `application.yml` scopes Flyway to `classpath:db/migration/postgresql`
> so the Postgres and Oracle migration sets don't collide.

## Accounts & Seeding

A migration (`V2`) seeds an `admin@financeos.local` user, but its BCrypt hash does
**not** correspond to the literal `changeme` documented historically — so logging in
as admin won't work out of the box. The simplest way to get a working account is the
public signup endpoint:

```bash
curl -X POST http://localhost:8080/api/v1/auth/signup \
  -H 'Content-Type: application/json' \
  -d '{"email":"you@example.com","password":"yourpassword"}'
```

### Seed sample data

With the app running (either DB), populate a demo user + sample accounts,
transactions, and investments via the API:

```bash
./seed.sh
# logs in as demo@financeos.local / demo1234 and creates sample data
```

## API Endpoints

### Authentication
| Method | Endpoint | Description |
|--------|----------|-------------|
| POST | `/api/v1/auth/login` | Login with email/password |
| POST | `/api/v1/auth/logout` | Logout and invalidate session |
| GET | `/api/v1/auth/me` | Get current user |

### Accounts
| Method | Endpoint | Description |
|--------|----------|-------------|
| POST | `/api/v1/accounts` | Create account |
| GET | `/api/v1/accounts` | List all accounts |
| GET | `/api/v1/accounts/{id}` | Get account by ID |
| POST | `/api/v1/accounts/{id}/bank-details` | Add bank details |
| POST | `/api/v1/accounts/{id}/credit-card-details` | Add credit card details |
| POST | `/api/v1/accounts/{id}/stock-details` | Add stock details |
| POST | `/api/v1/accounts/{id}/mutual-fund-details` | Add mutual fund details |

### Transactions
| Method | Endpoint | Description |
|--------|----------|-------------|
| POST | `/api/v1/transactions` | Create transaction |
| GET | `/api/v1/transactions` | List transactions (paginated) |

### Investments
| Method | Endpoint | Description |
|--------|----------|-------------|
| POST | `/api/v1/investments/transactions` | Record buy/sell |
| GET | `/api/v1/investments/transactions` | List investment transactions |
| GET | `/api/v1/investments/position` | Get FIFO-calculated positions |

### Skeleton Endpoints (Not Implemented)
| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | `/api/v1/gmail/oauth/start` | Start Gmail OAuth |
| GET | `/api/v1/gmail/oauth/callback` | Gmail OAuth callback |
| POST | `/api/v1/gmail/sync` | Sync transactions from Gmail |
| POST | `/api/v1/rules` | Create categorization rule |
| POST | `/api/v1/rules/apply` | Apply rules to transactions |
| GET | `/api/v1/dashboard/summary` | Get dashboard summary |

## Security

### Zero-Trust Design
- All endpoints (except login) require authentication
- Session-based authentication with HTTP-only cookies
- CSRF protection disabled (API-only usage)
- CORS configured for frontend origin

### Field-Level Encryption
The `statement_password` field in credit card details is encrypted using AES-256-GCM.

To encrypt a custom field, use the `@Convert` annotation:

```java
@Convert(converter = EncryptedStringConverter.class)
private String sensitiveField;
```

## Database Schema

### Account Types
- `bank_account`
- `credit_card`
- `stock`
- `mutual_fund`
- `generic`

### Financial Positions
- `asset`
- `liability`

### Transaction Sources
- `gmail`
- `manual`

### Investment Transaction Types
- `buy`
- `sell`

## Development

### Run Tests
```bash
./mvnw test
```

### Build JAR
```bash
./mvnw clean package -DskipTests
java -jar target/backend-1.0.0.jar
```

### Docker (Optional)

```dockerfile
FROM eclipse-temurin:21-jre
COPY target/backend-1.0.0.jar app.jar
ENTRYPOINT ["java", "-jar", "/app.jar"]
```

## Configuration Reference

| Property | Environment Variable | Default                  | Description |
|----------|---------------------|--------------------------|-------------|
| `spring.datasource.url` | `DB_HOST`, `DB_PORT`, `DB_NAME` | localhost:5432/financeos | Database connection |
| `spring.datasource.username` | `DB_USERNAME` | financeos                | Database username |
| `spring.datasource.password` | `DB_PASSWORD` | financeos                | Database password |
| `app.encryption.key` | `ENCRYPTION_KEY` | -                        | AES-256 encryption key (base64) |
| `app.cors.allowed-origins` | `CORS_ORIGINS` | http://localhost:3001    | CORS allowed origins |
| `server.port` | `SERVER_PORT` | 8080                     | Server port |

## License

Private - Personal Use Only

