package com.skatingcoach.memory;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class MemoryModels {
    private MemoryModels() {}
    public record MemoryItem(UUID id,String kind,String subjectKey,String statement,String scopeType,
            String scopeId,String evidenceKind,String status,long revision,UUID sourceId,
            Instant observedAt,Instant validUntil) {}
    public record Snapshot(long revision,long privacyEpoch,List<MemoryItem> items) {}
    public record Create(String statement,String kind,String subjectKey,String scopeType,String scopeId,String requestId) {}
    public record Edit(String statement,long expectedRevision,String scopeType,String scopeId) {}
    public record Forgotten(String status,long privacyEpoch) {}
    public record ExtractionResult(String status,int saved) {}
}
