package in.coderarmy.gatekeeper.gateway.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URI;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UpstreamSafetyValidatorTest {

    @Test
    @DisplayName("Blocks cloud metadata endpoint regardless of mode")
    void validate_blocksCloudMetadata() {
        UpstreamSafetyValidator devValidator = new UpstreamSafetyValidator(true, true);
        UpstreamSafetyValidator prodValidator = new UpstreamSafetyValidator(true, false);

        URI metadataUri = URI.create("http://169.254.169.254/latest/meta-data");

        assertThatThrownBy(() -> devValidator.validate(metadataUri))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("cloud metadata");

        assertThatThrownBy(() -> prodValidator.validate(metadataUri))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("cloud metadata");
    }

    @Test
    @DisplayName("Blocks loopback in production mode but allows loopback in development mode")
    void validate_loopbackBehavior() {
        UpstreamSafetyValidator prodValidator = new UpstreamSafetyValidator(true, false);
        UpstreamSafetyValidator devValidator = new UpstreamSafetyValidator(true, true);

        URI loopbackUri = URI.create("http://127.0.0.1:8080/service");
        URI localhostUri = URI.create("http://localhost:8080/service");

        // Production blocks loopback
        assertThatThrownBy(() -> prodValidator.validate(loopbackUri))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("loopback");

        assertThatThrownBy(() -> prodValidator.validate(localhostUri))
                .isInstanceOf(SecurityException.class);

        // Development mode permits loopback for local mock backends
        assertThatCode(() -> devValidator.validate(loopbackUri)).doesNotThrowAnyException();
        assertThatCode(() -> devValidator.validate(localhostUri)).doesNotThrowAnyException();
    }
}

