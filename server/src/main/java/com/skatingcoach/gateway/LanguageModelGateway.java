package com.skatingcoach.gateway;

public interface LanguageModelGateway {
    boolean available();

    ModelReply generate(String systemInstruction, String userInput);
}
