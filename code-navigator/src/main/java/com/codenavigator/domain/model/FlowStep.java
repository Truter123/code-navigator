package com.codenavigator.domain.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record FlowStep(
    String action,
    String actor,
    @JsonProperty("on_failure") String onFailure
) {}
