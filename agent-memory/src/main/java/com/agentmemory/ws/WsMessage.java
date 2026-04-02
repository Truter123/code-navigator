package com.agentmemory.ws;

import java.time.Instant;

public record WsMessage(String type, String topic, Object data, String ts) {

    public static WsMessage event(String topic, Object data) {
        return new WsMessage("event", topic, data, Instant.now().toString());
    }
}
