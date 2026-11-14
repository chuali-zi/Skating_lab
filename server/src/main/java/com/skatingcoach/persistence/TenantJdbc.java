package com.skatingcoach.persistence;

import com.skatingcoach.access.CurrentActor;
import com.skatingcoach.api.ApiException;
import java.util.function.Supplier;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** All personalized SQL runs in a short transaction with pool-safe local scope. */
@Component
public class TenantJdbc {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;

    public TenantJdbc(JdbcTemplate jdbc, PlatformTransactionManager manager) {
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(manager);
    }

    public <T> T read(CurrentActor actor, Supplier<T> work) { return scoped(actor, false, work); }
    public <T> T write(CurrentActor actor, Supplier<T> work) { return scoped(actor, true, work); }

    private <T> T scoped(CurrentActor actor, boolean lock, Supplier<T> work) {
        return transactions.execute(status -> {
            String existing = jdbc.queryForObject("select current_setting('app.athlete_id',true)", String.class);
            if (existing != null && !existing.isBlank() && !existing.equals(actor.athleteId().toString())) {
                throw new IllegalStateException("Cannot switch athlete inside a transaction");
            }
            jdbc.queryForObject("select set_config('app.athlete_id',?,true)", String.class, actor.athleteId().toString());
            jdbc.queryForObject("select set_config('app.actor_id',?,true)", String.class, actor.actorId().toString());
            var ids = jdbc.queryForList("select id from sc_athlete where id=? and actor_id=?" + (lock ? " for update" : ""),
                    actor.athleteId(), actor.actorId());
            if (ids.isEmpty()) throw new ApiException(HttpStatus.FORBIDDEN,"ATHLETE_ACCESS_DENIED","当前会话无权访问这位选手。");
            return work.get();
        });
    }
}
