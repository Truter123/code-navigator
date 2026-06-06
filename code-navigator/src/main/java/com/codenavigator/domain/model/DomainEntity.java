package com.codenavigator.domain.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record DomainEntity(
    String name,
    String context,
    String type,
    String description,
    List<EntityField> fields,
    @JsonProperty("code_mapping") String codeMapping
) {
}
