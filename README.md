# Gatekeeper — Multi-Tenant API Gateway Platform

Gatekeeper is a multi-tenant API Management and Gateway platform built with **Java 21** and **Spring Boot**. It cleanly decouples administrative control-plane responsibilities (tenant provisioning, user authentication, route configuration versioning, and compliance audit logging) from high-throughput data-plane responsibilities (dynamic route matching, path rewriting, query parameter preservation, rate limiting, SSRF protection, and reverse proxy forwarding).

Configuration synchronization between the Control Plane and Data Plane uses **authenticated scheduled REST configuration polling**, eliminating message broker infrastructure (Apache Kafka) while ensuring single-source-of-truth consistency via MySQL 8.

---

## Architecture Overview

Gatekeeper follows a clean separation between the **Control Plane** and the **Data Plane**:

```
+-----------------------------------------------------------------------------------+
|                                  CONTROL PLANE                                    |
|                       API Management Service (Port 8081)                         |
|                                                                                   |
|  - Platform & Tenant Admin Authentication (JWT HMAC-SHA256)                      |
|  - Organization Provisioning & Tenant Isolation                                  |
|  - Route Configuration Versioning Snapshots                                      |
|  - Control Plane Audit Logging                                                    |
|  - Seeded Gateway Service Account (GATEWAY_SERVICE role)                         |
|  - Authenticated Configuration Endpoint: GET /api/gateway/routes                 |
|  - Embedded Management Dashboard UI (/index.html)                                 |
+-----------------------------------------------------------------------------------+
                                   |                 ^
             Reads & Persists      |                 | Authenticated REST Polling
             Routes, Orgs, Audits  |                 | (Bearer JWT, 10s Interval)
                                   v                 |
                        +----------------------+     |
                        |       MySQL 8        |     |
                        |     (Port 3306)      |     |
                        | Flyway V1-V3 Schema  |     |
                        +----------------------+     |
                                                     |
+-----------------------------------------------------------------------------------+
|                                   DATA PLANE                                      |
|                        Smart Gateway Service (Port 8080)                          |
|                                                                                   |
|  - Scheduled Route Polling (@Scheduled default 10s, ApplicationRunner on startup) |
|  - Atomic In-Memory Route Registry (AtomicReference<List<GatewayRoute>>)          |
|  - Longest / Exact-Path Priority Matching via AntPathMatcher                      |
|  - Fixed-Window Rate Limiting (per Org, Route, and Client IP)                     |
|  - Upstream Safety & SSRF Protection (Cloud metadata / private IP blocking)       |
|  - Reverse Proxy Forwarding, Path Rewriting & Hop-by-Hop Header Stripping        |
+-----------------------------------------------------------------------------------+
             ^                                       |
             | Incoming Client Requests              | Proxied HTTP Requests
             | (Port 8080)                           v
     +---------------+                      +------------------+
     |  API Clients  |                      | Upstream Targets |
     +---------------+                      +------------------+
```

### Components

1. **API Management Service (`api-management-service`) — Control Plane (Port 8081)**:
   - Platform administrator management and tenant organization provisioning.
   - Tenant administrator role creation and strict tenant route isolation.
   - Route CRUD operations, activation/deactivation, and version incrementing.
   - Comprehensive audit logging recording actor, action, duration, and correlation ID.
   - Protected endpoint `GET /api/gateway/routes` requiring `GATEWAY_SERVICE` role.
   - Embedded browser management dashboard (`/index.html`).

2. **Smart Gateway Service (`smart-gateway-service`) — Data Plane (Port 8080)**:
   - On startup (`ApplicationRunner`), performs initial authenticated route synchronization.
   - Periodically polls `GET /api/gateway/routes` using Spring Scheduling (`@Scheduled`).
   - Atomically updates an in-memory `RouteRegistry` snapshot (`AtomicReference`).
   - Matches incoming requests with exact-path priority over wildcards (`AntPathMatcher`).
   - Enforces per-route allowed HTTP methods (returns `405 Method Not Allowed`).
   - Enforces fixed-window rate limiting per org/route/client (returns `429 Too Many Requests`).
   - Enforces SSRF and cloud metadata protection (returns `403 Forbidden`).
   - Rewrites paths, preserves query parameters, strips hop-by-hop headers, and proxies upstream.

