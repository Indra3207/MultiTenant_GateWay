package in.coderarmy.gatekeeper.gateway.service;

import in.coderarmy.gatekeeper.gateway.client.ManagementApiClient;
import in.coderarmy.gatekeeper.gateway.dto.GatewayRoute;
import in.coderarmy.gatekeeper.gateway.registry.RouteRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.net.ConnectException;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

@ExtendWith(MockitoExtension.class)
class GatewayConfigurationSynchronizationTest {

    @Mock
    private ManagementApiClient managementApiClient;

    private RouteRegistry routeRegistry;
    private GatewayConfigurationLoader loader;

    @BeforeEach
    void setUp() {
        routeRegistry = new RouteRegistry();
        loader = new GatewayConfigurationLoader(managementApiClient, routeRegistry);
    }

    private GatewayRoute createRoute(String routeId, String pattern, String targetUrl, boolean active) {
        GatewayRoute route = new GatewayRoute();
        route.setRouteId(routeId);
        route.setName(routeId);
        route.setPathPattern(pattern);
        route.setTargetBaseUrl(targetUrl);
        route.setTargetPathPrefix("/");
        route.setResponseTimeoutMs(5000);
        route.setRequestsPerMinute(60);
        route.setActive(active);
        route.setAllowedMethods(Set.of("GET", "POST"));
        return route;
    }

    @Test
    @DisplayName("1. Gateway loads initial routes successfully on startup")
    void test01_loadsInitialRoutesSuccessfully() {
        GatewayRoute initialRoute = createRoute("orders-v1", "/orders/**", "http://orders-svc:8080", true);
        when(managementApiClient.fetchActiveRoutes()).thenReturn(List.of(initialRoute));

        loader.run(new DefaultApplicationArguments(new String[0]));

        assertThat(routeRegistry.getRouteCount()).isEqualTo(1);
        assertThat(routeRegistry.findMatchingRoute("/orders/123")).isPresent();
        assertThat(routeRegistry.findMatchingRoute("/orders/123").get().getRouteId()).isEqualTo("orders-v1");
    }

    @Test
    @DisplayName("2. New route becomes available after the next refresh")
    void test02_newRouteBecomesAvailableAfterNextRefresh() {
        GatewayRoute route1 = createRoute("orders", "/orders/**", "http://orders-svc:8080", true);
        when(managementApiClient.fetchActiveRoutes()).thenReturn(List.of(route1));
        loader.refresh();
        assertThat(routeRegistry.getRouteCount()).isEqualTo(1);

        GatewayRoute route2 = createRoute("payments", "/payments/**", "http://payments-svc:8080", true);
        when(managementApiClient.fetchActiveRoutes()).thenReturn(List.of(route1, route2));
        boolean refreshed = loader.refresh();

        assertThat(refreshed).isTrue();
        assertThat(routeRegistry.getRouteCount()).isEqualTo(2);
        assertThat(routeRegistry.findMatchingRoute("/payments/pay")).isPresent();
    }

    @Test
    @DisplayName("3. Updated route replaces existing route with new configuration")
    void test03_updatedRouteReplacesExistingRoute() {
        GatewayRoute v1 = createRoute("orders", "/orders/**", "http://orders-v1:8080", true);
        when(managementApiClient.fetchActiveRoutes()).thenReturn(List.of(v1));
        loader.refresh();
        assertThat(routeRegistry.findMatchingRoute("/orders/1").get().getTargetBaseUrl()).isEqualTo("http://orders-v1:8080");

        GatewayRoute v2 = createRoute("orders", "/orders/**", "http://orders-v2:8080", true);
        when(managementApiClient.fetchActiveRoutes()).thenReturn(List.of(v2));
        boolean refreshed = loader.refresh();

        assertThat(refreshed).isTrue();
        assertThat(routeRegistry.getRouteCount()).isEqualTo(1);
        assertThat(routeRegistry.findMatchingRoute("/orders/1").get().getTargetBaseUrl()).isEqualTo("http://orders-v2:8080");
    }

    @Test
    @DisplayName("4. Deactivated route is removed from the registry")
    void test04_deactivatedRouteIsRemovedFromRegistry() {
        GatewayRoute activeRoute = createRoute("orders", "/orders/**", "http://orders:8080", true);
        when(managementApiClient.fetchActiveRoutes()).thenReturn(List.of(activeRoute));
        loader.refresh();
        assertThat(routeRegistry.getRouteCount()).isEqualTo(1);

        GatewayRoute deactivatedRoute = createRoute("orders", "/orders/**", "http://orders:8080", false);
        when(managementApiClient.fetchActiveRoutes()).thenReturn(List.of(deactivatedRoute));
        boolean refreshed = loader.refresh();

        assertThat(refreshed).isTrue();
        assertThat(routeRegistry.getRouteCount()).isEqualTo(0);
        assertThat(routeRegistry.findMatchingRoute("/orders/1")).isEmpty();
    }

