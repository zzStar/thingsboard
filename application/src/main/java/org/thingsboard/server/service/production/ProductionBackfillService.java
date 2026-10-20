// SPDX-FileCopyrightText: Copyright The ThingsBoard Authors
// SPDX-License-Identifier: Apache-2.0
package org.thingsboard.server.service.production;

import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.thingsboard.common.util.ThingsBoardThreadFactory;
import org.thingsboard.server.common.data.id.DeviceId;
import org.thingsboard.server.common.data.id.TenantId;
import org.thingsboard.server.common.data.kv.BaseReadTsKvQuery;
import org.thingsboard.server.common.data.kv.TsKvEntry;
import org.thingsboard.server.dao.timeseries.TimeseriesService;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ScheduledExecutorService;

import static org.thingsboard.server.service.production.ProductionModels.*;

@Service
@Profile("!install")
@RequiredArgsConstructor
@Slf4j
public class ProductionBackfillService implements AutoCloseable {
    private final JdbcTemplate jdbc;
    private final TimeseriesService timeseries;
    private final DeviceProductionService production;
    private final Semaphore capacity = new Semaphore(2);
    private final ExecutorService executor = Executors.newFixedThreadPool(2, ThingsBoardThreadFactory.forName("production-backfill"));
    private final ScheduledExecutorService resumeExecutor = Executors.newSingleThreadScheduledExecutor(
            ThingsBoardThreadFactory.forName("production-backfill-resume"));
    private volatile boolean closing;

    // Single-node deployment: interrupted jobs retain their exact cursor and job ID.
    // Cluster task leasing must be implemented before enabling multiple application nodes.
    @EventListener(ApplicationReadyEvent.class)
    public void resumeOnStartup() {
        jdbc.update("UPDATE production_backfill SET status='INTERRUPTED' WHERE status='RUNNING'");
        resumeExecutor.scheduleWithFixedDelay(() -> {
            try { resumeInterrupted(); }
            catch (Exception e) { log.warn("Cannot resume production backfill yet", e); }
        }, 1, 10, TimeUnit.SECONDS);
    }

    void resumeInterrupted() {
        if (closing) return;
        var jobs = jdbc.query("SELECT b.*,d.tenant_id FROM production_backfill b JOIN device d ON d.id=b.device_id "
                        + "WHERE b.status='INTERRUPTED' ORDER BY b.updated_ts LIMIT 2",
                (rs, n) -> new Resume(new TenantId(rs.getObject("tenant_id", UUID.class)),
                        new DeviceId(rs.getObject("device_id", UUID.class)), rs.getObject("job_id", UUID.class),
                        new BackfillRequest(rs.getLong("start_ts"), rs.getLong("end_ts")),
                        rs.getLong("cursor_ts"), rs.getLong("processed")));
        for (Resume job : jobs) {
            if (!capacity.tryAcquire()) return;
            try {
                int claimed = jdbc.update("UPDATE production_backfill SET status='RUNNING',updated_ts=?,error=NULL "
                                + "WHERE device_id=? AND job_id=? AND status='INTERRUPTED'",
                        System.currentTimeMillis(), job.device().getId(), job.job());
                if (claimed == 0) { capacity.release(); continue; }
                executor.submit(() -> run(job.tenant(), job.device(), job.job(), job.request(), job.cursor(), job.processed()));
            } catch (RuntimeException e) {
                capacity.release();
                jdbc.update("UPDATE production_backfill SET status='INTERRUPTED' WHERE device_id=? AND job_id=?",
                        job.device().getId(), job.job());
                throw e;
            }
        }
    }

    private record Resume(TenantId tenant, DeviceId device, UUID job, BackfillRequest request, long cursor, long processed) {}

    public Backfill get(DeviceId device) {
        var rows = jdbc.query("SELECT * FROM production_backfill WHERE device_id=?", (rs, n) -> {
            String status = rs.getString("status");
            if ("RUNNING".equals(status) && rs.getLong("updated_ts") < System.currentTimeMillis() - 300_000) status = "INTERRUPTED";
            return new Backfill(rs.getLong("start_ts"), rs.getLong("end_ts"), rs.getLong("cursor_ts"), rs.getLong("processed"),
                    status, rs.getString("error"));
        }, device.getId());
        return rows.isEmpty() ? null : rows.getFirst();
    }

