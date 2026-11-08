package in.coderarmy.gatekeeper.gateway.forwarding;

import in.coderarmy.gatekeeper.gateway.dto.GatewayRoute;
import in.coderarmy.gatekeeper.gateway.ratelimit.FixedWindowRateLimiter;
import in.coderarmy.gatekeeper.gateway.security.UpstreamSafetyValidator;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.util.AntPathMatcher;

import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Service
public class ForwardingService {

    private static final Logger logger = LoggerFactory.getLogger(ForwardingService.class);

    private static final Set<String> HOP_BY_HOP_HEADERS = Set.of(
            "connection",
            "keep-alive",
            "proxy-authenticate",
            "proxy-authorization",
            "te",
            "trailers",
            "transfer-encoding",
            "upgrade",
            "host",
            "content-length"
    );

    private final AntPathMatcher pathMatcher = new AntPathMatcher();
    private final FixedWindowRateLimiter rateLimiter;
    private final UpstreamSafetyValidator safetyValidator;
    private final HttpClient httpClient;

    public ForwardingService(FixedWindowRateLimiter rateLimiter,
                             UpstreamSafetyValidator safetyValidator) {
        this.rateLimiter = rateLimiter;
        this.safetyValidator = safetyValidator;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    /**
     * Forwards an incoming request to the upstream target defined in the matching route.
     */
    public ResponseEntity<byte[]> forward(GatewayRoute route, HttpServletRequest request, byte[] body) {
        String method = request.getMethod().toUpperCase(Locale.ENGLISH);

        // 1. Method Validation
        if (!isMethodAllowed(route, method)) {
            HttpHeaders headers = new HttpHeaders();
            Set<HttpMethod> allowed = route.getAllowedMethods() != null
                    ? route.getAllowedMethods().stream()
                            .map(m -> HttpMethod.valueOf(m.toUpperCase(Locale.ENGLISH)))
                            .collect(java.util.stream.Collectors.toSet())
                    : java.util.Collections.emptySet();
            headers.setAllow(allowed);
            return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
                    .headers(headers)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(String.format("{\"error\": \"Method Not Allowed\", \"message\": \"Method %s is not permitted for route %s\"}",
                            method, route.getRouteId()).getBytes());
        }

        // 2. Rate Limiting
        String clientIp = rateLimiter.resolveClientIp(request);
        if (!rateLimiter.isAllowed(route.getOrganizationId(), route.getRouteId(), clientIp, route.getRequestsPerMinute())) {
            HttpHeaders headers = new HttpHeaders();
            headers.add("Retry-After", "60");
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .headers(headers)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(String.format("{\"error\": \"Too Many Requests\", \"message\": \"Rate limit of %d requests/minute exceeded for route %s\"}",
                            route.getRequestsPerMinute(), route.getRouteId()).getBytes());
        }

        // 3. Path Rewriting & URI Construction
        URI targetUri;
        try {
            targetUri = buildTargetUri(route, request);
        } catch (Exception ex) {
            logger.error("Failed to construct target URI for route {}: {}", route.getRouteId(), ex.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(String.format("{\"error\": \"Bad Request\", \"message\": \"Invalid target URI: %s\"}", ex.getMessage()).getBytes());
        }

        // 4. Upstream Safety / SSRF Validation
        try {
            safetyValidator.validate(targetUri);
        } catch (SecurityException ex) {
            logger.warn("Upstream safety check rejected target {}: {}", targetUri, ex.getMessage());
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(String.format("{\"error\": \"Forbidden\", \"message\": \"%s\"}", ex.getMessage()).getBytes());
        }

        // 5. Build Upstream HTTP Request
        int timeoutMs = (route.getResponseTimeoutMs() != null && route.getResponseTimeoutMs() > 0)
                ? route.getResponseTimeoutMs()
                : 3000;

        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                .uri(targetUri)
                .timeout(Duration.ofMillis(timeoutMs));

        // Copy incoming headers (stripping hop-by-hop)
        Enumeration<String> headerNames = request.getHeaderNames();
        if (headerNames != null) {
            while (headerNames.hasMoreElements()) {
                String name = headerNames.nextElement();
                if (HOP_BY_HOP_HEADERS.contains(name.toLowerCase(Locale.ENGLISH))) {
                    continue;
                }
                Enumeration<String> values = request.getHeaders(name);
                while (values.hasMoreElements()) {
                    String value = values.nextElement();
                    try {
                        requestBuilder.header(name, value);
                    } catch (IllegalArgumentException ex) {
                        logger.debug("Skipping restricted header {}: {}", name, ex.getMessage());
                    }
                }
            }
        }

        // Set Request Method & Body
        HttpRequest.BodyPublisher bodyPublisher = (body != null && body.length > 0)
                ? HttpRequest.BodyPublishers.ofByteArray(body)
                : HttpRequest.BodyPublishers.noBody();
        requestBuilder.method(method, bodyPublisher);

        // 6. Execute Forwarding
        logger.debug("Forwarding {} {} -> {}", method, request.getRequestURI(), targetUri);
        try {
            HttpResponse<byte[]> upstreamResponse = httpClient.send(
                    requestBuilder.build(),
                    HttpResponse.BodyHandlers.ofByteArray()
            );

            // Copy upstream response headers
            HttpHeaders responseHeaders = new HttpHeaders();
            upstreamResponse.headers().map().forEach((headerName, headerValues) -> {
                if (!HOP_BY_HOP_HEADERS.contains(headerName.toLowerCase(Locale.ENGLISH))) {
                    for (String val : headerValues) {
                        responseHeaders.add(headerName, val);
                    }
                }
            });

            byte[] responseBody = upstreamResponse.body() != null ? upstreamResponse.body() : new byte[0];
            return ResponseEntity.status(upstreamResponse.statusCode())
                    .headers(responseHeaders)
                    .body(responseBody);

        } catch (HttpTimeoutException ex) {
            logger.warn("Upstream timed out after {} ms for route {}: {}", timeoutMs, route.getRouteId(), targetUri);
            return ResponseEntity.status(HttpStatus.GATEWAY_TIMEOUT)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(String.format("{\"error\": \"Gateway Timeout\", \"message\": \"Upstream destination %s timed out after %d ms\"}",
                            targetUri.getHost(), timeoutMs).getBytes());

        } catch (ConnectException ex) {
            logger.warn("Connection refused for upstream route {}: {}", route.getRouteId(), targetUri);
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(String.format("{\"error\": \"Bad Gateway\", \"message\": \"Cannot connect to upstream destination %s\"}",
                            targetUri.getHost()).getBytes());

        } catch (IOException | InterruptedException ex) {
            logger.error("I/O error forwarding to {}: {}", targetUri, ex.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(String.format("{\"error\": \"Bad Gateway\", \"message\": \"Failed to reach upstream: %s\"}",
                            ex.getMessage()).getBytes());
        }
    }

    public URI buildTargetUri(GatewayRoute route, HttpServletRequest request) {
        String base = route.getTargetBaseUrl() != null ? route.getTargetBaseUrl().trim() : "";
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }

        String prefix = route.getTargetPathPrefix() != null ? route.getTargetPathPrefix().trim() : "";
        if (!prefix.isEmpty() && !prefix.startsWith("/")) {
            prefix = "/" + prefix;
        }

        String remainingPath = pathMatcher.extractPathWithinPattern(route.getPathPattern(), request.getRequestURI());
        if (remainingPath != null && !remainingPath.isEmpty() && !remainingPath.startsWith("/")) {
            remainingPath = "/" + remainingPath;
        }

        // Combine prefix and remaining path cleanly
        String combinedPath;
        if (prefix.endsWith("/") && remainingPath != null && remainingPath.startsWith("/")) {
            combinedPath = prefix + remainingPath.substring(1);
        } else {
            combinedPath = prefix + (remainingPath != null ? remainingPath : "");
        }

        if (combinedPath.isEmpty()) {
            combinedPath = "/";
        }

        String query = request.getQueryString();
        String fullUriString = base + combinedPath + (query != null && !query.isEmpty() ? "?" + query : "");
        return URI.create(fullUriString);
    }

    private boolean isMethodAllowed(GatewayRoute route, String method) {
        if (route.getAllowedMethods() == null || route.getAllowedMethods().isEmpty()) {
            return true;
        }
        for (String allowed : route.getAllowedMethods()) {
            if (allowed.equalsIgnoreCase(method)) {
                return true;
            }
        }
        return false;
    }
}

