// SPDX-FileCopyrightText: Copyright The ThingsBoard Authors
// SPDX-License-Identifier: Apache-2.0
package org.thingsboard.server.service.production;

import com.google.common.util.concurrent.Futures;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.thingsboard.server.common.data.id.DeviceId;
import org.thingsboard.server.common.data.id.TenantId;
import org.thingsboard.server.common.data.kv.BasicTsKvEntry;
import org.thingsboard.server.common.data.kv.LongDataEntry;
import org.thingsboard.server.common.data.kv.ReadTsKvQuery;
import org.thingsboard.server.common.data.kv.TsKvEntry;
import org.thingsboard.server.dao.timeseries.TimeseriesService;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

public class ProductionRepairServiceTest {
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18");
    private static JdbcTemplate jdbc;
    private DataSourceTransactionManager transactions;
    private TimeseriesService source;
    private DeviceProductionService production;
    private DeviceId device;
    private TenantId tenant;
    private List<TsKvEntry> raw;
    private long start;

    @BeforeClass
    public static void database() {
        POSTGRES.start();
        jdbc = new JdbcTemplate(new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
        jdbc.execute("CREATE TABLE device(id uuid PRIMARY KEY,tenant_id uuid,name text,label text)");
    }

    @AfterClass
    public static void stopDatabase() { POSTGRES.stop(); }

    @Before
    public void setup() {
        jdbc.execute("TRUNCATE device CASCADE");
        transactions = new DataSourceTransactionManager(jdbc.getDataSource());
        source = mock(TimeseriesService.class);
        production = new DeviceProductionService(jdbc, transactions, source);
        production.initSchema();
        jdbc.execute("TRUNCATE production_repair_state");
        device = new DeviceId(UUID.randomUUID());
        tenant = new TenantId(UUID.randomUUID());
        jdbc.update("INSERT INTO device(id,tenant_id) VALUES (?,?)", device.getId(), tenant.getId());
        start = System.currentTimeMillis() - 600_000;
        raw = new ArrayList<>();
        for (int i = 0; i < 250; i++) raw.add(new BasicTsKvEntry(start + i * 2000L,
                new LongDataEntry("totalCount", i < 130 ? 1000L + i : i - 130L)));
        when(source.findAll(any(), any(), anyList())).thenAnswer(call -> {
            ReadTsKvQuery query = ((List<ReadTsKvQuery>) call.getArgument(2)).getFirst();
            var matches = raw.stream().filter(entry -> entry.getTs() >= query.getStartTs() && entry.getTs() < query.getEndTs());
            if ("DESC".equals(query.getOrder())) matches = matches.sorted(Comparator.comparingLong(TsKvEntry::getTs).reversed());
            return Futures.immediateFuture(matches.limit(query.getLimit()).toList());
        });
    }

    private ProductionRepairService repair() { return new ProductionRepairService(jdbc, source, production, transactions); }
    private long samples() { return jdbc.queryForObject("SELECT COUNT(*) FROM production_sample WHERE device_id=?", Long.class, device.getId()); }
    private long quantity() { return jdbc.queryForObject("SELECT SUM(quantity) FROM production_summary WHERE device_id=? AND shift_index=-1", Long.class, device.getId()); }
    private long pending() { return jdbc.queryForObject("SELECT COUNT(*) FROM production_repair", Long.class); }
    private void finish(ProductionRepairService repair) {
        for (int i = 0; i < 10 && pending() != 0; i++) repair.tick();
        assertEquals(0, pending());
    }

    @Test
    public void startupRepairsInteriorGapAndFirstFailedCalculationWithoutDuplicates() {
        production.record(tenant, device, List.of(raw.getFirst(), raw.getLast()), false);
        try (var repair = repair()) {
            repair.initialize(System.currentTimeMillis());
            finish(repair);
            assertEquals(250, samples());
            assertEquals(248, quantity());
            repair.initialize(System.currentTimeMillis());
            finish(repair);
            assertEquals(248, quantity());
        }
        jdbc.update("DELETE FROM production_device WHERE device_id=?", device.getId());
        jdbc.execute("TRUNCATE production_repair_state");
        try (var repair = repair()) {
            repair.initialize(System.currentTimeMillis());
            finish(repair);
            assertEquals(250, samples());
            assertEquals(248, quantity());
        }
    }

    @Test
    public void checkpointSurvivesRestartAndReceivesOlderLateFailure() {
        try (var first = repair()) {
            first.enqueue(device, raw);
            first.tick();
            assertEquals(100, samples());
        }
        try (var restarted = repair()) {
            finish(restarted);
            assertEquals(250, samples());
            assertEquals(248, quantity());
            // A missing old sample is below MAX(ts); the marker must seek backwards.
            restarted.enqueue(device, List.of(raw.get(7)));
            finish(restarted);
            assertEquals(248, quantity());
        }
    }

    @Test
    public void sourceFailureRetainsCheckpointAndBacksOff() {
        try (var repair = repair()) {
            repair.enqueue(device, raw);
            doReturn(Futures.immediateFailedFuture(new IllegalStateException("offline"))).when(source).findAll(any(), any(), anyList());
            repair.tick();
            assertEquals(0, samples());
            assertEquals(start, (long) jdbc.queryForObject("SELECT cursor_ts FROM production_repair", Long.class));
            assertEquals(1, (int) jdbc.queryForObject("SELECT attempts FROM production_repair", Integer.class));
            assertTrue(jdbc.queryForObject("SELECT retry_ts FROM production_repair", Long.class) > System.currentTimeMillis());
        }
    }

    @Test
    public void calculationFailureRollsBackCursorAndPartialAggregates() {
        DeviceProductionService broken = spy(production);
        doThrow(new IllegalStateException("calculation failed")).when(broken).record(any(), any(), anyList(), anyBoolean());
        try (var repair = new ProductionRepairService(jdbc, source, broken, transactions)) {
            repair.enqueue(device, raw);
            assertThrows(IllegalStateException.class, repair::tick);
            assertEquals(start, (long) jdbc.queryForObject("SELECT cursor_ts FROM production_repair", Long.class));
            assertEquals(0, samples());
        }
        try (var repair = repair()) { finish(repair); assertEquals(248, quantity()); }
    }

    @Test
    public void deletedDeviceRemovesRepairAndFreshHeartbeatDoesNotSkipOlderLateWrites() {
        try (var repair = repair()) {
            long now = System.currentTimeMillis();
            jdbc.update("INSERT INTO production_repair_state VALUES(1,?)", now - 2000);
            repair.initialize(now);
            assertEquals(now - 86400000, (long) jdbc.queryForObject("SELECT start_ts FROM production_repair", Long.class));
            jdbc.update("DELETE FROM device WHERE id=?", device.getId());
            assertEquals(0, pending());
        }
    }

    @Test
    public void interruptedUserBackfillResumesSameJobAndCursor() throws Exception {
        production.settings(tenant, device);
        production.record(tenant, device, raw.subList(0, 100), false);
        UUID job = UUID.randomUUID();
        jdbc.update("INSERT INTO production_backfill(device_id,job_id,start_ts,end_ts,cursor_ts,processed,status,updated_ts) "
                        + "VALUES(?,?,?,?,?,100,'INTERRUPTED',?)", device.getId(), job, start, start + 500000, start + 200000, start);
        try (var backfill = new ProductionBackfillService(jdbc, source, production)) {
            backfill.resumeInterrupted();
            long deadline = System.currentTimeMillis() + 10000;
            while ("RUNNING".equals(backfill.get(device).status()) && System.currentTimeMillis() < deadline) Thread.sleep(20);
            assertEquals("COMPLETE", backfill.get(device).status());
            assertEquals(250, backfill.get(device).processed());
            assertEquals(job, jdbc.queryForObject("SELECT job_id FROM production_backfill", UUID.class));
            assertEquals(248, quantity());
        }
    }

    @Test
    public void futureSchemaRefusesStartupWithoutDowngradingDatabaseMarker() {
        int current = DeviceProductionService.CURRENT_SCHEMA_VERSION;
        jdbc.update("UPDATE production_schema_settings SET schema_version=? WHERE id=1", current + 1);
        try {
            assertThrows(IllegalStateException.class, production::initSchema);
            assertEquals(current + 1, (int) jdbc.queryForObject("SELECT schema_version FROM production_schema_settings", Integer.class));
        } finally {
            jdbc.update("UPDATE production_schema_settings SET schema_version=? WHERE id=1", current);
        }
    }

    @Test
    public void repairCorrectsRawOverwriteThatFailedAfterTelemetryWasSaved() {
        production.record(tenant, device, raw, false);
        TsKvEntry corrected = new BasicTsKvEntry(raw.get(7).getTs(), new LongDataEntry("totalCount", 1200L));
        raw.set(7, corrected);
        try (var repair = repair()) {
            repair.enqueue(device, List.of(corrected));
            finish(repair);
            assertEquals(1200L, (long) jdbc.queryForObject("SELECT counter FROM production_sample WHERE device_id=? AND ts=?",
                    Long.class, device.getId(), corrected.getTs()));
            long once = quantity();
            repair.enqueue(device, List.of(corrected));
            finish(repair);
            assertEquals(once, quantity());
        }
    }

    @Test
    public void staleSourceSnapshotCannotOverwriteANewerSuccessfulCalculation() {
        production.record(tenant, device, raw, false);
        long oldRevision = production.revision(device);
        TsKvEntry correction = new BasicTsKvEntry(raw.get(7).getTs(), new LongDataEntry("totalCount", 1200L));
        production.record(tenant, device, List.of(correction), true);
        assertFalse(production.reconcile(tenant, device, raw, oldRevision));
        assertEquals(1200L, (long) jdbc.queryForObject("SELECT counter FROM production_sample WHERE device_id=? AND ts=?",
                Long.class, device.getId(), correction.getTs()));
    }

    @Test
    public void startupSweepDoesNotEnrollDevicesWithoutProductionTelemetry() {
        raw.clear();
        try (var repair = repair()) {
            repair.initialize(System.currentTimeMillis());
            finish(repair);
            assertEquals(0L, (long) jdbc.queryForObject("SELECT COUNT(*) FROM production_device WHERE device_id=?",
                    Long.class, device.getId()));
        }
    }
}
