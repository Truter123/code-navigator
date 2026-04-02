package com.agentmemory.ws;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.javalin.router.JavalinDefaultRoutingApi;

import java.util.HashSet;
import java.util.Set;

public class WebSocketHandler {

    private final EventBus eventBus;
    private final ObjectMapper mapper;
    private final Set<String> defaultTopics;

    public WebSocketHandler(EventBus eventBus, ObjectMapper mapper, Set<String> defaultTopics) {
        this.eventBus = eventBus;
        this.mapper = mapper;
        this.defaultTopics = defaultTopics;
    }

    public void register(JavalinDefaultRoutingApi app) {
        app.ws("/ws/events", ws -> {
            ws.onConnect(ctx -> {
                String id = ctx.sessionId();
                var subscriber = new EventBus.Subscriber(
                        id,
                        ctx::send,
                        new HashSet<>(defaultTopics)
                );
                eventBus.addSubscriber(subscriber);
            });

            ws.onMessage(ctx -> {
                try {
                    WsCommand cmd = mapper.readValue(ctx.message(), WsCommand.class);
                    String id = ctx.sessionId();
                    if ("subscribe".equals(cmd.type()) && cmd.topics() != null) {
                        eventBus.updateTopics(id, Set.copyOf(cmd.topics()));
                    } else if ("unsubscribe".equals(cmd.type()) && cmd.topics() != null) {
                        eventBus.removeTopics(id, Set.copyOf(cmd.topics()));
                    }
                } catch (Exception ignored) {
                    // unknown message type — silently ignore per spec
                }
            });

            ws.onClose(ctx -> eventBus.removeSubscriber(ctx.sessionId()));
            ws.onError(ctx -> eventBus.removeSubscriber(ctx.sessionId()));
        });
    }
}
