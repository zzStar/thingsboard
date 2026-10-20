// SPDX-FileCopyrightText: Copyright The ThingsBoard Authors
// SPDX-License-Identifier: Apache-2.0
package org.thingsboard.server.service.production;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.thingsboard.common.util.ThingsBoardThreadFactory;
import org.thingsboard.server.common.data.id.DeviceId;
import org.thingsboard.server.common.data.id.TenantId;
import org.thingsboard.server.common.data.kv.BaseReadTsKvQuery;
import org.thingsboard.server.common.data.kv.TsKvEntry;
import org.thingsboard.server.dao.timeseries.TimeseriesService;

import java.util.List;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Bounded, persistent reconciliation; independent of the user-requested backfill task. */
@Service
@Profile("!install")
@ConditionalOnProperty(prefix = "production.repair", name = "enabled", havingValue = "true", matchIfMissing = true)
@Slf4j
public class ProductionRepairService implements AutoCloseable {
    private final JdbcTemplate jdbc;
    private final TimeseriesService timeseries;
    private final DeviceProductionService production;
    private final TransactionTemplate transaction;
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(
            ThingsBoardThreadFactory.forName("production-repair"));
    @Value("${production.repair.lookback_ms:86400000}")
    private long lookbackMs = 86_400_000;
    private volatile boolean initialized;

    public ProductionRepairService(JdbcTemplate jdbc, TimeseriesService timeseries,
                                   DeviceProductionService production, PlatformTransactionManager transactions) {
        this.jdbc = jdbc;
        this.timeseries = timeseries;
        this.production = production;
        this.transaction = new TransactionTemplate(transactions);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        executor.scheduleWithFixedDelay(() -> {
            try {
                if (!initialized) initialize(System.currentTimeMillis());
                tick();
            } catch (Exception e) {
                log.warn("Production repair will retry after storage failure", e);
            }
        }, 1, 1, TimeUnit.SECONDS);
    }

    void initialize(long now) {
        transaction.executeWithoutResult(tx -> {
            jdbc.execute("SELECT pg_advisory_xact_lock(7536891047216478433)");
            var previous = jdbc.query("SELECT heartbeat_ts FROM production_repair_state WHERE id=1", (rs, n) -> rs.getLong(1));
            long from = Math.max(0, now - Math.max(60_000, lookbackMs));
            if (!previous.isEmpty() && previous.getFirst() < from) {
                log.warn("Production repair lookback does not cover the entire downtime; older raw telemetry needs manual backfill");
            }
            // Always sweep the full lookback: a late write may carry a timestamp older than the last heartbeat.
            // Include devices with no production row: the first calculation may have failed before ensureDevice.
            jdbc.update("INSERT INTO production_repair(device_id,start_ts,end_ts,cursor_ts,updated_ts) "
                            + "SELECT id,?,?,?,? FROM device WHERE tenant_id IS NOT NULL "
                            + "ON CONFLICT (device_id) DO UPDATE SET start_ts=LEAST(production_repair.start_ts,EXCLUDED.start_ts), "
                            + "end_ts=GREATEST(production_repair.end_ts,EXCLUDED.end_ts), "
                            + "cursor_ts=LEAST(production_repair.cursor_ts,EXCLUDED.cursor_ts),status='PENDING',retry_ts=0",
                    from, now + 1, from, now);
            heartbeat(now);
        });
        initialized = true;
    }

    public void enqueue(DeviceId device, List<TsKvEntry> entries) {
        long now = System.currentTimeMillis();
        var counters = entries.stream().filter(entry -> "totalCount".equals(entry.getKey())
                && entry.getTs() >= 0 && entry.getTs() <= now + 300_000).toList();
        if (counters.isEmpty()) return;
        long from = counters.stream().mapToLong(TsKvEntry::getTs).min().orElseThrow();
        long until = counters.stream().mapToLong(TsKvEntry::getTs).max().orElseThrow() + 1;
        jdbc.update("INSERT INTO production_repair(device_id,start_ts,end_ts,cursor_ts,updated_ts) VALUES (?,?,?,?,?) "
                        + "ON CONFLICT (device_id) DO UPDATE SET start_ts=LEAST(production_repair.start_ts,EXCLUDED.start_ts), "
                        + "end_ts=GREATEST(production_repair.end_ts,EXCLUDED.end_ts), "
                        + "cursor_ts=LEAST(production_repair.cursor_ts,EXCLUDED.cursor_ts),status='PENDING',retry_ts=0",
                device.getId(), from, until, from, System.currentTimeMillis());
    }

