package com.sample.service;

import com.sample.repository.GameTypeRepository;

@Service
public class GameTypeService {
    private final GameTypeRepository repository;
    public GameTypeService(GameTypeRepository repository) { this.repository = repository; }
    public Object findAll() { return repository.findAll(); }
}
