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

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executors;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.thingsboard.server.service.production.ProductionCalculator.*;
import static org.thingsboard.server.service.production.ProductionModels.*;

public class DeviceProductionServiceTest {
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18");
    private static JdbcTemplate jdbc;
    private DeviceProductionService service;
    private DeviceId device;
    private TenantId tenant;
    private long start;

    @BeforeClass
    public static void database() {
        POSTGRES.start();
        jdbc = new JdbcTemplate(new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
        jdbc.execute("CREATE TABLE customer(id uuid PRIMARY KEY, tenant_id uuid, title text)");
        jdbc.execute("CREATE TABLE device(id uuid PRIMARY KEY, tenant_id uuid, customer_id uuid, name text DEFAULT 'Production test device', label text)");
    }

    @AfterClass
    public static void stopDatabase() { POSTGRES.stop(); }

    @Before
    public void setup() {
        TimeseriesService timeseries = mock(TimeseriesService.class);
        when(timeseries.findLatest(any(), any(), eq("speed"))).thenReturn(Futures.immediateFuture(Optional.empty()));
        service = new DeviceProductionService(jdbc, new DataSourceTransactionManager(jdbc.getDataSource()), timeseries);
        service.initSchema();
        device = new DeviceId(UUID.randomUUID());
        tenant = new TenantId(UUID.randomUUID());
        jdbc.update("INSERT INTO device(id, tenant_id) VALUES (?, ?)", device.getId(), tenant.getId());
        start = dayStart(LocalDate.of(2026, 1, 13), Config.defaults());
    }

    private void count(long offset, long counter, boolean overwrite) {
        service.record(tenant, device, List.of(new BasicTsKvEntry(start + offset, new LongDataEntry("totalCount", counter))), overwrite);
    }

    private long quantity(long from, long until) {
        return service.report(tenant, device, start + from, start + until, "day", null).rows().stream().mapToLong(Row::quantity).sum();
    }

    @Test
    public void unitRenameIsImmediatePersistentAndDeviceScopedWithoutChangingTotals() {
        count(0, 1000, false);
        count(2000, 1010, false);
        Config defaults = Config.defaults();
        Settings saved = service.saveSettings(tenant, device, new SaveSettings(
                new Config("张", defaults.timezone(), defaults.reportIntervalSeconds(), defaults.shifts()), null));
        assertEquals("张", saved.current().unit());
        assertEquals(1, saved.versions().size());
        assertEquals("张", service.overview(tenant, device).config().unit());
        assertTrue(service.report(tenant, device, start, start + 2000, "shift", null).rows().stream()
                .allMatch(row -> "张".equals(row.unit())));
        assertEquals(10, quantity(0, 2000));
        assertEquals(2L, (long) jdbc.queryForObject("SELECT COUNT(*) FROM production_sample WHERE device_id=?", Long.class, device.getId()));
        assertEquals(10L, (long) jdbc.queryForObject("SELECT SUM(quantity) FROM production_summary WHERE device_id=? AND shift_index=-1", Long.class, device.getId()));
        DeviceId other = new DeviceId(UUID.randomUUID());
        jdbc.update("INSERT INTO device(id) VALUES (?)", other.getId());
        assertEquals("贴", service.settings(tenant, other).current().unit());
        var restarted = new DeviceProductionService(jdbc, new DataSourceTransactionManager(jdbc.getDataSource()), mock(TimeseriesService.class));
        restarted.initSchema();
        assertEquals("张", restarted.settings(tenant, device).current().unit());
    }

    @Test
    public void legacyPendingUnitAppliesNowAndRenamePreservesScheduledShifts() {
        service.settings(tenant, device);
        Config future = new Config("张", "Asia/Shanghai", 2,
                List.of(new Shift("早班", "07:00"), new Shift("中班", "15:00"), new Shift("夜班", "23:00")));
        long effective = dayStart(LocalDate.now().plusDays(2), Config.defaults());
        jdbc.update("INSERT INTO production_config(device_id, effective_ts, config) VALUES (?, ?, ?::jsonb)",
                device.getId(), effective, org.thingsboard.common.util.JacksonUtil.toString(future));
        assertEquals("张", service.settings(tenant, device).current().unit());
        assertEquals("张", service.overview(tenant, device).config().unit());
        Settings saved = service.saveSettings(tenant, device, new SaveSettings(
                new Config("页", future.timezone(), future.reportIntervalSeconds(), future.shifts()), null));
        assertEquals(2, saved.versions().size());
        assertEquals(effective, saved.versions().getLast().effectiveTs());
        assertEquals(2, saved.current().shifts().size());
        assertEquals(3, saved.versions().getLast().config().shifts().size());
        assertTrue(saved.versions().stream().allMatch(version -> "页".equals(version.config().unit())));
        assertEquals(2L, (long) jdbc.queryForObject("SELECT COUNT(*) FROM production_config WHERE device_id=? AND config->>'unit'='页'", Long.class, device.getId()));
        assertThrows(IllegalArgumentException.class, () -> service.saveSettings(tenant, device, new SaveSettings(
                new Config("米", "Asia/Shanghai", 2, Config.defaults().shifts()), LocalDate.now().minusDays(1).toString())));
        assertEquals("页", service.settings(tenant, device).current().unit());
        assertEquals(effective, service.settings(tenant, device).versions().getLast().effectiveTs());
    }

    @Test
    public void deduplicatesAndCountsMultipleResets() {
        count(0, 1000, false);
        count(2000, 1010, false);
        count(2000, 1010, false);
        count(4000, 0, false);
        count(6000, 7, false);
        count(8000, 0, false);
        count(10000, 3, false);
        assertEquals(20, quantity(0, 10000));
        assertEquals(6L, (long) jdbc.queryForObject("SELECT COUNT(*) FROM production_sample WHERE device_id=?", Long.class, device.getId()));
        assertEquals(2, service.report(tenant, device, start, start + 10000, "shift", null).rows().getFirst().resets());
    }

    @Test
    public void repairsLateReadingAndItsSuccessorAtomically() {
        count(0, 1000, false);
        count(4000, 10, false);
        assertEquals(10, quantity(0, 4000));
        count(2000, 1005, false);
        assertEquals(15, quantity(0, 4000));
        count(2000, 1008, true);
        assertEquals(18, quantity(0, 4000));
        assertEquals(18L, (long) jdbc.queryForObject("SELECT SUM(quantity) FROM production_minute WHERE device_id=?", Long.class, device.getId()));
        assertEquals(18L, (long) jdbc.queryForObject("SELECT SUM(quantity) FROM production_summary WHERE device_id=? AND shift_index=-1", Long.class, device.getId()));
    }

    @Test
    public void preservesTotalsAcrossShiftAndMinuteBoundaries() {
        long boundary = 12 * 60 * MINUTE;
        count(boundary - 1000, 1000, false);
        count(boundary + 1000, 1003, false);
        var rows = service.report(tenant, device, start, start + 86400000, "shift", null).rows();
        assertEquals(1, rows.getFirst().quantity());
        assertEquals(2, rows.getLast().quantity());
        assertEquals(3, quantity(boundary - 1000, boundary + 1000));
        assertEquals(1, quantity(boundary - 1000, boundary));
        assertEquals(2, quantity(boundary, boundary + 1000));
    }

    @Test
    public void baselineOnlyAndNoDataAreDifferentFromStoppedZero() {
        assertEquals("NO_DATA", service.report(tenant, device, start, start + 2000, "day", null).rows().getFirst().status());
        count(0, 1000, false);
        count(2000, 1000, false);
        var row = service.report(tenant, device, start, start + 2000, "day", null).rows().getFirst();
        assertEquals(0, row.quantity());
        assertEquals(2000, row.observedMs());
        assertEquals("COMPLETE", row.status());
    }

    @Test
    public void concurrentRetriesDoNotMultiplyProduction() throws Exception {
        count(0, 1000, false);
        try (var executor = Executors.newFixedThreadPool(4)) {
            var tasks = new java.util.ArrayList<java.util.concurrent.Future<?>>();
            for (int i = 0; i < 12; i++) tasks.add(executor.submit(() -> count(2000, 1010, false)));
            for (var task : tasks) task.get();
        }
        assertEquals(10, quantity(0, 2000));
    }

    @Test
    public void restartRestoresBaselineAndDeviceDeletionCascades() {
        count(0, 1000, false);
        setupServiceAgain();
        count(2000, 1010, false);
        assertEquals(10, quantity(0, 2000));
        jdbc.update("DELETE FROM device WHERE id=?", device.getId());
        assertEquals(0L, (long) jdbc.queryForObject("SELECT COUNT(*) FROM production_sample WHERE device_id=?", Long.class, device.getId()));
    }

    @Test
    public void backfillsPaginatedTelemetryWithoutDuplicatingExistingCounts() throws Exception {
        List<TsKvEntry> history = new java.util.ArrayList<>();
        for (int i = 0; i < 510; i++) {
            history.add(new BasicTsKvEntry(start + i * 2000L, new LongDataEntry("totalCount", i < 300 ? 1000L + i : i - 300L)));
        }
        TimeseriesService source = mock(TimeseriesService.class);
        when(source.findAll(any(), any(), anyList())).thenAnswer(call -> {
            ReadTsKvQuery query = ((List<ReadTsKvQuery>) call.getArgument(2)).getFirst();
            var ordered = history.stream().filter(entry -> entry.getTs() >= query.getStartTs() && entry.getTs() < query.getEndTs());
            if ("DESC".equals(query.getOrder())) ordered = ordered.sorted(java.util.Comparator.comparingLong(TsKvEntry::getTs).reversed());
            return Futures.immediateFuture(ordered.limit(query.getLimit()).toList());
        });
        try (var job = new ProductionBackfillService(jdbc, source, service)) {
            job.start(tenant, device, new BackfillRequest(start, start + 510 * 2000L));
            long deadline = System.currentTimeMillis() + 30000;
            while ("RUNNING".equals(job.get(device).status()) && System.currentTimeMillis() < deadline) Thread.sleep(30);
            assertEquals("COMPLETE", job.get(device).status());
            assertEquals(510, job.get(device).processed());
            assertEquals(508, quantity(0, 510 * 2000L));
            count(2000, 1001, false);
            assertEquals(508, quantity(0, 510 * 2000L));
        }
    }

    private void setupServiceAgain() {
        service = new DeviceProductionService(jdbc, new DataSourceTransactionManager(jdbc.getDataSource()), mock(TimeseriesService.class));
    }

    @Test
    public void screenUsesCurrentOwningCustomerWithinTheDeviceTenant() throws Exception {
        TimeseriesService source = mock(TimeseriesService.class);
        when(source.findAll(any(), any(), anyList())).thenReturn(Futures.immediateFuture(List.of()));
        var screens = new ProductionScreenService(jdbc, new DataSourceTransactionManager(jdbc.getDataSource()), service, source);
        ScreenLink link = screens.create(tenant, device, false);
        assertEquals("", screens.view(device, link.key()).customerName());
        UUID customer = UUID.randomUUID();
        jdbc.update("INSERT INTO customer(id, tenant_id, title) VALUES (?, ?, ?)", customer, tenant.getId(), "客户甲");
        jdbc.update("UPDATE device SET customer_id=? WHERE id=?", customer, device.getId());
        assertEquals("客户甲", screens.view(device, link.key()).customerName());
        jdbc.update("UPDATE customer SET title=? WHERE id=?", "客户甲新名称", customer);
        assertEquals("客户甲新名称", screens.view(device, link.key()).customerName());
        UUID other = UUID.randomUUID();
        jdbc.update("INSERT INTO customer(id, tenant_id, title) VALUES (?, ?, ?)", other, tenant.getId(), "客户乙");
        jdbc.update("UPDATE device SET customer_id=? WHERE id=?", other, device.getId());
        assertEquals("客户乙", screens.view(device, link.key()).customerName());
        jdbc.update("UPDATE customer SET tenant_id=? WHERE id=?", UUID.randomUUID(), other);
        assertEquals("", screens.view(device, link.key()).customerName());
        jdbc.update("UPDATE device SET customer_id=NULL WHERE id=?", device.getId());
        assertEquals("", screens.view(device, link.key()).customerName());
        assertEquals(link, screens.link(tenant, device));
    }

    @Test
    public void screenReturnsPersistedHistoryWhenSpeedQueryFailsOrTimesOut() throws Exception {
        long first = System.currentTimeMillis() - 120000;
        service.record(tenant, device, List.of(new BasicTsKvEntry(first, new LongDataEntry("totalCount", 1000L)),
                new BasicTsKvEntry(first + 2000, new LongDataEntry("totalCount", 1010L))), false);
        TimeseriesService source = mock(TimeseriesService.class);
        when(source.findLatest(any(), any(), eq("speed"))).thenReturn(Futures.immediateFuture(Optional.empty()));
        when(source.findAll(any(), any(), anyList())).thenReturn(Futures.immediateFailedFuture(new IllegalStateException("Speed storage unavailable")));
        var transactions = new DataSourceTransactionManager(jdbc.getDataSource());
        var production = new DeviceProductionService(jdbc, transactions, source);
        var screens = new ProductionScreenService(jdbc, transactions, production, source);
        ScreenLink link = screens.create(tenant, device, false);
        ScreenView view = screens.view(device, link.key());
        assertTrue(view.speedUnavailable());
        assertEquals(10, view.output().rows().stream().mapToLong(Row::quantity).sum());
        assertEquals(first + 2000, (long) view.overview().lastSampleTs());
        assertTrue(view.speed().isEmpty());
        var pending = com.google.common.util.concurrent.SettableFuture.<List<TsKvEntry>>create();
        when(source.findAll(any(), any(), anyList())).thenReturn(pending);
        ScreenView timedOut = screens.view(device, link.key());
        assertTrue(timedOut.speedUnavailable());
        assertTrue(pending.isCancelled());
        assertEquals(10, timedOut.output().rows().stream().mapToLong(Row::quantity).sum());
        screens.revoke(tenant, device);
        assertThrows(org.thingsboard.server.exception.ThingsboardRuntimeException.class, () -> screens.view(device, link.key()));
    }

    @Test
    public void screenReturnsHistoryForADeviceThatStoppedReporting() throws Exception {
        long first = System.currentTimeMillis() - 120000;
        service.record(tenant, device, List.of(new BasicTsKvEntry(first, new LongDataEntry("totalCount", 1000L)),
                new BasicTsKvEntry(first + 2000, new LongDataEntry("totalCount", 1010L))), false);
        TimeseriesService source = mock(TimeseriesService.class);
        when(source.findLatest(any(), any(), eq("speed"))).thenReturn(Futures.immediateFuture(
                Optional.of(new BasicTsKvEntry(first, new LongDataEntry("speed", 8400L)))));
        when(source.findAll(any(), any(), anyList())).thenReturn(Futures.immediateFuture(List.of(
                new BasicTsKvEntry(first, new LongDataEntry("speed", 8400L)))));
        var transactions = new DataSourceTransactionManager(jdbc.getDataSource());
        var screens = new ProductionScreenService(jdbc, transactions,
                new DeviceProductionService(jdbc, transactions, source), source);
        ScreenView view = screens.view(device, screens.create(tenant, device, false).key());
        assertFalse(view.speedUnavailable());
        assertEquals(10, view.output().rows().stream().mapToLong(Row::quantity).sum());
        assertTrue(view.overview().now() - view.overview().lastSampleTs() > 10000);
        assertEquals(first, view.speed().getFirst().ts());
    }

    @Test
    public void screenLinkSurvivesServiceRestartAndSchemaInitialization() {
        var transactions = new DataSourceTransactionManager(jdbc.getDataSource());
        var screens = new ProductionScreenService(jdbc, transactions, service, mock(TimeseriesService.class));
        ScreenLink link = screens.create(tenant, device, false);
        service.initSchema();
        var restarted = new ProductionScreenService(jdbc, transactions, service, mock(TimeseriesService.class));
        assertEquals(link, restarted.link(tenant, device));
        assertEquals(link, restarted.create(tenant, device, false));
        assertNotEquals(link.key(), restarted.create(tenant, device, true).key());
    }

    @Test
    public void concurrentScreenCreationReturnsOneStableLinkPerDevice() throws Exception {
        var screens = new ProductionScreenService(jdbc, new DataSourceTransactionManager(jdbc.getDataSource()),
                service, mock(TimeseriesService.class));
        try (var executor = Executors.newFixedThreadPool(4)) {
            var futures = new java.util.ArrayList<java.util.concurrent.Future<ScreenLink>>();
            for (int i = 0; i < 8; i++) futures.add(executor.submit(() -> screens.create(tenant, device, false)));
            ScreenLink link = futures.getFirst().get();
            for (var future : futures) assertEquals(link, future.get());
        }
    }
}
