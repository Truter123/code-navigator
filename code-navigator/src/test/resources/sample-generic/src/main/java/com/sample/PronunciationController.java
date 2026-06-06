package com.sample;

public class PronunciationController {
    private final PronunciationService service;
    public PronunciationController(PronunciationService service) { this.service = service; }
    public Object getAll() { return service.findAll(); }
}
