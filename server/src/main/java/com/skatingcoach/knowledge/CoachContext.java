package com.skatingcoach.knowledge;
import java.util.List;
public record CoachContext(long memoryRevision,long privacyEpoch,long knowledgeRevision,
                           String text,List<Evidence> evidence){}
