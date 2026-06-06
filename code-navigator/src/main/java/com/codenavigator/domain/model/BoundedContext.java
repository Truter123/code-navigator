package com.codenavigator.domain.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record BoundedContext(
    String name,
    String description,
    String owner,
    List<String> entities,
    @JsonProperty("communicates_with") List<ContextCommunication> communicatesWith
) {
}
