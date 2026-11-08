package in.coderarmy.gatekeeper.gateway.dto;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Set;

@Getter
@Setter
@NoArgsConstructor
public class GatewayRoute {

    private Long id;
    private Long organizationId;
    private String organizationName;
    private String routeId;
    private String name;
    private String pathPattern;
    private String targetBaseUrl;
    private String targetPathPrefix;
    private Integer requestsPerMinute;
    private Integer responseTimeoutMs;
    private boolean active;
    private Long configurationVersion;
    private Set<String> allowedMethods = new HashSet<String>();
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
