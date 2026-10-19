package com.hmdp;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HmDianPingApplicationYamlTest {

    @Test
    @SuppressWarnings("unchecked")
    void shouldDisableRabbitAutoConfiguration() {
        InputStream inputStream = HmDianPingApplicationYamlTest.class
                .getClassLoader()
                .getResourceAsStream("application.yaml");
        Map<String, Object> root = new Yaml().load(inputStream);
        Map<String, Object> spring = (Map<String, Object>) root.get("spring");
        Map<String, Object> autoconfigure = (Map<String, Object>) spring.get("autoconfigure");
        List<String> exclude = (List<String>) autoconfigure.get("exclude");

        assertTrue(exclude.contains("org.springframework.boot.autoconfigure.amqp.RabbitAutoConfiguration"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldKeepRocketMqTimeoutAndFlashPayConfig() {
        InputStream inputStream = HmDianPingApplicationYamlTest.class
                .getClassLoader()
                .getResourceAsStream("application.yaml");
        Map<String, Object> root = new Yaml().load(inputStream);

        Map<String, Object> rocketmq = (Map<String, Object>) root.get("rocketmq");
        Map<String, Object> producer = (Map<String, Object>) rocketmq.get("producer");
        assertEquals(10000, ((Number) producer.get("send-message-timeout")).intValue());

        Map<String, Object> flashPay = (Map<String, Object>) root.get("flash-pay");
        Map<String, Object> payoutRules = (Map<String, Object>) flashPay.get("payout-rules");
        assertEquals(50, ((Number) payoutRules.get("FLASH_PAY")).intValue());
        assertEquals(500, ((Number) flashPay.get("max-payout")).intValue());
    }
}
