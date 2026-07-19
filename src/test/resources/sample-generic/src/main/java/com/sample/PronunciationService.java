package com.sample;

public class PronunciationService {
    private final PronunciationRepository repository;
    public PronunciationService(PronunciationRepository repository) { this.repository = repository; }
    public Object findAll() { return repository.findAll(); }
}
