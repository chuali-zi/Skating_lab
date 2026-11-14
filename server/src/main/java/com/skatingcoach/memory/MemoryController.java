package com.skatingcoach.memory;

import com.skatingcoach.access.CurrentActorService;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;
import static com.skatingcoach.memory.MemoryModels.*;

@RestController
@RequestMapping("/api/v1/memory")
public class MemoryController {
    private final CurrentActorService actors; private final MemoryService memory;
    public MemoryController(CurrentActorService actors,MemoryService memory){this.actors=actors;this.memory=memory;}
    @GetMapping public Snapshot list(@RequestParam(required=false) String scopeId){return memory.snapshot(actors.requireActor(),scopeId);}
    @GetMapping("/{id}/source") public java.util.Map<String,Object> source(@PathVariable UUID id){return memory.source(actors.requireActor(),id);}
    @PostMapping public MemoryItem create(@RequestBody Create input){return memory.create(actors.requireActor(),input);}
    @PatchMapping("/{id}") public MemoryItem edit(@PathVariable UUID id,@RequestBody Edit input){return memory.edit(actors.requireActor(),id,input);}
    @DeleteMapping("/{id}") public Forgotten forget(@PathVariable UUID id,@RequestParam long expectedRevision){return memory.forget(actors.requireActor(),id,expectedRevision);}
}
