package com.skatingcoach.planning;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.skatingcoach.access.CurrentActor;
import com.skatingcoach.api.ApiException;
import com.skatingcoach.persistence.TenantJdbc;
import com.skatingcoach.scoring.ScoringModels;
import com.skatingcoach.scoring.ScoringService;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import static com.skatingcoach.planning.PlanModels.*;

@Service
public class PlanService {
    private final JdbcTemplate jdbc;
    private final TenantJdbc tenant;
    private final ObjectMapper mapper;
    private final PlanBodyProjector projector;
    private final ScoringService scoring = new ScoringService();

    public PlanService(JdbcTemplate jdbc, TenantJdbc tenant, ObjectMapper mapper, PlanBodyProjector projector) {
        this.jdbc = jdbc;
        this.tenant = tenant;
        this.mapper = mapper;
        this.projector = projector;
    }

    public List<PlanView> list(CurrentActor actor) {
        return tenant.read(actor, () -> jdbc.query("SELECT * FROM saved_plan WHERE athlete_id=? ORDER BY updated_at DESC",
                this::map, actor.athleteId()));
    }

    public PlanView get(CurrentActor actor, UUID id) { return tenant.read(actor, () -> require(actor, id)); }

    public PlanView create(CurrentActor actor, PlanInput input) {
        return tenant.write(actor, () -> insert(actor, input));
    }

    private PlanView insert(CurrentActor actor, PlanInput input) {
        JsonNode body = validateBody(actor, input.kind(), input.body());
        UUID id = UUID.randomUUID();
        return jdbc.queryForObject("""
                INSERT INTO saved_plan(id,athlete_id,title,kind,revision,body,created_at,updated_at)
                VALUES (?,?,?,?,1,?::jsonb,now(),now()) RETURNING *
                """, this::map, id, actor.athleteId(), input.title(), input.kind().name(), json(body));
    }

    public PlanView update(CurrentActor actor, UUID id, PlanInput input) {
        if (input.expectedRevision() == null) throw new ApiException(HttpStatus.BAD_REQUEST, "VERSION_REQUIRED", "编辑需要携带当前方案版本。");
        return tenant.write(actor, () -> {
            PlanView existing = require(actor, id);
            if (existing.revision() != input.expectedRevision()) throw stale();
            if (existing.kind() != input.kind()) throw new ApiException(HttpStatus.CONFLICT, "PLAN_KIND_FIXED", "请为不同类型另建方案。");
            JsonNode body = validateBody(actor, input.kind(), input.body());
            if (existing.kind() == Kind.RECOVERY && (!existing.body().path("completedPrefix").equals(body.path("completedPrefix"))
                    || !existing.body().path("sourcePlanId").equals(body.path("sourcePlanId")))) {
                throw new ApiException(HttpStatus.CONFLICT, "ACTUAL_PREFIX_LOCKED", "该预案已发生的动作已冻结；修正失误情境请创建新预案。");
            }
            List<PlanView> updated = jdbc.query("""
                    UPDATE saved_plan SET title=?, body=?::jsonb, revision=revision+1, context_invalidated=false, updated_at=now()
                    WHERE id=? AND athlete_id=? AND revision=? RETURNING *
                    """, this::map, input.title(), json(body), id, actor.athleteId(), input.expectedRevision());
            if (updated.isEmpty()) throw stale();
            return updated.get(0);
        });
    }

    public RecoveryView recovery(CurrentActor actor, UUID id, RecoveryInput input) {
        return tenant.write(actor, () -> {
            PlanView original = require(actor, id);
            if (original.revision() != input.expectedRevision()) throw stale();
            requireCurrentContext(original);
            var recovery = scoring.evaluateRecovery(new ScoringModels.RecoveryRequest(input.completedPrefix(),
                    input.proposedElements(), input.ruleSet(), input.selection()));
            if (!recovery.prefixPreserved()) throw prefixChanged();
            ObjectNode body = mapper.createObjectNode();
            body.set("elements", mapper.valueToTree(input.proposedElements()));
            body.set("completedPrefix", mapper.valueToTree(input.completedPrefix()));
            body.set("ruleSet", mapper.valueToTree(input.ruleSet()));
            body.set("selection", mapper.valueToTree(input.selection()));
            body.put("sourcePlanId", id.toString());
            body.put("sourcePlanRevision", original.revision());
            body.put("failureDescription", input.failureDescription() == null ? "" : input.failureDescription());
            String title = input.title() == null || input.title().isBlank() ? original.title() + " · 失误预案" : input.title();
            PlanView saved = insert(actor, new PlanInput(title, Kind.RECOVERY, body, null));
            return new RecoveryView(saved, recovery.evaluation());
        });
    }

    private JsonNode validateBody(CurrentActor actor, Kind kind, JsonNode proposed) {
        ObjectNode body = projector.project(proposed);
        if (kind == Kind.RECOVERY) {
            if (!body.has("completedPrefix") && body.has("actualPrefix")) body.set("completedPrefix", body.get("actualPrefix"));
            if (!body.path("completedPrefix").isArray() || !body.path("elements").isArray() || !body.path("sourcePlanId").isTextual()) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "RECOVERY_CONTEXT_REQUIRED", "预案需要保存主方案引用和已经发生的动作。");
            }
            requireCurrentContext(require(actor, UUID.fromString(body.path("sourcePlanId").asText())));
            JsonNode prefix = body.path("completedPrefix");
            JsonNode elements = body.path("elements");
            if (prefix.size() > elements.size()) throw prefixChanged();
            for (int i = 0; i < prefix.size(); i++) {
                if (!prefix.get(i).equals(elements.get(i))) throw prefixChanged();
            }
        }
        return body;
    }

    private PlanView require(CurrentActor actor, UUID id) {
        List<PlanView> found = jdbc.query("SELECT * FROM saved_plan WHERE id=? AND athlete_id=?", this::map, id, actor.athleteId());
        if (found.isEmpty()) throw new ApiException(HttpStatus.NOT_FOUND, "PLAN_NOT_FOUND", "找不到该方案。");
        return found.get(0);
    }

    private PlanView map(ResultSet rs, int row) throws SQLException {
        try {
            return new PlanView(rs.getObject("id", UUID.class), rs.getString("title"), Kind.valueOf(rs.getString("kind")),
                    rs.getLong("revision"), mapper.readTree(rs.getString("body")), rs.getTimestamp("created_at").toInstant(),
                    rs.getTimestamp("updated_at").toInstant(), rs.getBoolean("context_invalidated"));
        } catch (JsonProcessingException error) { throw new IllegalStateException("Stored plan JSON is invalid", error); }
    }

    private String json(JsonNode body) { return body.toString(); }
    public static void requireCurrentContext(PlanView plan) {
        if (plan.contextInvalidated()) throw new ApiException(HttpStatus.CONFLICT, "SOURCE_REVOKED",
                "这份方案的部分来源已撤回，请读取最新内容并明确重新保存当前编排，再继续分析。");
    }
    private ApiException stale() { return new ApiException(HttpStatus.CONFLICT, "STALE_VERSION", "方案已在其他操作中更新，请读取最新版本后合并。"); }
    private ApiException prefixChanged() { return new ApiException(HttpStatus.CONFLICT, "ACTUAL_PREFIX_CHANGED", "预案不能改写已实际发生的动作。"); }
}
