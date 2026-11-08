package in.coderarmy.gatekeeper.gateway.registry;

import in.coderarmy.gatekeeper.gateway.dto.GatewayRoute;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class RouteRegistryTest {

    private RouteRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new RouteRegistry();
    }

    private GatewayRoute createRoute(String id, String pattern, boolean active) {
        GatewayRoute route = new GatewayRoute();
        route.setRouteId(id);
        route.setName(id);
        route.setPathPattern(pattern);
        route.setTargetBaseUrl("http://localhost:9000");
        route.setTargetPathPrefix("/api");
        route.setActive(active);
        route.setAllowedMethods(Set.of("GET", "POST"));
        return route;
    }

    @Test
    @DisplayName("Registry only retains active routes and updates atomically")
    void updateRoutes_onlyActiveRetained() {
        GatewayRoute active1 = createRoute("orders-wildcard", "/acme/orders/**", true);
        GatewayRoute inactive = createRoute("inactive-route", "/acme/inactive/**", false);
        GatewayRoute active2 = createRoute("orders-exact", "/acme/orders/special", true);

        registry.updateRoutes(List.of(active1, inactive, active2));

        assertThat(registry.getRouteCount()).isEqualTo(2);
        assertThat(registry.getActiveRoutes()).extracting(GatewayRoute::getRouteId)
                .containsExactlyInAnyOrder("orders-wildcard", "orders-exact");
    }

    @Test
    @DisplayName("Specific exact paths take precedence over wildcard patterns")
    void findMatchingRoute_specificPrecedence() {
        GatewayRoute wildcard = createRoute("wildcard", "/acme/orders/**", true);
        GatewayRoute exact = createRoute("exact", "/acme/orders/special", true);

        registry.updateRoutes(List.of(wildcard, exact));

        Optional<GatewayRoute> matchExact = registry.findMatchingRoute("/acme/orders/special");
        assertThat(matchExact).isPresent();
        assertThat(matchExact.get().getRouteId()).isEqualTo("exact");

        Optional<GatewayRoute> matchWildcard = registry.findMatchingRoute("/acme/orders/123");
        assertThat(matchWildcard).isPresent();
        assertThat(matchWildcard.get().getRouteId()).isEqualTo("wildcard");
    }

    @Test
    @DisplayName("Returns empty optional when path does not match any route")
    void findMatchingRoute_noMatch() {
        GatewayRoute route = createRoute("users", "/acme/users/**", true);
        registry.updateRoutes(List.of(route));

        Optional<GatewayRoute> match = registry.findMatchingRoute("/unknown/path");
        assertThat(match).isEmpty();
    }
}

