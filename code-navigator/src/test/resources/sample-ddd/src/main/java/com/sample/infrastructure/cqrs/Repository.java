package com.sample.infrastructure.cqrs;

import java.util.Optional;

public interface Repository<A, ID> { Optional<A> findById(ID id); A save(A aggregate); }