    @Test
    @DisplayName("5. Deleted route is removed from the registry")
    void test05_deletedRouteIsRemovedFromRegistry() {
        GatewayRoute route1 = createRoute("orders", "/orders/**", "http://orders:8080", true);
        GatewayRoute route2 = createRoute("users", "/users/**", "http://users:8080", true);
        when(managementApiClient.fetchActiveRoutes()).thenReturn(List.of(route1, route2));
        loader.refresh();
        assertThat(routeRegistry.getRouteCount()).isEqualTo(2);

        // orders deleted from control plane, only users remains
        when(managementApiClient.fetchActiveRoutes()).thenReturn(List.of(route2));
        boolean refreshed = loader.refresh();

        assertThat(refreshed).isTrue();
        assertThat(routeRegistry.getRouteCount()).isEqualTo(1);
        assertThat(routeRegistry.findMatchingRoute("/orders/1")).isEmpty();
        assertThat(routeRegistry.findMatchingRoute("/users/me")).isPresent();
    }

    @Test
    @DisplayName("6. Empty successful response clears the registry")
    void test06_emptySuccessfulResponseClearsRegistry() {
        GatewayRoute route = createRoute("orders", "/orders/**", "http://orders:8080", true);
        when(managementApiClient.fetchActiveRoutes()).thenReturn(List.of(route));
        loader.refresh();
        assertThat(routeRegistry.getRouteCount()).isEqualTo(1);

        when(managementApiClient.fetchActiveRoutes()).thenReturn(Collections.emptyList());
        boolean refreshed = loader.refresh();

        assertThat(refreshed).isTrue();
        assertThat(routeRegistry.getRouteCount()).isEqualTo(0);
        assertThat(routeRegistry.getActiveRoutes()).isEmpty();
    }

    @Test
    @DisplayName("7. Management API returning 500 preserves the previous registry")
    void test07_managementApiReturning500PreservesPreviousRegistry() {
        GatewayRoute route = createRoute("orders", "/orders/**", "http://orders:8080", true);
        when(managementApiClient.fetchActiveRoutes()).thenReturn(List.of(route));
        loader.refresh();
        assertThat(routeRegistry.getRouteCount()).isEqualTo(1);

        when(managementApiClient.fetchActiveRoutes())
                .thenThrow(new HttpServerErrorException(HttpStatus.INTERNAL_SERVER_ERROR, "Database down"));

        boolean refreshed = loader.refresh();

        assertThat(refreshed).isFalse();
        assertThat(routeRegistry.getRouteCount()).isEqualTo(1);
        assertThat(routeRegistry.findMatchingRoute("/orders/1")).isPresent();
    }

    @Test
    @DisplayName("8. Connection failure preserves the previous registry")
    void test08_connectionFailurePreservesPreviousRegistry() {
        GatewayRoute route = createRoute("orders", "/orders/**", "http://orders:8080", true);
        when(managementApiClient.fetchActiveRoutes()).thenReturn(List.of(route));
        loader.refresh();
        assertThat(routeRegistry.getRouteCount()).isEqualTo(1);

        when(managementApiClient.fetchActiveRoutes())
                .thenThrow(new ResourceAccessException("Connection refused", new ConnectException("Connection refused")));

        boolean refreshed = loader.refresh();

        assertThat(refreshed).isFalse();
        assertThat(routeRegistry.getRouteCount()).isEqualTo(1);
        assertThat(routeRegistry.findMatchingRoute("/orders/1")).isPresent();
    }

    @Test
    @DisplayName("9. Invalid response preserves the previous registry")
    void test09_invalidResponsePreservesPreviousRegistry() {
        GatewayRoute route = createRoute("orders", "/orders/**", "http://orders:8080", true);
        when(managementApiClient.fetchActiveRoutes()).thenReturn(List.of(route));
        loader.refresh();
        assertThat(routeRegistry.getRouteCount()).isEqualTo(1);

        when(managementApiClient.fetchActiveRoutes())
                .thenThrow(new IllegalStateException("API Management returned null routes list"));

        boolean refreshed = loader.refresh();

        assertThat(refreshed).isFalse();
        assertThat(routeRegistry.getRouteCount()).isEqualTo(1);
        assertThat(routeRegistry.findMatchingRoute("/orders/1")).isPresent();
    }