3. **Infrastructure (Docker Compose)**:
   - **MySQL 8** (Port 3306): The single source of truth for organizations, users, roles, routes, configuration version snapshots, and audit history.
   - **No external message brokers required**: Kafka has been eliminated, simplifying infrastructure to a single database container.

---

## Technologies Used

- **Java 21**
- **Spring Boot 4.1.1**
- **Spring Security** (Stateless JWT resource server with Nimbus JWT encoder/decoder, HMAC-SHA256)
- **Spring Data JPA** (Hibernate 6)
- **Spring Scheduling** (`@EnableScheduling`, `@Scheduled`)
- **Spring RestClient** (HTTP Client for inter-service communication)
- **MySQL 8.0** & **Flyway** (Database migrations V1–V3)
- **Docker & Docker Compose** (Containerized MySQL)
- **JUnit 5, Mockito & AssertJ** (Unit, integration, and security testing)

---

## Configuration

Configuration is externalized through environment variables and configured in `application.properties`:

| Variable | Description | Default / Example | Target Service |
|---|---|---|---|
| `MYSQL_ROOT_PASSWORD` | Root password for MySQL Docker container | *(Required)* | Docker Compose |
| `MYSQL_DATABASE` | Database name initialized in MySQL | `gatekeeper_management` | Docker Compose |
| `MYSQL_USER` | Application DB username | `gatekeeper` | Docker Compose |
| `MYSQL_PASSWORD` | Application DB password | *(Required)* | Docker Compose |
| `GATEKEEPER_DB_URL` | JDBC URL for API Management | `jdbc:mysql://localhost:3306/gatekeeper_management` | API Management |
| `GATEKEEPER_DB_USERNAME` | Database username | `gatekeeper` | API Management |
| `GATEKEEPER_DB_PASSWORD` | Database password | *(Matches MYSQL_PASSWORD)* | API Management |
| `GATEKEEPER_JWT_SECRET` | HMAC-SHA256 secret (min 256 bits / 32 chars) | *(Required random secret)* | API Management |
| `GATEKEEPER_GATEWAY_USERNAME` | Gateway service username for Control Plane auth | `gateway-service` | Smart Gateway |
| `GATEKEEPER_GATEWAY_PASSWORD` | Gateway service password for Control Plane auth | `gateway123` | Smart Gateway |
| `GATEKEEPER_MANAGEMENT_URL` | Base URL of the API Management Service | `http://localhost:8081` | Smart Gateway |
| `GATEKEEPER_CONFIGURATION_REFRESH_INTERVAL_MS` | Gateway route polling interval in milliseconds | `10000` (10 seconds) | Smart Gateway |
| `GATEKEEPER_SAFETY_ENABLED` | Enable SSRF & unsafe IP checking | `true` | Smart Gateway |
| `GATEKEEPER_DEV_MODE` | Allow loopback/private target IPs in local dev | `true` | Smart Gateway |

---

## Running the Application (Windows PowerShell)

Follow these steps to run the complete platform locally.

### Step 1: Open the Project & Configure Environment

Copy `.env.example` to `.env` at the repository root:

```powershell
Copy-Item .env.example .env
```

Open `.env` in an editor and set your local credentials:
- Set `MYSQL_ROOT_PASSWORD` and `MYSQL_PASSWORD`.
- Set `GATEKEEPER_DB_PASSWORD` to match `MYSQL_PASSWORD`.
- Set `GATEKEEPER_JWT_SECRET` (at least 32 characters, e.g. `gatekeeper-production-grade-jwt-secret-key-32-chars!`).
- Confirm `GATEKEEPER_GATEWAY_USERNAME=gateway-service` and `GATEKEEPER_GATEWAY_PASSWORD=gateway123` (matching seeded migration `V3__seed_platform_accounts.sql`).
- Set `GATEKEEPER_CONFIGURATION_REFRESH_INTERVAL_MS=10000` (10 seconds).

### Step 2: Start MySQL

Start the MySQL 8 container in the background:

```powershell
docker compose up -d
```

Verify that MySQL is healthy:

```powershell
docker compose ps
```

Wait until `gatekeeper-mysql` reports `(healthy)`.

