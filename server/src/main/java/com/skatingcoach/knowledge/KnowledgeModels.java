package com.skatingcoach.knowledge;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class KnowledgeModels {
    private KnowledgeModels(){}
    public record Source(UUID id,String title,String sourceKind,String status,long revision,String sourceUrl,
                         String competitionKey,Instant createdAt,String errorCode){}
    public record Block(UUID id,String text,String locator){}
    public record Detail(Source source,List<Block> blocks){}
    public record TextInput(String title,String text,String sourceUrl,String competitionKey,String requestId,UUID supersedesId){}
    public record SearchResult(String mode,List<Evidence> items,List<String> warnings){}
    public record ParsedPart(String text,String locator){}
}
