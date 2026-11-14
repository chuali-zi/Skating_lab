package com.skatingcoach.access;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skatingcoach.api.ApiError;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class ActorAuthenticationFilter extends OncePerRequestFilter {
    private final DevSessionRegistry sessions;
    private final ObjectMapper mapper;

    public ActorAuthenticationFilter(DevSessionRegistry sessions, ObjectMapper mapper) {
        this.sessions = sessions;
        this.mapper = mapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return !path.startsWith("/api/v1/") || path.equals("/api/v1/health") || path.equals("/api/v1/dev/session");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String authorization = request.getHeader("Authorization");
        var actor = authorization != null && authorization.startsWith("Bearer ")
                ? sessions.authenticate(authorization.substring(7)) : java.util.Optional.<CurrentActor>empty();
        if (actor.isEmpty()) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            mapper.writeValue(response.getOutputStream(), new ApiError("AUTHENTICATION_REQUIRED", "请先建立授权会话。"));
            return;
        }
        request.setAttribute(CurrentActorService.REQUEST_ATTRIBUTE, actor.get());
        chain.doFilter(request, response);
    }
}
