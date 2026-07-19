package com.codenavigator.domain.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record BusinessRule(
    String name,
    String description,
    String context,
    String entity,
    Severity severity,
    String invariant
) {
}
