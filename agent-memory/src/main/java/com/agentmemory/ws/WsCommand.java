package com.agentmemory.ws;

import java.util.List;

public record WsCommand(String type, List<String> topics) {}
