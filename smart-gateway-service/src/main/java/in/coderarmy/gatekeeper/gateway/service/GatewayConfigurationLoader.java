package in.coderarmy.gatekeeper.gateway.service;

import in.coderarmy.gatekeeper.gateway.client.ManagementApiClient;
import in.coderarmy.gatekeeper.gateway.dto.GatewayRoute;
import in.coderarmy.gatekeeper.gateway.registry.RouteRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class GatewayConfigurationLoader implements ApplicationRunner {

    private static final Logger logger = LoggerFactory.getLogger(GatewayConfigurationLoader.class);

    private final ManagementApiClient managementApiClient;
    private final RouteRegistry routeRegistry;

    public GatewayConfigurationLoader(ManagementApiClient managementApiClient,
                                    RouteRegistry routeRegistry) {
        this.managementApiClient = managementApiClient;
        this.routeRegistry = routeRegistry;
    }

    @Override
    public void run(ApplicationArguments arguments) {
        try {
            refresh();
        } catch (Exception exception) {
            logger.warn("Management API is currently unavailable ({}). Gateway started with empty RouteRegistry.",
                    exception.getMessage());
        }
    }

    public synchronized boolean refresh() {
        try {
            List<GatewayRoute> routes = managementApiClient.fetchActiveRoutes();
            routeRegistry.updateRoutes(routes);
            logger.info("Successfully synchronized {} active route(s) from Management API", routeRegistry.getRouteCount());
            return true;
        } catch (Exception exception) {
            logger.warn("Could not synchronize routes from Management API: {}", exception.getMessage());
            return false;
        }
    }
}
