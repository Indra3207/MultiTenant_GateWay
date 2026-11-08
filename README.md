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
- The Gateway automatically authenticates with `http://localhost:8081/api/auth/login` and synchronizes active routes into its in-memory `RouteRegistry`.
- If API Management is temporarily offline, the Gateway starts gracefully with an empty registry and syncs when API Management becomes available or upon Kafka event notification.

---

## Gateway Capabilities & Verification Demo

The Smart Gateway runtime provides:
- **Dynamic In-Memory Route Registry**: Atomically swapped route snapshots with exact-path priority over wildcard patterns.
- **Resilient Startup & Kafka Sync**: Non-blocking startup with real-time route invalidation via Kafka.
- **Path Rewriting**: Maps `/tenant-slug/resource/**` to `targetBaseUrl + targetPathPrefix + remainingPath`.
- **Query Parameter Preservation**: Forwards query strings transparently to upstream targets.
- **HTTP Method Enforcement**: Returns `405 Method Not Allowed` with `Allow` header when an unsupported HTTP verb is invoked.
- **Hop-by-Hop Header Stripping**: Cleans standard hop-by-hop headers (`Connection`, `Keep-Alive`, `Upgrade`, `Transfer-Encoding`, etc.) before forwarding and replying.
- **Response Timeout**: Enforces per-route `responseTimeoutMs` and returns `504 Gateway Timeout` on slow backends.
- **In-Memory Fixed-Window Rate Limiter**: Enforces `requestsPerMinute` per route and client IP, returning `429 Too Many Requests` with `Retry-After: 60`.
- **Upstream Safety (SSRF Protection)**: Rejects requests targeting cloud metadata (`169.254.169.254`), link-local, and private addresses by default (`gateway.safety.development-mode=true` permits local mock backends during development).

### End-to-End Verification Demo (Windows PowerShell)

Follow this complete step-by-step workflow in PowerShell. The platform begins with only seeded platform administrator credentials (`platform-admin` / `platform123`).

#### 1. Authenticate as Platform Admin
```powershell
$loginBody = @{ username = "platform-admin"; password = "platform123" } | ConvertTo-Json
$platformAuth = Invoke-RestMethod -Uri "http://localhost:8081/api/auth/login" -Method Post -Body $loginBody -ContentType "application/json"
$platformToken = $platformAuth.token
```

#### 2. Provision Organization (FoodFast Inc)
```powershell
$orgBody = @{ name = "FoodFast Inc"; slug = "foodfast" } | ConvertTo-Json
$org = Invoke-RestMethod -Uri "http://localhost:8081/api/platform/organizations" `
    -Method Post -Body $orgBody -ContentType "application/json" `
    -Headers @{ Authorization = "Bearer $platformToken" }
$orgId = $org.id
```

#### 3. Assign Tenant Admin Account
```powershell
$adminBody = @{ username = "foodfast_admin"; password = "Password123!"; enabled = $true } | ConvertTo-Json
$tenantAdmin = Invoke-RestMethod -Uri "http://localhost:8081/api/platform/organizations/$orgId/tenant-admin" `
    -Method Post -Body $adminBody -ContentType "application/json" `
    -Headers @{ Authorization = "Bearer $platformToken" }
```

#### 4. Activate the Organization
```powershell
$statusBody = @{ status = "ACTIVE" } | ConvertTo-Json
$activeOrg = Invoke-RestMethod -Uri "http://localhost:8081/api/platform/organizations/$orgId/status" `
    -Method Patch -Body $statusBody -ContentType "application/json" `
    -Headers @{ Authorization = "Bearer $platformToken" }
```

#### 5. Authenticate as Tenant Admin
```powershell
$tenantLogin = @{ username = "foodfast_admin"; password = "Password123!" } | ConvertTo-Json
$tenantAuth = Invoke-RestMethod -Uri "http://localhost:8081/api/auth/login" -Method Post -Body $tenantLogin -ContentType "application/json"
$tenantToken = $tenantAuth.token
```

#### 6. Configure Gateway Route
```powershell
$routeBody = @{
    routeId = "orders-v1"
    name = "Orders Service"
    pathPattern = "/foodfast/orders/**"
    targetBaseUrl = "https://httpbin.org"
    targetPathPrefix = "/anything/orders"
    requestsPerMinute = 5
    responseTimeoutMs = 3000
    active = $true
    allowedMethods = @("GET", "POST")
} | ConvertTo-Json

$route = Invoke-RestMethod -Uri "http://localhost:8081/api/tenant/routes" `
    -Method Post -Body $routeBody -ContentType "application/json" `
    -Headers @{ Authorization = "Bearer $tenantToken" }
```
*The Control Plane saves version 1 and emits a Kafka event; the Smart Gateway automatically syncs the new active route.*

#### 7. Forward Requests via the Smart Gateway (Port 8080)

##### A. Successful GET with Path Rewriting & Query Parameters
```powershell
curl.exe -i "http://localhost:8080/foodfast/orders/items/42?status=shipped&category=food"
```
*Gateway forwards to `https://httpbin.org/anything/orders/items/42?status=shipped&category=food` and returns `200 OK`.*

##### B. Successful POST with JSON Body
```powershell
curl.exe -i -X POST http://localhost:8080/foodfast/orders/create `
  -H "Content-Type: application/json" `
  -d '{"item": "Burger", "qty": 2}'
```
*Gateway forwards body and returns upstream `200 OK` with mirrored payload.*

##### C. Method Not Allowed (405)
```powershell
# DELETE is not permitted in allowedMethods
curl.exe -i -X DELETE http://localhost:8080/foodfast/orders/42
```
*Returns `HTTP/1.1 405 Method Not Allowed` with `Allow: GET, POST` header.*

##### D. Route Not Found (404)
```powershell
curl.exe -i http://localhost:8080/unknown/service
```
*Returns `HTTP/1.1 404 Not Found` with `{"error": "Not Found", ...}`.*

##### E. Rate Limiting Enforced (429)
```powershell
# Send 7 rapid requests exceeding the 5 req/min quota
1..7 | ForEach-Object {
    curl.exe -s -o NUL -w "%{http_code}`n" http://localhost:8080/foodfast/orders/items
}
```
*Outputs `200` five times, followed by `429` on requests 6 and 7 with `Retry-After: 60`.*

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

