package com.skatingcoach.access;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skatingcoach.api.ApiException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import static org.assertj.core.api.Assertions.*;

class ActorAuthenticationTest {
    @Test void aClientSuppliedAthleteIdCannotAuthenticate() throws Exception {
        var request = new MockHttpServletRequest("GET", "/api/v1/plans");
        request.addHeader("X-Athlete-Id", DevSessionRegistry.DEMO_ATHLETE_ID.toString());
        var response = new MockHttpServletResponse();
        new ActorAuthenticationFilter(new DevSessionRegistry(), new ObjectMapper())
                .doFilter(request, response, new MockFilterChain());
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(request.getAttribute(CurrentActorService.REQUEST_ATTRIBUTE)).isNull();
    }

    @Test void authenticatedSubjectComesFromServerSession() throws Exception {
        var sessions = new DevSessionRegistry();
        var session = sessions.create();
        var request = new MockHttpServletRequest("GET", "/api/v1/plans");
        request.addHeader("Authorization", "Bearer " + session.token());
        request.addHeader("X-Athlete-Id", "00000000-0000-0000-0000-000000000999");
        var response = new MockHttpServletResponse();
        new ActorAuthenticationFilter(sessions, new ObjectMapper()).doFilter(request, response, new MockFilterChain());
        assertThat(request.getAttribute(CurrentActorService.REQUEST_ATTRIBUTE)).isEqualTo(session.actor());
    }

    @Test void expiredTokensStopAuthenticating() {
        class MutableClock extends Clock {
            Instant now = Instant.parse("2026-11-14T00:00:00Z");
            @Override public ZoneId getZone() { return ZoneOffset.UTC; }
            @Override public Clock withZone(ZoneId zone) { return this; }
            @Override public Instant instant() { return now; }
        }
        var clock = new MutableClock();
        var sessions = new DevSessionRegistry(clock);
        var session = sessions.create();
        assertThat(sessions.authenticate(session.token())).isPresent();
        clock.now = session.expiresAt();
        assertThat(sessions.authenticate(session.token())).isEmpty();
    }

    @Test void devLoginRequiresBothExplicitProfileAndLoopback() {
        var request = new MockHttpServletRequest();
        request.setRemoteAddr("127.0.0.1");
        var environment = new MockEnvironment();
        assertThatThrownBy(() -> new DevSessionController(new DevSessionRegistry(), environment, true).create(request))
                .isInstanceOf(ApiException.class);
        environment.setActiveProfiles("dev");
        var controller = new DevSessionController(new DevSessionRegistry(), environment, true);
        assertThat(controller.create(request).devOnly()).isTrue();
        request.setRemoteAddr("192.168.1.20");
        request.addHeader("X-Forwarded-For", "127.0.0.1");
        assertThatThrownBy(() -> controller.create(request)).isInstanceOf(ApiException.class);
    }
}
