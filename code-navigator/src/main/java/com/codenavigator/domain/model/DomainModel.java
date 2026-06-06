package com.codenavigator.domain.model;

import java.util.List;

public record DomainModel(
    String projectName,
    String projectPath,
    List<BoundedContext> contexts,
    List<GlossaryTerm> terms,
    List<BusinessFlow> flows,
    List<BusinessRule> rules,
    List<DomainEntity> entities
) {}
