package com.agentmemory.ws;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

public class EventBus {

    private final CopyOnWriteArrayList<Subscriber> subscribers = new CopyOnWriteArrayList<>();
    private final ObjectMapper mapper;
    private final long throttleMs;
    private final ConcurrentHashMap<String, ConcurrentLinkedQueue<String>> buffers = new ConcurrentHashMap<>();
    private ScheduledExecutorService scheduler;

    public EventBus(ObjectMapper mapper) {
        this(mapper, 0);
    }

    public EventBus(ObjectMapper mapper, long throttleMs) {
        this.mapper = mapper;
        this.throttleMs = throttleMs;
        if (throttleMs > 0) {
            scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "ws-throttle");
                t.setDaemon(true);
                return t;
            });
            scheduler.scheduleAtFixedRate(this::flush, throttleMs, throttleMs, TimeUnit.MILLISECONDS);
        }
    }

    public void addSubscriber(Subscriber subscriber) {
        subscribers.add(subscriber);
    }

    public void removeSubscriber(String id) {
        subscribers.removeIf(s -> s.id.equals(id));
    }

    public void updateTopics(String id, Set<String> topics) {
        for (Subscriber s : subscribers) {
            if (s.id.equals(id)) {
                s.topics = new HashSet<>(topics);
                return;
            }
        }
    }

    public void removeTopics(String id, Set<String> topics) {
        for (Subscriber s : subscribers) {
            if (s.id.equals(id)) {
                s.topics.removeAll(topics);
                return;
            }
        }
    }

    public void publish(String topic, Object data) {
        WsMessage msg = WsMessage.event(topic, data);
        String json;
        try {
            json = mapper.writeValueAsString(msg);
        } catch (Exception e) {
            return;
        }
        for (Subscriber s : subscribers) {
            if (s.topics.contains(topic) || s.topics.contains("*")) {
                if (throttleMs > 0) {
                    buffers.computeIfAbsent(s.id, k -> new ConcurrentLinkedQueue<>()).add(json);
                } else {
                    try {
                        s.sender.accept(json);
                    } catch (Exception ignored) {
                    }
                }
            }
        }
    }

    private void flush() {
        for (Subscriber s : subscribers) {
            ConcurrentLinkedQueue<String> queue = buffers.get(s.id);
            if (queue == null || queue.isEmpty()) continue;
            List<String> events = new ArrayList<>();
            String item;
            while ((item = queue.poll()) != null) events.add(item);
            if (events.isEmpty()) continue;
            try {
                String batch = "{\"type\":\"batch\",\"events\":[" + String.join(",", events) + "]}";
                s.sender.accept(batch);
            } catch (Exception ignored) {
            }
        }
    }

    public static class Subscriber {
        final String id;
        final Consumer<String> sender;
        volatile Set<String> topics;

        public Subscriber(String id, Consumer<String> sender, Set<String> topics) {
            this.id = id;
            this.sender = sender;
            this.topics = new HashSet<>(topics);
        }
    }
}
