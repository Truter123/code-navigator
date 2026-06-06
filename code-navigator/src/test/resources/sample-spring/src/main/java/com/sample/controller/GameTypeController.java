package com.sample.controller;

import com.sample.service.GameTypeService;

@RestController
@RequestMapping("/api/game-types")
public class GameTypeController {
    private final GameTypeService service;
    public GameTypeController(GameTypeService service) { this.service = service; }

    @GetMapping("")
    public Object list() { return service.findAll(); }

    @PostMapping("")
    public Object create(Object request) { return service.create(request); }

    @DeleteMapping("/{id}")
    public void delete(String id) { service.delete(id); }
}
