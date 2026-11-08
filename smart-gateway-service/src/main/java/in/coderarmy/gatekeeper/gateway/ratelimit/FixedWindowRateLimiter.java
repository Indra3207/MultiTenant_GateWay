package in.coderarmy.gatekeeper.gateway.ratelimit;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

@Component
public class FixedWindowRateLimiter {

    private static final long WINDOW_SIZE_MS = 60_000L;

    private static final class WindowCounter {
        final long windowEpochMinute;
        final AtomicInteger count;

        WindowCounter(long windowEpochMinute, int initialCount) {
            this.windowEpochMinute = windowEpochMinute;
            this.count = new AtomicInteger(initialCount);
        }
    }

    private final Set<String> trustedProxies;
    private final ConcurrentMap<String, WindowCounter> counters = new ConcurrentHashMap<>();

    public FixedWindowRateLimiter() {
        this(Collections.emptySet());
    }

    @Autowired
    public FixedWindowRateLimiter(
            @Value("${gateway.trusted-proxies:}") String trustedProxiesConfig) {
        this(parseTrustedProxies(trustedProxiesConfig));
    }

    public FixedWindowRateLimiter(Set<String> trustedProxies) {
        this.trustedProxies = trustedProxies != null ? trustedProxies : Collections.emptySet();
    }

    private static Set<String> parseTrustedProxies(String config) {
        if (config == null || config.isBlank()) {
            return Collections.emptySet();
        }
        return Arrays.stream(config.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toSet());
    }

    /**
     * Resolves the client IP address from the request.
     * Uses request.getRemoteAddr() by default.
     * Trusts X-Forwarded-For only if remoteAddr matches an explicitly configured trusted proxy.
     */
    public String resolveClientIp(HttpServletRequest request) {
        if (request == null) {
            return "unknown";
        }
        String remoteAddr = request.getRemoteAddr();
        if (remoteAddr != null && !remoteAddr.isBlank() && trustedProxies.contains(remoteAddr.trim())) {
            String xForwardedFor = request.getHeader("X-Forwarded-For");
            if (xForwardedFor != null && !xForwardedFor.isBlank()) {
                String[] parts = xForwardedFor.split(",");
                if (parts.length > 0 && !parts[0].isBlank()) {
                    return parts[0].trim();
                }
            }
        }
        return (remoteAddr != null && !remoteAddr.isBlank()) ? remoteAddr.trim() : "unknown";
    }

    /**
     * Checks if the request is within the allowed requests-per-minute quota.
     * Buckets are keyed by organizationId + routeId + clientIp.
     *
     * @param organizationId    the tenant organization ID
     * @param routeId           the route identifier
     * @param clientIp          the resolved client IP address
     * @param requestsPerMinute allowed requests per minute; null or <= 0 indicates no rate limit
     * @return true if request is permitted, false if rate limit is exceeded
     */
    public boolean isAllowed(Long organizationId, String routeId, String clientIp, Integer requestsPerMinute) {
        if (requestsPerMinute == null || requestsPerMinute <= 0) {
            return true;
        }

        long currentWindow = System.currentTimeMillis() / WINDOW_SIZE_MS;
        String key = (organizationId != null ? organizationId : 0L) + ":" +
                     (routeId != null ? routeId : "unknown") + ":" +
                     (clientIp != null ? clientIp : "unknown");

        WindowCounter counter = counters.compute(key, (k, existing) -> {
            if (existing == null || existing.windowEpochMinute != currentWindow) {
                return new WindowCounter(currentWindow, 1);
            }
            existing.count.incrementAndGet();
            return existing;
        });

        // Periodically prune old windows if map grows large
        if (counters.size() > 5000) {
            cleanupOldWindows(currentWindow);
        }

        return counter.count.get() <= requestsPerMinute;
    }

    public boolean isAllowed(String routeId, String clientIp, Integer requestsPerMinute) {
        return isAllowed(null, routeId, clientIp, requestsPerMinute);
    }

    private void cleanupOldWindows(long currentWindow) {
        counters.entrySet().removeIf(entry -> entry.getValue().windowEpochMinute < currentWindow);
    }

    public void clear() {
        counters.clear();
    }

    public Set<String> getTrustedProxies() {
        return Collections.unmodifiableSet(trustedProxies);
    }
}