### Step 3: Load Environment Variables in PowerShell

Run this snippet in each PowerShell terminal before launching a service to load `.env` variables into the current session:

```powershell
Get-Content .env | Where-Object { $_ -match '^\s*[^#\s]+=.*$' } | ForEach-Object {
    $name, $value = $_.Trim().Split('=', 2)
    [System.Environment]::SetEnvironmentVariable($name.Trim(), $value.Trim(), "Process")
}
```

### Step 4: Run API Management Service (Control Plane - Port 8081)

In Terminal 1 (with environment loaded):

```powershell
cd api-management-service
.\mvnw.cmd spring-boot:run
```

Once started:
- Health check: `http://localhost:8081/health`
- Management Dashboard: Open `http://localhost:8081/` in your browser.
- Seeded Platform Administrator: `platform-admin` / `platform123`.

### Step 5: Run Smart Gateway Service (Data Plane - Port 8080)

In Terminal 2 (with environment loaded):

```powershell
cd smart-gateway-service
.\mvnw.cmd spring-boot:run
```

Once started:
- Health check: `http://localhost:8080/health`
- The Gateway automatically logs in to `http://localhost:8081/api/auth/login` as `gateway-service`, retrieves active routes, and starts periodic background synchronization every 10 seconds.
- If API Management is offline when the Gateway starts, the Gateway starts gracefully with an empty registry and automatically recovers on subsequent polling cycles once API Management comes online.

---

## End-to-End Demo Workflow (Windows PowerShell)

This workflow demonstrates the entire tenant provisioning, route configuration, scheduled synchronization, and request forwarding lifecycle.

### 1. Authenticate as Platform Administrator

```powershell
$loginBody = @{ username = "platform-admin"; password = "platform123" } | ConvertTo-Json
$platformAuth = Invoke-RestMethod -Uri "http://localhost:8081/api/auth/login" -Method Post -Body $loginBody -ContentType "application/json"
$platformToken = $platformAuth.token
Write-Host "Platform Admin Token acquired."
```

### 2. Provision Tenant Organization

```powershell
$orgBody = @{ name = "FoodFast Inc"; slug = "foodfast" } | ConvertTo-Json
$org = Invoke-RestMethod -Uri "http://localhost:8081/api/platform/organizations" `
    -Method Post -Body $orgBody -ContentType "application/json" `
    -Headers @{ Authorization = "Bearer $platformToken" }
$orgId = $org.id
Write-Host "Created Organization ID: $orgId (Status: PENDING)"
```

### 3. Create Tenant Administrator

```powershell
$adminBody = @{ username = "foodfast_admin"; password = "Password123!"; enabled = $true } | ConvertTo-Json
$tenantAdmin = Invoke-RestMethod -Uri "http://localhost:8081/api/platform/organizations/$orgId/tenant-admin" `
    -Method Post -Body $adminBody -ContentType "application/json" `
    -Headers @{ Authorization = "Bearer $platformToken" }
Write-Host "Created Tenant Administrator: foodfast_admin"
```

### 4. Activate the Organization

```powershell
$statusBody = @{ status = "ACTIVE" } | ConvertTo-Json
$activeOrg = Invoke-RestMethod -Uri "http://localhost:8081/api/platform/organizations/$orgId/status" `
    -Method Patch -Body $statusBody -ContentType "application/json" `
    -Headers @{ Authorization = "Bearer $platformToken" }
Write-Host "Organization activated."
```

### 5. Authenticate as Tenant Administrator

```powershell
$tenantLogin = @{ username = "foodfast_admin"; password = "Password123!" } | ConvertTo-Json
$tenantAuth = Invoke-RestMethod -Uri "http://localhost:8081/api/auth/login" -Method Post -Body $tenantLogin -ContentType "application/json"
$tenantToken = $tenantAuth.token
Write-Host "Tenant Admin Token acquired."
```

