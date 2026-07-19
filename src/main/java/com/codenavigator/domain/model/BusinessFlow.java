package com.codenavigator.domain.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record BusinessFlow(
    String name,
    String description,
    String context,
    String trigger,
    List<FlowStep> steps,
    String outcome
) {
}
