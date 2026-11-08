package in.coderarmy.gatekeeper.gateway.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Set;

@Component
public class UpstreamSafetyValidator {

    private static final Logger logger = LoggerFactory.getLogger(UpstreamSafetyValidator.class);

    private static final Set<String> CLOUD_METADATA_HOSTS = Set.of(
            "169.254.169.254",
            "metadata.google.internal",
            "100.100.100.200"
    );

    private final boolean safetyEnabled;
    private final boolean developmentMode;

    public UpstreamSafetyValidator(
            @Value("${gateway.safety.enabled:true}") boolean safetyEnabled,
            @Value("${gateway.safety.development-mode:false}") boolean developmentMode) {
        this.safetyEnabled = safetyEnabled;
        this.developmentMode = developmentMode;
    }

    /**
     * Validates that the target URI does not target dangerous internal, loopback, or cloud metadata endpoints.
     *
     * @param targetUri the upstream destination URI
     * @throws SecurityException if the target destination violates SSRF safety rules
     */
    public void validate(URI targetUri) {
        if (!safetyEnabled) {
            return;
        }

        String host = targetUri.getHost();
        if (host == null || host.isBlank()) {
            throw new SecurityException("Invalid target host: " + targetUri);
        }

        // Always block cloud metadata IP and domains regardless of mode
        if (CLOUD_METADATA_HOSTS.contains(host.toLowerCase())) {
            throw new SecurityException("Access to cloud metadata service is prohibited: " + host);
        }

        try {
            InetAddress[] addresses = InetAddress.getAllByName(host);
            for (InetAddress address : addresses) {
                String ip = address.getHostAddress();

                if (CLOUD_METADATA_HOSTS.contains(ip)) {
                    throw new SecurityException("Access to cloud metadata service is prohibited: " + ip);
                }

                boolean isLoopback = address.isLoopbackAddress() || "localhost".equalsIgnoreCase(host);

                if (isLoopback) {
                    if (developmentMode) {
                        logger.debug("Allowing loopback upstream {} because development mode is enabled", host);
                        continue;
                    }
                    throw new SecurityException("Access to loopback address is forbidden: " + host);
                }

                if (address.isSiteLocalAddress()) {
                    if (developmentMode) {
                        logger.debug("Allowing private site-local upstream {} in development mode", host);
                        continue;
                    }
                    throw new SecurityException("Access to private network address is forbidden: " + host);
                }

                if (address.isLinkLocalAddress()) {
                    throw new SecurityException("Access to link-local address is forbidden: " + host);
                }

                if (address.isAnyLocalAddress()) {
                    throw new SecurityException("Access to wildcard local address is forbidden: " + host);
                }
            }
        } catch (UnknownHostException ex) {
            logger.warn("DNS resolution failed for upstream host: {}", host);
            throw new SecurityException("Cannot resolve upstream host: " + host, ex);
        }
    }

    public boolean isDevelopmentMode() {
        return developmentMode;
    }
}

