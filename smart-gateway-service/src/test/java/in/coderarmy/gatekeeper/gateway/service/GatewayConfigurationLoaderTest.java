package in.coderarmy.gatekeeper.gateway.service;

import in.coderarmy.gatekeeper.gateway.client.ManagementApiClient;
import in.coderarmy.gatekeeper.gateway.dto.GatewayRoute;
import in.coderarmy.gatekeeper.gateway.registry.RouteRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.web.client.ResourceAccessException;

import java.net.ConnectException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GatewayConfigurationLoaderTest {

    @Mock
    private ManagementApiClient managementApiClient;

    private RouteRegistry routeRegistry;
    private GatewayConfigurationLoader loader;

    @BeforeEach
    void setUp() {
        routeRegistry = new RouteRegistry();
        loader = new GatewayConfigurationLoader(managementApiClient, routeRegistry);
    }

    @Test
    @DisplayName("Startup does not throw or crash when Management API is unavailable")
    void run_managementApiUnavailable_doesNotCrash() {
        when(managementApiClient.fetchActiveRoutes())
                .thenThrow(new ResourceAccessException("Connection refused", new ConnectException()));

        assertThatCode(() -> loader.run(new DefaultApplicationArguments(new String[0])))
                .doesNotThrowAnyException();

        assertThat(routeRegistry.getRouteCount()).isEqualTo(0);
    }

    @Test
    @DisplayName("refresh() populates RouteRegistry when Management API is available")
    void refresh_success_updatesRouteRegistry() {
        GatewayRoute route = new GatewayRoute();
        route.setRouteId("test-route");
        route.setPathPattern("/test/**");
        route.setActive(true);

        when(managementApiClient.fetchActiveRoutes()).thenReturn(List.of(route));

        boolean refreshed = loader.refresh();

        assertThat(refreshed).isTrue();
        assertThat(routeRegistry.getRouteCount()).isEqualTo(1);
        assertThat(routeRegistry.findMatchingRoute("/test/hello")).isPresent();
    }
}

