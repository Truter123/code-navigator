package com.agentmemory.ws;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

class EventBusTest {

    EventBus bus;
    CopyOnWriteArrayList<String> sent;
    EventBus.Subscriber fakeSub;

    @BeforeEach
    void setUp() {
        bus = new EventBus(new ObjectMapper());
        sent = new CopyOnWriteArrayList<>();
        fakeSub = new EventBus.Subscriber("test-1", sent::add, Set.of("audit", "memory"));
    }

    @Test
    void publish_sendsToMatchingTopic() {
        bus.addSubscriber(fakeSub);
        bus.publish("audit", Map.of("op", "store"));

        assertThat(sent).hasSize(1);
        assertThat(sent.get(0)).contains("\"topic\":\"audit\"");
    }

    @Test
    void publish_skipsNonMatchingTopic() {
        bus.addSubscriber(fakeSub);
        bus.publish("anomaly", Map.of("type", "loop"));

        assertThat(sent).isEmpty();
    }

    @Test
    void subscribe_updateTopics() {
        bus.addSubscriber(fakeSub);
        bus.updateTopics("test-1", Set.of("anomaly"));
        bus.publish("anomaly", Map.of("type", "drift"));

        assertThat(sent).hasSize(1);
    }

    @Test
    void unsubscribe_removesTopics() {
        bus.addSubscriber(fakeSub);
        bus.removeTopics("test-1", Set.of("audit"));
        bus.publish("audit", Map.of("op", "store"));

        assertThat(sent).isEmpty();
    }

    @Test
    void remove_cleansUpSubscriber() {
        bus.addSubscriber(fakeSub);
        bus.removeSubscriber("test-1");
        bus.publish("audit", Map.of("op", "store"));

        assertThat(sent).isEmpty();
    }
}
