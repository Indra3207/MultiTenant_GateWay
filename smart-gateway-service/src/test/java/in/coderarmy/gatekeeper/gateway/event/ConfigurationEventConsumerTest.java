package in.coderarmy.gatekeeper.gateway.event;

import in.coderarmy.gatekeeper.gateway.service.GatewayConfigurationLoader;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class ConfigurationEventConsumerTest {

    @Mock
    private GatewayConfigurationLoader configurationLoader;

    @InjectMocks
    private ConfigurationEventConsumer eventConsumer;

    @Test
    @DisplayName("Kafka event consumer invokes configurationLoader.refresh()")
    void configurationChanged_invokesRefresh() {
        String eventPayload = "{\"eventId\": \"evt-123\", \"changeType\": \"CREATED\", \"routeId\": \"orders\"}";

        eventConsumer.configurationChanged(eventPayload);

        verify(configurationLoader).refresh();
    }
}

