package in.coderarmy.gatekeeper.gateway;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import in.coderarmy.gatekeeper.gateway.controller.GatewayController;
import in.coderarmy.gatekeeper.gateway.dto.GatewayRoute;
import in.coderarmy.gatekeeper.gateway.forwarding.ForwardingService;
import in.coderarmy.gatekeeper.gateway.ratelimit.FixedWindowRateLimiter;
import in.coderarmy.gatekeeper.gateway.registry.RouteRegistry;
import in.coderarmy.gatekeeper.gateway.security.UpstreamSafetyValidator;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class GatewayIntegrationTest {

    private static HttpServer mockServer;
    private static int mockPort;
    private static final AtomicReference<String> lastReceivedPath = new AtomicReference<>();
    private static final AtomicReference<String> lastReceivedQuery = new AtomicReference<>();
    private static final AtomicReference<String> lastReceivedBody = new AtomicReference<>();

    private RouteRegistry routeRegistry;
    private FixedWindowRateLimiter rateLimiter;
    private ForwardingService forwardingService;
    private GatewayController gatewayController;

    @BeforeAll
    static void startMockBackend() throws IOException {
        mockServer = HttpServer.create(new InetSocketAddress(0), 0);
        mockPort = mockServer.getAddress().getPort();

        // Echo and orders handler
        mockServer.createContext("/api/orders", new HttpHandler() {
            @Override
            public void handle(HttpExchange exchange) throws IOException {
                lastReceivedPath.set(exchange.getRequestURI().getPath());
                lastReceivedQuery.set(exchange.getRequestURI().getQuery());

                try (InputStream is = exchange.getRequestBody()) {
                    lastReceivedBody.set(new String(is.readAllBytes(), StandardCharsets.UTF_8));
                }

                String method = exchange.getRequestMethod();
                if ("POST".equalsIgnoreCase(method)) {
                    byte[] response = "{\"status\": \"created\", \"id\": 999}".getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(201, response.length);
                    try (OutputStream os = exchange.getResponseBody()) {
                        os.write(response);
                    }
                } else {
                    byte[] response = "{\"orders\": [{\"id\": 1, \"name\": \"Test Order\"}]}".getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.getResponseHeaders().set("X-Mock-Backend", "Gatekeeper-Mock");
                    exchange.sendResponseHeaders(200, response.length);
                    try (OutputStream os = exchange.getResponseBody()) {
                        os.write(response);
                    }
                }
            }
        });

        // Slow handler simulating delay
        mockServer.createContext("/api/slow", new HttpHandler() {
            @Override
            public void handle(HttpExchange exchange) throws IOException {
                try {
                    Thread.sleep(300); // 300ms delay
                } catch (InterruptedException ignored) {}

                byte[] response = "{\"delayed\": true}".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, response.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(response);
                }
            }
        });

        mockServer.start();
    }

    @AfterAll
    static void stopMockBackend() {
        if (mockServer != null) {
            mockServer.stop(0);
        }
    }

    @BeforeEach
    void setUp() {
        routeRegistry = new RouteRegistry();
        rateLimiter = new FixedWindowRateLimiter(java.util.Collections.emptySet());
        rateLimiter.clear();
        UpstreamSafetyValidator safetyValidator = new UpstreamSafetyValidator(true, true); // Dev mode enables localhost mock
        forwardingService = new ForwardingService(rateLimiter, safetyValidator);
        gatewayController = new GatewayController(routeRegistry, forwardingService);

        // Configure active test routes
        GatewayRoute ordersRoute = new GatewayRoute();
        ordersRoute.setOrganizationId(1L);
        ordersRoute.setRouteId("orders-api");
        ordersRoute.setName("Orders Service");
        ordersRoute.setPathPattern("/acme/orders/**");
        ordersRoute.setTargetBaseUrl("http://localhost:" + mockPort);
        ordersRoute.setTargetPathPrefix("/api/orders");
        ordersRoute.setActive(true);
        ordersRoute.setAllowedMethods(Set.of("GET", "POST"));
        ordersRoute.setResponseTimeoutMs(2000);

        GatewayRoute slowRoute = new GatewayRoute();
        slowRoute.setOrganizationId(1L);
        slowRoute.setRouteId("slow-api");
        slowRoute.setName("Slow Service");
        slowRoute.setPathPattern("/acme/slow/**");
        slowRoute.setTargetBaseUrl("http://localhost:" + mockPort);
        slowRoute.setTargetPathPrefix("/api/slow");
        slowRoute.setActive(true);
        slowRoute.setAllowedMethods(Set.of("GET"));
        slowRoute.setResponseTimeoutMs(50); // Intentionally low to trigger timeout

        GatewayRoute unreachableRoute = new GatewayRoute();
        unreachableRoute.setOrganizationId(1L);
        unreachableRoute.setRouteId("unreachable-api");
        unreachableRoute.setName("Unreachable Service");
        unreachableRoute.setPathPattern("/acme/unreachable/**");
        unreachableRoute.setTargetBaseUrl("http://localhost:59998"); // Closed port
        unreachableRoute.setTargetPathPrefix("/api");
        unreachableRoute.setActive(true);
        unreachableRoute.setAllowedMethods(Set.of("GET"));

        GatewayRoute rateLimitedRoute = new GatewayRoute();
        rateLimitedRoute.setOrganizationId(1L);
        rateLimitedRoute.setRouteId("limited-api");
        rateLimitedRoute.setName("Rate Limited Service");
        rateLimitedRoute.setPathPattern("/acme/limited/**");
        rateLimitedRoute.setTargetBaseUrl("http://localhost:" + mockPort);
        rateLimitedRoute.setTargetPathPrefix("/api/orders");
        rateLimitedRoute.setActive(true);
        rateLimitedRoute.setAllowedMethods(Set.of("GET"));
        rateLimitedRoute.setRequestsPerMinute(2); // Max 2 requests/min

        routeRegistry.updateRoutes(List.of(ordersRoute, slowRoute, unreachableRoute, rateLimitedRoute));
    }

    @Test
    @DisplayName("GET request is successfully forwarded with path rewriting and query parameters preserved")
    void forward_getSuccess_withPathRewritingAndQueryParams() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/acme/orders/items/42");
        request.setQueryString("filter=active&sort=desc");
        request.addHeader("X-Client-Header", "GatekeeperClient");

        ResponseEntity<byte[]> response = gatewayController.handleGatewayTraffic(request, null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(new String(response.getBody(), StandardCharsets.UTF_8)).contains("Test Order");
        assertThat(response.getHeaders().getFirst("X-Mock-Backend")).isEqualTo("Gatekeeper-Mock");

        // Verify upstream received rewritten path and preserved query parameters
        assertThat(lastReceivedPath.get()).isEqualTo("/api/orders/items/42");
        assertThat(lastReceivedQuery.get()).isEqualTo("filter=active&sort=desc");
    }

    @Test
    @DisplayName("POST request forwards body and returns upstream 201 Created")
    void forward_postSuccess_withBody() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/acme/orders/create");
        byte[] body = "{\"item\": \"pizza\", \"quantity\": 2}".getBytes(StandardCharsets.UTF_8);

        ResponseEntity<byte[]> response = gatewayController.handleGatewayTraffic(request, body);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(new String(response.getBody(), StandardCharsets.UTF_8)).contains("created");
        assertThat(lastReceivedBody.get()).isEqualTo("{\"item\": \"pizza\", \"quantity\": 2}");
    }

    @Test
    @DisplayName("Returns 404 Not Found when no active route matches path")
    void handleTraffic_noRouteMatches_returns404() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/unknown/nonexistent/service");

        ResponseEntity<byte[]> response = gatewayController.handleGatewayTraffic(request, null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(new String(response.getBody(), StandardCharsets.UTF_8)).contains("No active route matches path");
    }

    @Test
    @DisplayName("Returns 405 Method Not Allowed when HTTP method is not permitted for route")
    void forward_unsupportedMethod_returns405() {
        MockHttpServletRequest request = new MockHttpServletRequest("DELETE", "/acme/orders/1");

        ResponseEntity<byte[]> response = gatewayController.handleGatewayTraffic(request, null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
        assertThat(response.getHeaders().getAllow()).contains(org.springframework.http.HttpMethod.GET, org.springframework.http.HttpMethod.POST);
    }

    @Test
    @DisplayName("Returns 504 Gateway Timeout when upstream response exceeds configured timeout")
    void forward_timeoutExceeded_returns504() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/acme/slow/endpoint");

        ResponseEntity<byte[]> response = gatewayController.handleGatewayTraffic(request, null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.GATEWAY_TIMEOUT);
        assertThat(new String(response.getBody(), StandardCharsets.UTF_8)).contains("Gateway Timeout");
    }

    @Test
    @DisplayName("Returns 502 Bad Gateway when upstream server cannot be reached")
    void forward_connectionFailure_returns502() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/acme/unreachable/test");

        ResponseEntity<byte[]> response = gatewayController.handleGatewayTraffic(request, null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
        assertThat(new String(response.getBody(), StandardCharsets.UTF_8)).contains("Bad Gateway");
    }

    @Test
    @DisplayName("Returns 429 Too Many Requests when rate limit is exceeded")
    void forward_rateLimitExceeded_returns429() {
        MockHttpServletRequest req1 = new MockHttpServletRequest("GET", "/acme/limited/test");
        req1.setRemoteAddr("10.0.0.99");
        MockHttpServletRequest req2 = new MockHttpServletRequest("GET", "/acme/limited/test");
        req2.setRemoteAddr("10.0.0.99");
        MockHttpServletRequest req3 = new MockHttpServletRequest("GET", "/acme/limited/test");
        req3.setRemoteAddr("10.0.0.99");

        assertThat(gatewayController.handleGatewayTraffic(req1, null).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(gatewayController.handleGatewayTraffic(req2, null).getStatusCode()).isEqualTo(HttpStatus.OK);

        // 3rd request exceeds limit of 2/min
        ResponseEntity<byte[]> response3 = gatewayController.handleGatewayTraffic(req3, null);
        assertThat(response3.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(response3.getHeaders().getFirst("Retry-After")).isEqualTo("60");
        assertThat(new String(response3.getBody(), StandardCharsets.UTF_8)).contains("Rate limit of 2 requests/minute exceeded");
    }
}

