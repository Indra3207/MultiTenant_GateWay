package in.coderarmy.gatekeeper.gateway.controller;

import in.coderarmy.gatekeeper.gateway.dto.GatewayRoute;
import in.coderarmy.gatekeeper.gateway.forwarding.ForwardingService;
import in.coderarmy.gatekeeper.gateway.registry.RouteRegistry;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Optional;

@RestController
public class GatewayController {

    private static final Logger logger = LoggerFactory.getLogger(GatewayController.class);

    private final RouteRegistry routeRegistry;
    private final ForwardingService forwardingService;

    public GatewayController(RouteRegistry routeRegistry,
                             ForwardingService forwardingService) {
        this.routeRegistry = routeRegistry;
        this.forwardingService = forwardingService;
    }

    @RequestMapping("/**")
    public ResponseEntity<byte[]> handleGatewayTraffic(
            HttpServletRequest request,
            @RequestBody(required = false) byte[] body) {

        String path = request.getRequestURI();

        // Skip internal health checks if forwarded here
        if ("/health".equals(path) || path.startsWith("/health/")) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }

        Optional<GatewayRoute> routeOpt = routeRegistry.findMatchingRoute(path);
        if (routeOpt.isEmpty()) {
            logger.info("No matching active route found for path: {}", path);
            String errorJson = String.format("{\"error\": \"Not Found\", \"message\": \"No active route matches path %s\"}", path);
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(errorJson.getBytes());
        }

        GatewayRoute route = routeOpt.get();
        return forwardingService.forward(route, request, body);
    }
}

