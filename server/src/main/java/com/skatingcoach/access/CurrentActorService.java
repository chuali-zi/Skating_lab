package com.skatingcoach.access;

import com.skatingcoach.api.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class CurrentActorService {
    public static final String REQUEST_ATTRIBUTE = CurrentActor.class.getName();
    private final HttpServletRequest request;

    public CurrentActorService(HttpServletRequest request) {
        this.request = request;
    }

    public CurrentActor requireActor() {
        Object value = request.getAttribute(REQUEST_ATTRIBUTE);
        if (value instanceof CurrentActor actor) {
            return actor;
        }
        throw new ApiException(HttpStatus.UNAUTHORIZED, "AUTHENTICATION_REQUIRED", "请先建立授权会话。");
    }
}