    @Test
    @DisplayName("10. Expired JWT triggers appropriate re-authentication and route fetch retry")
    void test10_expiredJwtTriggersReauthentication() {
        RestClient.Builder restClientBuilder = RestClient.builder().baseUrl("http://localhost:8081");
        MockRestServiceServer mockServer = MockRestServiceServer.bindTo(restClientBuilder).build();
        ManagementApiClient realClient = new ManagementApiClient(restClientBuilder.build(), "gateway-service", "gateway123");

        // 1. Initial login
        mockServer.expect(requestTo("http://localhost:8081/api/auth/login"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("{\"token\": \"initial-jwt-token\", \"role\": \"GATEWAY_SERVICE\", \"expiresInSeconds\": 3600}", MediaType.APPLICATION_JSON));

        // 2. Initial fetch with expired/rejected token -> returns 401
        mockServer.expect(requestTo("http://localhost:8081/api/gateway/routes"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer initial-jwt-token"))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED));

        // 3. Re-authentication on 401
        mockServer.expect(requestTo("http://localhost:8081/api/auth/login"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("{\"token\": \"refreshed-jwt-token\", \"role\": \"GATEWAY_SERVICE\", \"expiresInSeconds\": 3600}", MediaType.APPLICATION_JSON));

        // 4. Retry with new token succeeds
        String routeJson = "[{\"routeId\":\"orders\",\"name\":\"Orders\",\"pathPattern\":\"/orders/**\",\"targetBaseUrl\":\"http://orders:8080\",\"targetPathPrefix\":\"/\",\"responseTimeoutMs\":5000,\"requestsPerMinute\":60,\"active\":true,\"allowedMethods\":[\"GET\"]}]";
        mockServer.expect(requestTo("http://localhost:8081/api/gateway/routes"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer refreshed-jwt-token"))
                .andRespond(withSuccess(routeJson, MediaType.APPLICATION_JSON));

        List<GatewayRoute> routes = realClient.fetchActiveRoutes();

        mockServer.verify();
        assertThat(routes).hasSize(1);
        assertThat(routes.get(0).getRouteId()).isEqualTo("orders");
    }

    @Test
    @DisplayName("11. Successful synchronization resumes after Management API recovery")
    void test11_successfulSynchronizationResumesAfterManagementApiRecovery() {
        GatewayRoute initialRoute = createRoute("initial", "/initial/**", "http://initial:8080", true);
        GatewayRoute recoveredRoute = createRoute("recovered", "/recovered/**", "http://recovered:8080", true);

        when(managementApiClient.fetchActiveRoutes())
                .thenReturn(List.of(initialRoute))
                .thenThrow(new HttpServerErrorException(HttpStatus.INTERNAL_SERVER_ERROR))
                .thenReturn(List.of(recoveredRoute));

        // 1. Initial success
        boolean initialRefresh = loader.refresh();
        assertThat(initialRefresh).isTrue();
        assertThat(routeRegistry.getRouteCount()).isEqualTo(1);
        assertThat(routeRegistry.findMatchingRoute("/initial/test")).isPresent();

        // 2. Outage: 500 error preserves initial route
        boolean failedRefresh = loader.refresh();
        assertThat(failedRefresh).isFalse();
        assertThat(routeRegistry.getRouteCount()).isEqualTo(1);
        assertThat(routeRegistry.findMatchingRoute("/initial/test")).isPresent();

        // 3. Recovery: Management API comes back with updated route
        boolean recoveredRefresh = loader.refresh();
        assertThat(recoveredRefresh).isTrue();
        assertThat(routeRegistry.getRouteCount()).isEqualTo(1);
        assertThat(routeRegistry.findMatchingRoute("/initial/test")).isEmpty();
        assertThat(routeRegistry.findMatchingRoute("/recovered/test")).isPresent();
    }

    @Test
    @DisplayName("12. Concurrent synchronization attempts cannot corrupt route snapshot")
    void test12_twoSynchronizationAttemptsCannotCorruptRouteSnapshot() throws InterruptedException {
        GatewayRoute routeA = createRoute("route-a", "/a/**", "http://service-a:8080", true);
        GatewayRoute routeB = createRoute("route-b", "/b/**", "http://service-b:8080", true);

        when(managementApiClient.fetchActiveRoutes())
                .thenReturn(List.of(routeA))
                .thenReturn(List.of(routeB))
                .thenReturn(List.of(routeA, routeB));

        int threadCount = 4;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);
        AtomicInteger successCount = new AtomicInteger();

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    if (loader.refresh()) {
                        successCount.incrementAndGet();
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        boolean completed = latch.await(5, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(completed).isTrue();
        assertThat(successCount.get()).isGreaterThanOrEqualTo(1);

        // Snapshot must remain valid, non-null, uncorrupted
        List<GatewayRoute> snapshot = routeRegistry.getActiveRoutes();
        assertThat(snapshot).isNotNull();
        assertThat(snapshot).isNotEmpty();
        for (GatewayRoute r : snapshot) {
            assertThat(r.getRouteId()).isIn("route-a", "route-b");
            assertThat(routeRegistry.findMatchingRoute(r.getPathPattern().replace("/**", "/test"))).isPresent();
        }
    }
}
