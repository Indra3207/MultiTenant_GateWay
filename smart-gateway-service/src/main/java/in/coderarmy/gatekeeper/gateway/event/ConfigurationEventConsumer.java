package in.coderarmy.gatekeeper.gateway.event;

import in.coderarmy.gatekeeper.gateway.service.GatewayConfigurationLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class ConfigurationEventConsumer {

    private static final Logger logger = LoggerFactory.getLogger(ConfigurationEventConsumer.class);

    private final GatewayConfigurationLoader configurationLoader;

    public ConfigurationEventConsumer(GatewayConfigurationLoader configurationLoader) {
        this.configurationLoader = configurationLoader;
    }

    @KafkaListener(topics = "${gateway.kafka.configuration-topic}",
            groupId = "${gateway.kafka.configuration-group}")
    public void configurationChanged(String event) {
        logger.info("Kafka configuration change event received: {}", event);
        boolean refreshed = configurationLoader.refresh();
        if (refreshed) {
            logger.info("Gateway configuration synchronized successfully following Kafka event");
        } else {
            logger.warn("Gateway configuration synchronization failed following Kafka event");
        }
    }
}