### 6. Create an API Route

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
Write-Host "Route orders-v1 created in Control Plane."
```

### 7. Scheduled Configuration Synchronization

> **Synchronization Notice**: Configuration updates reach the Smart Gateway via scheduled REST polling (`GATEKEEPER_CONFIGURATION_REFRESH_INTERVAL_MS`).
> With the default 10-second interval, wait at most **10 seconds** for the gateway to poll and update its in-memory `RouteRegistry`. Check the gateway console log for:
> `INFO ... GatewayConfigurationLoader : Successfully synchronized 1 active route(s) from Management API in ... ms`

```powershell
Start-Sleep -Seconds 10
```

### 8. Send Requests Through the Smart Gateway (Port 8080)

#### A. Path Rewriting & Query Parameter Preservation
```powershell
curl.exe -i "http://localhost:8080/foodfast/orders/items/42?status=shipped&category=food"
```
*Expected: `200 OK`. Upstream `httpbin.org/anything/orders/items/42?status=shipped&category=food` receives the query parameters transparently.*

#### B. Request Body Forwarding (POST)
```powershell
curl.exe -i -X POST http://localhost:8080/foodfast/orders/create `
  -H "Content-Type: application/json" `
  -d '{"item": "Burger", "qty": 2}'
```
*Expected: `200 OK` with JSON echo.*

#### C. Method Restriction Enforcement (405)
```powershell
# DELETE is not in allowedMethods
curl.exe -i -X DELETE http://localhost:8080/foodfast/orders/42
```
*Expected: `HTTP/1.1 405 Method Not Allowed` with `Allow: GET, POST` header.*

#### D. Unknown Route Handling (404)
```powershell
curl.exe -i http://localhost:8080/unknown/service
```
*Expected: `HTTP/1.1 404 Not Found` with `{ "error": "Not Found", "message": "No matching active route found for path: /unknown/service" }`.*

#### E. Rate Limiting Enforced (429)
```powershell
# Send 7 requests exceeding the 5 req/min quota
1..7 | ForEach-Object {
    curl.exe -s -o NUL -w "%{http_code}`n" http://localhost:8080/foodfast/orders/items
}
```
*Expected: Five `200` responses followed by two `429` responses with `Retry-After: 60`.*

---

## Testing

Both services contain comprehensive automated test suites using JUnit 5, Mockito, and Spring Test.

### Run API Management Tests
```powershell
cd api-management-service
.\mvnw.cmd test
```
*Covers platform admin authorization, tenant admin authorization, organization lifecycle, route CRUD, versioning, audit logging, and gateway endpoint security.*

### Run Smart Gateway Tests
```powershell
cd smart-gateway-service
.\mvnw.cmd test
```
*Covers scheduled polling synchronization (all 12 lifecycle/failure scenarios), atomic registry replacement, path rewriting, query preservation, fixed-window rate limiting, and SSRF upstream safety.*

### Package Both Services
```powershell
cd api-management-service; .\mvnw.cmd clean package -DskipTests
cd ../smart-gateway-service; .\mvnw.cmd clean package -DskipTests
```

---

## Architecture Trade-Offs

| Decision | Trade-Off / Justification |
|---|---|
| **Separate Control Plane & Data Plane** | Isolates tenant administration, database queries, and auditing from the critical request-forwarding path. Heavy management traffic or schema locks never degrade high-throughput runtime routing. |
| **In-Memory Route Registry** | Ultra-low lookup latency ($O(1)$ to $O(N)$ path match) with zero per-request database I/O. Uses `AtomicReference` for lock-free atomic snapshot replacement so requests never observe partial routes. |
| **Scheduled REST Polling vs. Kafka** | **Eliminates broker infrastructure entirely.** For API gateway route changes (which occur infrequently compared to request throughput), a 5–10 second propagation delay is completely acceptable. Removing Kafka reduces operational overhead, eliminates complex event-handling and broker dependencies, and simplifies local development and interview demonstration. |
| **Management Service Outage Behavior** | The Data Plane is resilient: if API Management goes down, the gateway preserves its last successful in-memory route snapshot and continues routing uninterrupted. On startup without Control Plane, it starts with an empty registry and recovers automatically once Control Plane is reachable. |
| **In-Memory Rate Limiting Limitations** | The fixed-window limiter stores counters in local gateway memory. This is lightweight with zero network latency, but counters are local to each gateway instance. Horizontal scaling with multiple gateway instances requires either sticky routing / IP hashing at a load balancer or migrating counters to an external store (e.g., Redis). |
