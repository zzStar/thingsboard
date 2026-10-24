// SPDX-FileCopyrightText: Copyright The ThingsBoard Authors
// SPDX-License-Identifier: Apache-2.0
package org.thingsboard.server.service.production;

import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.thingsboard.common.util.JacksonUtil;
import org.thingsboard.common.util.ThingsBoardThreadFactory;
import org.thingsboard.server.common.data.id.DeviceId;
import org.thingsboard.server.common.data.id.TenantId;

import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static org.thingsboard.server.service.production.ProductionModels.LEGACY_DEFAULT_SHIFTS;

/** Single-node migration: each device's configuration and historical summaries change atomically. */
@Service
@Profile("!install")
@RequiredArgsConstructor
@Slf4j
public class ProductionDefaultScheduleMigration implements AutoCloseable {
    static final String NAME = "default-shifts-0730-v1";
    private final JdbcTemplate jdbc;
    private final DeviceProductionService production;
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(
            ThingsBoardThreadFactory.forName("production-default-shifts"));

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        executor.scheduleWithFixedDelay(() -> {
            try {
                if (tick()) executor.shutdown();
            } catch (Exception e) {
                log.warn("Default production shift migration will retry; this device's changes were rolled back", e);
            }
        }, 1, 5, TimeUnit.SECONDS);
    }

    boolean tick() {
        if (Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM production_migration WHERE name=?)", Boolean.class, NAME))) {
            return true;
        }
        var devices = jdbc.query("SELECT DISTINCT d.id,d.tenant_id FROM production_config c JOIN device d ON d.id=c.device_id "
                        + "WHERE c.config->'shifts'=?::jsonb ORDER BY d.id LIMIT 1",
                (rs, n) -> new Target(new TenantId(rs.getObject(2, UUID.class)), new DeviceId(rs.getObject(1, UUID.class))),
                JacksonUtil.toString(LEGACY_DEFAULT_SHIFTS));
        if (devices.isEmpty()) {
            jdbc.update("INSERT INTO production_migration(name,completed_ts) VALUES (?,?) ON CONFLICT DO NOTHING", NAME, System.currentTimeMillis());
            log.info("Default production shifts migrated to 07:30/19:30, including historical summaries");
            return true;
        }
        Target target = devices.getFirst();
        if (production.migrateDefaultSchedule(target.tenant(), target.device())) {
            log.info("Migrated default production shifts and historical summaries for device {}", target.device());
        }
        return false;
    }

    private record Target(TenantId tenant, DeviceId device) {}

    @PreDestroy
    @Override
    public void close() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) executor.shutdownNow();
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
