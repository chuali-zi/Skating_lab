package com.skatingcoach.conversation;

import com.skatingcoach.access.CurrentActor;
import com.skatingcoach.api.ApiException;
import com.skatingcoach.memory.MemoryService;
import com.skatingcoach.persistence.TenantJdbc;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import static com.skatingcoach.conversation.ConversationModels.*;

@Service
public class ConversationService {
    private final JdbcTemplate jdbc;
    private final TenantJdbc tenant;
    private final MemoryService memory;

    public ConversationService(JdbcTemplate jdbc, TenantJdbc tenant, MemoryService memory) {
        this.jdbc = jdbc;
        this.tenant = tenant;
        this.memory = memory;
    }

    public SessionView create(CurrentActor actor) {
        return tenant.write(actor, () -> {
            UUID id = UUID.randomUUID();
            return jdbc.queryForObject("""
                    INSERT INTO conversation_session(id, athlete_id, actor_id, revision, created_at)
                    VALUES (?, ?, ?, 0, now()) RETURNING id, revision, created_at
                    """, (rs, row) -> new SessionView(rs.getObject("id", UUID.class), rs.getLong("revision"),
                    rs.getTimestamp("created_at").toInstant()), id, actor.athleteId(), actor.actorId());
        });
    }

    public MessageView append(CurrentActor actor, UUID sessionId, MessageInput input) {
        MessageView message = tenant.write(actor, () -> {
            requireSession(actor, sessionId);
            List<MessageView> prior = jdbc.query("""
                    SELECT m.id, m.session_id, m.content, m.created_at, s.revision
                    FROM conversation_message m JOIN conversation_session s ON s.id=m.session_id
                    WHERE m.athlete_id=? AND m.request_id=?
                    """, this::mapMessage, actor.athleteId(), input.requestId());
            if (!prior.isEmpty()) {
                MessageView previous = prior.get(0);
                if (!previous.sessionId().equals(sessionId) || !previous.content().equals(input.content())) {
                    throw new ApiException(HttpStatus.CONFLICT, "IDEMPOTENCY_CONFLICT", "同一请求标识已用于不同内容。");
                }
                return captureAndReturn(actor, previous);
            }
            UUID id = UUID.randomUUID();
            jdbc.update("""
                    INSERT INTO conversation_message(id,session_id,athlete_id,actor_id,request_id,content,created_at)
                    VALUES (?,?,?,?,?,?,now())
                    """, id, sessionId, actor.athleteId(), actor.actorId(), input.requestId(), input.content());
            jdbc.update("UPDATE conversation_session SET revision=revision+1 WHERE id=? AND athlete_id=?",
                    sessionId, actor.athleteId());
            MessageView saved = jdbc.queryForObject("""
                    SELECT m.id, m.session_id, m.content, m.created_at, s.revision
                    FROM conversation_message m JOIN conversation_session s ON s.id=m.session_id
                    WHERE m.id=? AND m.athlete_id=?
                    """, this::mapMessage, id, actor.athleteId());
            return captureAndReturn(actor, saved);
        });
        return message;
    }

    private MessageView captureAndReturn(CurrentActor actor, MessageView message) {
        // Capturing a raw memory source is DB-only and joins the message transaction.
        // Extraction and every network/model call remain outside this transaction.
        memory.captureInput(actor, message.id(), message.content(), message.sessionId().toString());
        return message;
    }

    public long revision(CurrentActor actor, UUID sessionId) {
        return tenant.read(actor, () -> requireSession(actor, sessionId));
    }

    private long requireSession(CurrentActor actor, UUID sessionId) {
        List<Long> revisions = jdbc.query("SELECT revision FROM conversation_session WHERE id=? AND athlete_id=?",
                (rs, row) -> rs.getLong(1), sessionId, actor.athleteId());
        if (revisions.isEmpty()) throw new ApiException(HttpStatus.NOT_FOUND, "SESSION_NOT_FOUND", "找不到该会话。");
        return revisions.get(0);
    }

    private MessageView mapMessage(ResultSet rs, int row) throws SQLException {
        return new MessageView(rs.getObject("id", UUID.class), rs.getObject("session_id", UUID.class),
                rs.getLong("revision"), rs.getString("content"), rs.getTimestamp("created_at").toInstant());
    }
}
