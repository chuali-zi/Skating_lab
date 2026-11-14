package com.skatingcoach.gateway;

public class ModelGatewayException extends RuntimeException {
    private final String code;
    public ModelGatewayException(String code, String message) {
        super(message);
        this.code = code;
    }
    public String code() { return code; }
}
