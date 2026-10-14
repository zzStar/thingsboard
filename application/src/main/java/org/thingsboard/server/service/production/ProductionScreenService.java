// SPDX-FileCopyrightText: Copyright The ThingsBoard Authors
// SPDX-License-Identifier: Apache-2.0
package org.thingsboard.server.service.production;

import com.google.common.util.concurrent.ListenableFuture;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.thingsboard.server.common.data.exception.ThingsboardErrorCode;
import org.thingsboard.server.exception.ThingsboardRuntimeException;
import org.thingsboard.server.common.data.id.DeviceId;
import org.thingsboard.server.common.data.id.TenantId;
import org.thingsboard.server.common.data.kv.TsKvEntry;
import org.thingsboard.server.dao.timeseries.TimeseriesService;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.thingsboard.server.service.production.ProductionModels.*;

@Service
@Profile("!install")
@Slf4j
public class ProductionScreenService {
    private static final SecureRandom RANDOM = new SecureRandom();
    @Value("${database.ts_max_intervals:700}")
    private long maxTsIntervals = 700;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate write;
    private final DeviceProductionService production;
    private final TimeseriesService timeseries;

    public ProductionScreenService(JdbcTemplate jdbc, PlatformTransactionManager transactions,
                                  DeviceProductionService production, TimeseriesService timeseries) {
        this.jdbc = jdbc;
        this.write = new TransactionTemplate(transactions);
        this.production = production;
        this.timeseries = timeseries;
    }

    public ScreenLink link(TenantId tenant, DeviceId device) {
        return jdbc.query("SELECT access_key, created_ts FROM production_screen_link WHERE device_id=? AND tenant_id=?",
                (rs, n) -> new ScreenLink(rs.getString(1), rs.getLong(2)), device.getId(), tenant.getId())
                .stream().findFirst().orElse(null);
    }

    public ScreenLink create(TenantId tenant, DeviceId device, boolean rotate) {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String key = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        return write.execute(tx -> {
            jdbc.update("INSERT INTO production_screen_link(device_id, tenant_id, access_key, created_ts) VALUES (?, ?, ?, ?) " +
                            "ON CONFLICT (device_id) DO UPDATE SET tenant_id=EXCLUDED.tenant_id, access_key=EXCLUDED.access_key, " +
                            "created_ts=EXCLUDED.created_ts WHERE production_screen_link.tenant_id<>EXCLUDED.tenant_id OR ?",
                    device.getId(), tenant.getId(), key, System.currentTimeMillis(), rotate);
            return link(tenant, device);
        });
    }

    public void revoke(TenantId tenant, DeviceId device) {
        jdbc.update("DELETE FROM production_screen_link WHERE device_id=? AND tenant_id=?", device.getId(), tenant.getId());
    }

    public ScreenView view(DeviceId device, String key) throws Exception {
        if (key == null || !key.matches("[A-Za-z0-9_-]{43}")) throw unavailable();
        var links = jdbc.query("SELECT s.access_key, s.tenant_id, COALESCE(NULLIF(d.label, ''), d.name) AS device_name " +
                        "FROM production_screen_link s JOIN device d ON d.id=s.device_id AND d.tenant_id=s.tenant_id WHERE s.device_id=?",
                (rs, n) -> new Access(rs.getString(1), new TenantId(rs.getObject(2, java.util.UUID.class)), rs.getString(3)), device.getId());
        if (links.isEmpty() || !MessageDigest.isEqual(links.getFirst().key().getBytes(StandardCharsets.US_ASCII),
                key.getBytes(StandardCharsets.US_ASCII))) throw unavailable();
        Access access = links.getFirst();
        Overview overview = production.overview(access.tenant(), device);
        long end = Math.max(overview.dayStart() + 1, overview.now());
        Report output = production.report(access.tenant(), device, overview.dayStart() - 6 * 86400000L, end, "shift", null);
        List<SpeedPoint> speed = List.of();
        boolean speedUnavailable = false;
        ListenableFuture<List<TsKvEntry>> query = null;
        try {
            query = timeseries.findAll(access.tenant(), device,
                    List.of(ProductionSpeedQuery.create(overview.dayStart(), end, maxTsIntervals)));
            speed = query.get(5, TimeUnit.SECONDS).stream()
                    .map(point -> new SpeedPoint(point.getTs(), point.getValueAsString())).toList();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (query != null) query.cancel(true);
            throw e;
        } catch (Exception e) {
            if (query != null) query.cancel(true);
            speedUnavailable = true;
            log.warn("Unable to load production speed trend for device {}: {}", device, e.toString());
        }
        return new ScreenView(access.deviceName(), overview, speed, output, speedUnavailable);
    }

    private ThingsboardRuntimeException unavailable() {
        return new ThingsboardRuntimeException("产量大屏链接不可用", ThingsboardErrorCode.ITEM_NOT_FOUND);
    }

    private record Access(String key, TenantId tenant, String deviceName) {}
}
