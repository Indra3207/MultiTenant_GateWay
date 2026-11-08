package in.coderarmy.gatekeeper.gateway.ratelimit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class FixedWindowRateLimiterTest {

    private FixedWindowRateLimiter limiter;

    @BeforeEach
    void setUp() {
        limiter = new FixedWindowRateLimiter(Set.of("10.0.0.1", "127.0.0.1"));
    }

    @Test
    @DisplayName("Allows requests up to limit and rejects exceeding requests within the window")
    void isAllowed_enforcesLimit() {
        Long orgId = 1L;
        String routeId = "orders-route";
        String clientIp = "192.168.1.100";
        int limit = 3;

        assertThat(limiter.isAllowed(orgId, routeId, clientIp, limit)).isTrue();
        assertThat(limiter.isAllowed(orgId, routeId, clientIp, limit)).isTrue();
        assertThat(limiter.isAllowed(orgId, routeId, clientIp, limit)).isTrue();

        // 4th request exceeds limit of 3
        assertThat(limiter.isAllowed(orgId, routeId, clientIp, limit)).isFalse();
    }

    @Test
    @DisplayName("Routes without limit (null or 0) allow unlimited requests")
    void isAllowed_unlimitedWhenNullOrZero() {
        Long orgId = 1L;
        String routeId = "public-route";
        String clientIp = "10.0.0.1";

        for (int i = 0; i < 100; i++) {
            assertThat(limiter.isAllowed(orgId, routeId, clientIp, null)).isTrue();
            assertThat(limiter.isAllowed(orgId, routeId, clientIp, 0)).isTrue();
        }
    }

    @Test
    @DisplayName("Maintains distinct rate limit buckets for identical route IDs across different tenants")
    void isAllowed_identicalRouteIdAcrossDifferentTenants_isolated() {
        Long tenantA = 100L;
        Long tenantB = 200L;
        String sameRouteId = "orders-api";
        String clientIp = "192.168.1.50";
        int limit = 1;

        // Tenant A exhausts their limit
        assertThat(limiter.isAllowed(tenantA, sameRouteId, clientIp, limit)).isTrue();
        assertThat(limiter.isAllowed(tenantA, sameRouteId, clientIp, limit)).isFalse();

        // Tenant B with the SAME routeId and SAME client IP has their own independent bucket
        assertThat(limiter.isAllowed(tenantB, sameRouteId, clientIp, limit)).isTrue();
        assertThat(limiter.isAllowed(tenantB, sameRouteId, clientIp, limit)).isFalse();
    }

    @Test
    @DisplayName("Spoofed X-Forwarded-For from untrusted remote address is ignored and remoteAddr is used")
    void resolveClientIp_untrustedProxy_ignoresSpoofedHeader() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("198.51.100.5"); // Untrusted client IP
        request.addHeader("X-Forwarded-For", "203.0.113.199, 10.0.0.2");

        String resolvedIp = limiter.resolveClientIp(request);

        // Header must be ignored, returning remoteAddr directly
        assertThat(resolvedIp).isEqualTo("198.51.100.5");
    }

    @Test
    @DisplayName("X-Forwarded-For is trusted when remoteAddr matches an explicitly configured trusted proxy")
    void resolveClientIp_trustedProxy_trustsHeader() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("10.0.0.1"); // Configured as trusted proxy in setUp
        request.addHeader("X-Forwarded-For", "203.0.113.42, 198.51.100.1");

        String resolvedIp = limiter.resolveClientIp(request);

        // Header is trusted, returning the first client IP in chain
        assertThat(resolvedIp).isEqualTo("203.0.113.42");
    }

    @Test
    @DisplayName("Defaults to remoteAddr when trusted proxy sends empty or blank X-Forwarded-For")
    void resolveClientIp_trustedProxy_emptyHeader_fallsBackToRemoteAddr() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("10.0.0.1");
        request.addHeader("X-Forwarded-For", "   ");

        String resolvedIp = limiter.resolveClientIp(request);

        assertThat(resolvedIp).isEqualTo("10.0.0.1");
    }
}
