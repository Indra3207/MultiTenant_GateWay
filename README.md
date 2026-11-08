# Gatekeeper — Multi-Tenant API Gateway Platform

Gatekeeper is a multi-tenant API Management and Gateway platform built with Spring Boot, Java 21, MySQL 8, and Apache Kafka.

---

## Architecture Overview

Gatekeeper follows a split **Control Plane** and **Data Plane** architecture:

- **API Management Service (`api-management-service`)** — Control Plane (Port 8081):
  - Multi-tenant organization and route management.
  - Role-Based Access Control (`PLATFORM_ADMIN`, `TENANT_ADMIN`, `GATEWAY_SERVICE`).
  - Route versioning snapshots and control-plane audit trails.
  - MySQL persistence with Flyway migrations.
  - Publishes route configuration change events to Kafka.
  - Embedded browser dashboard (`/index.html`).
- **Smart Gateway Service (`smart-gateway-service`)** — Data Plane (Port 8080):
  - Fetches active route definitions from the Control Plane via REST.
  - Subscribes to Kafka route invalidation notifications.
  - Forwarding proxy and routing runtime.
- **Infrastructure Services (Docker Compose)**:
  - **MySQL 8** (Port 3306): Stores organizations, users, roles, routes, versions, and audit logs.
  - **Apache Kafka** (Port 9092): Broker running in KRaft mode for real-time configuration sync.

> **Execution Model**: Infrastructure components (MySQL, Kafka) run in Docker Compose. The Spring Boot application services run directly on the local host machine during development.

---

## Prerequisites

1. **Java Development Kit (JDK) 21** or higher.
2. **Apache Maven 3.9+** (or use the included `mvnw` wrappers in each service directory).
3. **Docker** and **Docker Compose v2+** (Docker Desktop or Docker Engine).
4. **PowerShell 7+** or Windows PowerShell 5.1.

---

## Quickstart Guide

### 1. Configure Local Environment

Copy `.env.example` to `.env` at the repository root:

```powershell
Copy-Item .env.example .env
```

Open `.env` in an editor and set your local credentials:
- Set `MYSQL_ROOT_PASSWORD` and `MYSQL_PASSWORD`.
- Set `GATEKEEPER_DB_PASSWORD` to match your MySQL password.
- Generate and set `GATEKEEPER_JWT_SECRET` (at least 32 characters / 256 bits).
- Verify `GATEKEEPER_GATEWAY_USERNAME` and `GATEKEEPER_GATEWAY_PASSWORD` (defaults match the seeded account: `gateway-service` / `gateway123`).

> **Note**: `.env` is ignored by Git and should never be committed.

---

### 2. Start Infrastructure Containers

Start MySQL and Kafka in the background using Docker Compose:

```powershell
docker compose up -d
```

#### Check MySQL & Kafka Readiness

Verify container status:
```powershell
docker compose ps
```

Check MySQL health directly:
```powershell
docker compose exec mysql mysqladmin ping -h localhost -uroot -p
```
Wait until MySQL reports `mysqld is alive` and the container status shows `(healthy)`.

Check Kafka logs:
```powershell
docker compose logs kafka --tail 20
```

---

### 3. Load Environment Variables in PowerShell

Because the Spring Boot applications run directly on your host machine, load the `.env` variables into your current PowerShell session:

```powershell
Get-Content .env | Where-Object { $_ -match '^\s*[^#\s]+=.*$' } | ForEach-Object {
    $name, $value = $_.Trim().Split('=', 2)
    [System.Environment]::SetEnvironmentVariable($name.Trim(), $value.Trim(), "Process")
}
```

Verify that the variables are loaded (without printing secrets):
```powershell
Get-ChildItem env:GATEKEEPER_* | Select-Object Name
```

---

### 4. Run API Management Service (Control Plane)

Open a terminal session, ensure the environment variables are loaded, and run:

```powershell
cd api-management-service
./mvnw spring-boot:run
```

Once running:
- **Base URL**: `http://localhost:8081`
- **Health Check**: `http://localhost:8081/health`
- **Management Dashboard**: Open `http://localhost:8081/` in your browser.
- **Default Platform Admin**: username `platform-admin`, password (seeded via migration `V3__seed_platform_accounts.sql`).

---

### 5. Run Smart Gateway Service (Data Plane)

Open a second terminal session, ensure the environment variables are loaded, and run:

```powershell
cd smart-gateway-service
./mvnw spring-boot:run
```

Once running:
- **Base URL**: `http://localhost:8080`
- **Health Check**: `http://localhost:8080/health`
- The Gateway automatically authenticates with `http://localhost:8081/api/auth/login` and synchronizes active routes.

---

## Running Tests

Each application is an independent Maven project. Run tests directly inside each project folder:

### Test API Management Service
```powershell
cd api-management-service
./mvnw test
```
> Uses an in-memory H2 database (in MySQL compatibility mode) with pre-configured test credentials in `src/test/resources/application.properties`.

### Test Smart Gateway Service
```powershell
cd smart-gateway-service
./mvnw test
```

---

## Stopping Infrastructure

To stop the background Docker containers:

```powershell
docker compose down
```

To stop containers and reset the persistent MySQL database volume:
```powershell
docker compose down -v
```

