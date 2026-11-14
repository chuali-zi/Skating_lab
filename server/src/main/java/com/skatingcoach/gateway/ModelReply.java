package com.skatingcoach.gateway;

public record ModelReply(String text, String model, long inputTokens, long outputTokens) {}
