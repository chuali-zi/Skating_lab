package com.skatingcoach.knowledge;
import java.util.UUID;
public record Evidence(UUID id,UUID sourceId,String title,String text,String locator,
                       String sourceUrl,long sourceVersion,String sourceKind){}
