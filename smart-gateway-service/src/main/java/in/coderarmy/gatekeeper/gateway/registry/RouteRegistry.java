package in.coderarmy.gatekeeper.gateway.registry;

import in.coderarmy.gatekeeper.gateway.dto.GatewayRoute;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

@Component
public class RouteRegistry {

    private static final Logger logger = LoggerFactory.getLogger(RouteRegistry.class);

    private final AntPathMatcher pathMatcher = new AntPathMatcher();
    private final AtomicReference<List<GatewayRoute>> routesSnapshot =
            new AtomicReference<>(Collections.emptyList());

    /**
     * Atomically replaces the current active route snapshot.
     * Routes are sorted so specific paths take precedence over wildcard patterns.
     */
    public void updateRoutes(List<GatewayRoute> routes) {
        if (routes == null) {
            routesSnapshot.set(Collections.emptyList());
            logger.info("Route registry cleared");
            return;
        }

        List<GatewayRoute> activeRoutes = new ArrayList<>(routes.stream()
                .filter(GatewayRoute::isActive)
                .toList());

        // Sort patterns: exact / specific paths before wildcard patterns
        activeRoutes.sort(Comparator.comparing(
                GatewayRoute::getPathPattern,
                pathMatcher.getPatternComparator("/")
        ));

        routesSnapshot.set(Collections.unmodifiableList(activeRoutes));
        logger.info("Route registry updated atomically with {} active route(s)", activeRoutes.size());
    }

    /**
     * Finds the first (most specific) matching route for the given request path.
     */
    public Optional<GatewayRoute> findMatchingRoute(String path) {
        List<GatewayRoute> routes = routesSnapshot.get();
        for (GatewayRoute route : routes) {
            if (pathMatcher.match(route.getPathPattern(), path)) {
                return Optional.of(route);
            }
        }
        return Optional.empty();
    }

    public List<GatewayRoute> getActiveRoutes() {
        return routesSnapshot.get();
    }

    public int getRouteCount() {
        return routesSnapshot.get().size();
    }
}

