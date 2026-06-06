package com.codenavigator.domain.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record GlossaryTerm(
    String name,
    String definition,
    List<String> aliases,
    String context,
    @JsonProperty("related_entities") List<String> relatedEntities,
    @JsonProperty("business_rule") String businessRule
) {
}
