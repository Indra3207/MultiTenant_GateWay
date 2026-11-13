package in.coderarmy.gatekeeper.gateway.service;

import in.coderarmy.gatekeeper.gateway.client.ManagementApiClient;
import in.coderarmy.gatekeeper.gateway.dto.GatewayRoute;
import in.coderarmy.gatekeeper.gateway.registry.RouteRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.scheduling.annotation.Scheduled;
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
        logger.info("Executing initial gateway route configuration synchronization...");
        try {
            refresh();
        } catch (Exception exception) {
            logger.warn("Management API is currently unavailable during initial startup ({}). Gateway started with empty RouteRegistry.",
                    exception.getMessage());
        }
    }

    @Scheduled(
            fixedDelayString = "${gateway.configuration.refresh-interval-ms:10000}",
            initialDelayString = "${gateway.configuration.refresh-interval-ms:10000}"
    )
    public synchronized boolean refresh() {
        long startTime = System.currentTimeMillis();
        try {
            List<GatewayRoute> routes = managementApiClient.fetchActiveRoutes();
            routeRegistry.updateRoutes(routes);
            long durationMs = System.currentTimeMillis() - startTime;
            logger.info("Successfully synchronized {} active route(s) from Management API in {} ms",
                    routeRegistry.getRouteCount(), durationMs);
            return true;
        } catch (Exception exception) {
            long durationMs = System.currentTimeMillis() - startTime;
            logger.warn("Could not synchronize routes from Management API (took {} ms): {}",
                    durationMs, exception.getMessage());
            return false;
        }
    }
}