    void tick() {
        transaction.executeWithoutResult(tx -> {
            long now = System.currentTimeMillis();
            heartbeat(now);
            var jobs = jdbc.query("SELECT r.device_id,d.tenant_id,r.cursor_ts,r.end_ts FROM production_repair r "
                            + "JOIN device d ON d.id=r.device_id WHERE r.status='PENDING' AND r.retry_ts<=? "
                            + "ORDER BY r.updated_ts,r.device_id LIMIT 1 FOR UPDATE OF r SKIP LOCKED",
                    (rs, n) -> new Job(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getLong(3), rs.getLong(4)), now);
            if (jobs.isEmpty()) return;
            Job job = jobs.getFirst();
            try {
                DeviceId device = new DeviceId(job.device());
                TenantId tenant = new TenantId(job.tenant());
                long revision = production.revision(device);
                List<TsKvEntry> baseline = readings(tenant, device, 0, job.cursor(), 1, "DESC");
                List<TsKvEntry> batch = readings(tenant, device, job.cursor(), job.until(), 100, "ASC");
                // Read before modifying SQL. Source failures can be deferred without a partially failed transaction.
                List<TsKvEntry> following = batch.isEmpty()
                        ? readings(tenant, device, job.until(), now + 1, 1, "ASC") : List.of();
                List<TsKvEntry> replay = new ArrayList<>(baseline);
                replay.addAll(batch);
                replay.addAll(following);
                if (!production.reconcile(tenant, device, replay, revision)) {
                    // A newer calculation won the race; retry the same cursor with a fresh source snapshot.
                    jdbc.update("UPDATE production_repair SET updated_ts=? WHERE device_id=?", now, job.device());
                    return;
                }
                if (batch.isEmpty()) {
                    // A completed row is no longer needed; deleting avoids replaying old completed intervals on restart.
                    jdbc.update("DELETE FROM production_repair WHERE device_id=?", job.device());
                } else {
                    jdbc.update("UPDATE production_repair SET cursor_ts=?,updated_ts=?,attempts=0 WHERE device_id=?",
                            batch.getLast().getTs() + 1, now, job.device());
                }
            } catch (SourceUnavailable e) {
                jdbc.update("UPDATE production_repair SET attempts=attempts+1,retry_ts=?,updated_ts=? WHERE device_id=?",
                        now + 30_000, now, job.device());
                log.warn("Production raw telemetry unavailable for {}; repair checkpoint retained", job.device());
            }
            // SQL/calculation errors deliberately escape: rollback retains the original cursor and aggregates.
        });
    }

    private List<TsKvEntry> readings(TenantId tenant, DeviceId device, long from, long until, int limit, String order) {
        if (until <= from) return List.of();
        com.google.common.util.concurrent.ListenableFuture<List<TsKvEntry>> future = null;
        try {
            future = timeseries.findAll(tenant, device,
                    List.of(new BaseReadTsKvQuery("totalCount", from, until, limit, order)));
            return future.get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            if (future != null) future.cancel(true);
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new SourceUnavailable(e);
        }
    }

    private void heartbeat(long now) {
        jdbc.update("INSERT INTO production_repair_state(id,heartbeat_ts) VALUES (1,?) "
                + "ON CONFLICT (id) DO UPDATE SET heartbeat_ts=EXCLUDED.heartbeat_ts", now);
    }

    private record Job(UUID device, UUID tenant, long cursor, long until) {}
    private static class SourceUnavailable extends RuntimeException {
        SourceUnavailable(Throwable cause) { super(cause); }
    }

    @PreDestroy
    public void close() {
        executor.shutdownNow();
        try {
            executor.awaitTermination(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
