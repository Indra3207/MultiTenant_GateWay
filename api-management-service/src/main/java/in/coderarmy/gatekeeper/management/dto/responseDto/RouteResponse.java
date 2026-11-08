package in.coderarmy.gatekeeper.management.dto.responseDto;

import in.coderarmy.gatekeeper.management.entity.ApiRoute;
import lombok.AllArgsConstructor;
import lombok.Getter;

import java.time.LocalDateTime;
import java.util.Set;

@Getter
@AllArgsConstructor
public class RouteResponse {

    private Long id;
    private Long organizationId;
    private String routeId;
    private String name;
    private String pathPattern;
    private String targetBaseUrl;
    private String targetPathPrefix;
    private Integer requestsPerMinute;
    private Integer responseTimeoutMs;
    private boolean active;
    private Long configurationVersion;
    private Set<String> allowedMethods;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public static RouteResponse from(ApiRoute route) {
        return new RouteResponse(route.getId(), route.getOrganization().getId(), route.getRouteId(),
                route.getName(), route.getPathPattern(), route.getTargetBaseUrl(), route.getTargetPathPrefix(),
                route.getRequestsPerMinute(), route.getResponseTimeoutMs(),
                route.isActive(), route.getConfigurationVersion(), route.getAllowedMethods(),
                route.getCreatedAt(), route.getUpdatedAt());
    }
}