    public Backfill start(TenantId tenant, DeviceId device, BackfillRequest request) {
        if (closing) throw new IllegalArgumentException("服务正在更新，请稍后重试");
        long now = System.currentTimeMillis();
        if (request.startTs() < 0 || request.endTs() <= request.startTs() || request.endTs() > now
                || request.endTs() - request.startTs() > 31L * 86400000) {
            throw new IllegalArgumentException("回算范围须为过去时间，每次最多31天");
        }
        production.settings(tenant, device);
        if (!capacity.tryAcquire()) throw new IllegalArgumentException("当前有回算任务正在执行，请稍后重试");
        UUID job = UUID.randomUUID();
        try {
            int changed = jdbc.update("INSERT INTO production_backfill(device_id,job_id,start_ts,end_ts,cursor_ts,processed,status,updated_ts) "
                            + "VALUES (?,?,?,?,?,0,'RUNNING',?) ON CONFLICT (device_id) DO UPDATE SET job_id=EXCLUDED.job_id, "
                            + "start_ts=EXCLUDED.start_ts,end_ts=EXCLUDED.end_ts,cursor_ts=EXCLUDED.cursor_ts,processed=0,status='RUNNING',updated_ts=EXCLUDED.updated_ts,error=NULL "
                            + "WHERE production_backfill.status<>'RUNNING' OR production_backfill.updated_ts<?",
                    device.getId(), job, request.startTs(), request.endTs(), request.startTs(), now, now - 300_000);
            if (changed == 0) throw new IllegalArgumentException("该设备已有回算任务正在执行");
            executor.submit(() -> run(tenant, device, job, request, request.startTs(), 0));
        } catch (RuntimeException e) {
            capacity.release();
            jdbc.update("UPDATE production_backfill SET status='FAILED',updated_ts=? WHERE device_id=? AND job_id=?", now, device.getId(), job);
            throw e;
        }
        return get(device);
    }

    private List<TsKvEntry> readings(TenantId tenant, DeviceId device, long from, long until, int limit, String order) throws Exception {
        return timeseries.findAll(tenant, device, List.of(new BaseReadTsKvQuery("totalCount", from, until, limit, order)))
                .get(30, TimeUnit.SECONDS);
    }

    private void run(TenantId tenant, DeviceId device, UUID job, BackfillRequest request, long cursor, long processed) {
        try {
            // Fetch a preceding baseline and a following endpoint so a clipped range can be calculated.
            production.record(tenant, device, readings(tenant, device, 0, cursor, 1, "DESC"), false);
            while (cursor < request.endTs() && !Thread.currentThread().isInterrupted()) {
                List<TsKvEntry> entries = readings(tenant, device, cursor, request.endTs(), 500, "ASC");
                if (entries.isEmpty()) break;
                for (int offset = 0; offset < entries.size(); offset += 100) {
                    List<TsKvEntry> batch = entries.subList(offset, Math.min(offset + 100, entries.size()));
                    // Atomically renew/check ownership, then process only a bounded batch.
                    int owned = jdbc.update("UPDATE production_backfill SET updated_ts=? WHERE device_id=? AND job_id=? AND status='RUNNING'",
                            System.currentTimeMillis(), device.getId(), job);
                    if (owned == 0) return;
                    production.record(tenant, device, batch, false);
                    processed += batch.size();
                    cursor = batch.getLast().getTs() + 1;
                    jdbc.update("UPDATE production_backfill SET cursor_ts=?,processed=?,updated_ts=? WHERE device_id=? AND job_id=?",
                            cursor, processed, System.currentTimeMillis(), device.getId(), job);
                }
            }
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
            production.record(tenant, device, readings(tenant, device, request.endTs(), System.currentTimeMillis(), 1, "ASC"), false);
            jdbc.update("UPDATE production_backfill SET status='COMPLETE',cursor_ts=end_ts,updated_ts=? WHERE device_id=? AND job_id=?",
                    System.currentTimeMillis(), device.getId(), job);
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            log.warn("Production backfill failed for device {}", device, e);
            jdbc.update("UPDATE production_backfill SET status=?,error=?,updated_ts=? WHERE device_id=? AND job_id=?",
                    closing || e instanceof InterruptedException ? "INTERRUPTED" : "FAILED",
                    "回算中断；重启续算或重试不会重复计数", System.currentTimeMillis(), device.getId(), job);
        } finally {
            capacity.release();
        }
    }

    @PreDestroy
    public void close() {
        closing = true;
        resumeExecutor.shutdownNow();
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
                executor.awaitTermination(10, TimeUnit.SECONDS);
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
