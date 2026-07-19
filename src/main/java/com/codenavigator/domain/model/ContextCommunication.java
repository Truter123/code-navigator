package com.codenavigator.domain.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record ContextCommunication(
    String context,
    String type,
    String via
) {}
