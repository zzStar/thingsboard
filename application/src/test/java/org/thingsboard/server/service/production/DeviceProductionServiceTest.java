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
import java.time.ZoneId;
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
        jdbc.execute("TRUNCATE production_migration");
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

    private void legacySchedule(DeviceId target) {
        service.settings(tenant, target);
        jdbc.update("UPDATE production_config SET config=jsonb_set(config,'{shifts}',?::jsonb) WHERE device_id=?",
                org.thingsboard.common.util.JacksonUtil.toString(LEGACY_DEFAULT_SHIFTS), target.getId());
    }

    private void configVersion(long effective, Config config) {
        jdbc.update("INSERT INTO production_config(device_id,effective_ts,config) VALUES (?,?,?::jsonb)",
                device.getId(), effective, org.thingsboard.common.util.JacksonUtil.toString(config));
    }

    private void assertFullDefaultShifts(long day) {
        var rows = service.report(tenant, device, day, day + 86400000, "shift", null).rows();
        assertEquals(2, rows.size());
        assertEquals("白班", rows.getFirst().shiftName());
        assertEquals(day, rows.getFirst().startTs());
        assertEquals(day + 12 * 60 * MINUTE, rows.getFirst().endTs());
        assertEquals("夜班", rows.getLast().shiftName());
        assertEquals(day + 12 * 60 * MINUTE, rows.getLast().startTs());
        assertEquals(day + 86400000, rows.getLast().endTs());
    }

    @Test
    public void defaultMigrationRemovesTheOldSevenOClockBoundaryWhenVersionsBecomeIdentical() {
        legacySchedule(device);
        long tomorrow = dayStart(LocalDate.now(ZoneId.of("Asia/Shanghai")).plusDays(1), Config.defaults());
        configVersion(tomorrow - 30 * MINUTE, Config.defaults());
        count(0, 1000, false);
        count(2000, 1010, false);
        assertEquals(3, service.report(tenant, device, tomorrow - 86400000, tomorrow, "shift", null).rows().size());

        assertTrue(service.migrateDefaultSchedule(tenant, device));

        assertEquals(1, service.settings(tenant, device).versions().size());
        assertFullDefaultShifts(tomorrow - 86400000);
        assertEquals(10, quantity(0, 86400000));
        assertEquals(10L, (long) jdbc.queryForObject("SELECT SUM(quantity) FROM production_summary WHERE device_id=? AND shift_index=-1", Long.class, device.getId()));
    }

    @Test
    public void defaultScheduleMigrationRegroupsAllHistoryAndPreservesUnitsAndCustomVersions() {
        legacySchedule(device);
        count(-31 * MINUTE, 1000, false);
        count(-29 * MINUTE, 1010, false);
        count(-MINUTE, 1020, false);
        count(MINUTE, 1030, false);
        count(12 * 60 * MINUTE - MINUTE, 1130, false);
        count(12 * 60 * MINUTE + MINUTE, 1140, false);
        jdbc.update("UPDATE production_config SET config=jsonb_set(config,'{unit}','\"张\"') WHERE device_id=?", device.getId());
        Config future = new Config("张", "Asia/Shanghai", 2,
                List.of(new Shift("早班", "08:00"), new Shift("中班", "16:00"), new Shift("夜班", "00:00")));
        long effective = dayStart(LocalDate.now().plusDays(2), Config.defaults());
        jdbc.update("INSERT INTO production_config(device_id,effective_ts,config) VALUES (?,?,?::jsonb)",
                device.getId(), effective, org.thingsboard.common.util.JacksonUtil.toString(future));
        assertTrue(service.migrateDefaultSchedule(tenant, device));
        assertEquals(Config.defaults().shifts(), service.settings(tenant, device).current().shifts());
        assertEquals("张", service.settings(tenant, device).current().unit());
        assertEquals(future, service.settings(tenant, device).versions().getLast().config());
        assertEquals(25, quantity(-86400000, 0));
        assertEquals(115, quantity(0, 86400000));
        var rows = service.report(tenant, device, start, start + 86400000, "shift", null).rows();
        assertEquals(110, rows.getFirst().quantity());
        assertEquals(5, rows.getLast().quantity());
        assertEquals(140L, (long) jdbc.queryForObject("SELECT SUM(quantity) FROM production_minute WHERE device_id=?", Long.class, device.getId()));
        assertEquals(140L, (long) jdbc.queryForObject("SELECT SUM(quantity) FROM production_summary WHERE device_id=? AND shift_index=-1", Long.class, device.getId()));
        // Full stored periods and independently allocated partial ranges must agree.
        assertEquals(quantity(0, 12 * 60 * MINUTE), rows.getFirst().quantity());
        assertFalse(service.migrateDefaultSchedule(tenant, device));
    }

    @Test
    public void migrationFindsAllLegacyDefaultDevicesOnceAndKeepsCustomSchedules() {
        legacySchedule(device);
        DeviceId other = new DeviceId(UUID.randomUUID());
        jdbc.update("INSERT INTO device(id,tenant_id) VALUES (?,?)", other.getId(), tenant.getId());
        legacySchedule(other);
        DeviceId customDevice = new DeviceId(UUID.randomUUID());
        jdbc.update("INSERT INTO device(id,tenant_id) VALUES (?,?)", customDevice.getId(), tenant.getId());
        service.settings(tenant, customDevice);
        var custom = new Config("米", "Asia/Shanghai", 5,
                List.of(new Shift("早班", "08:00"), new Shift("晚班", "20:00")));
        jdbc.update("UPDATE production_config SET config=?::jsonb WHERE device_id=?",
                org.thingsboard.common.util.JacksonUtil.toString(custom), customDevice.getId());
        try (var first = new ProductionDefaultScheduleMigration(jdbc, service)) {
            assertFalse(first.tick());
        }
        try (var restarted = new ProductionDefaultScheduleMigration(jdbc, service)) {
            assertFalse(restarted.tick());
            assertTrue(restarted.tick());
            assertEquals(Config.defaults().shifts(), service.settings(tenant, device).current().shifts());
            assertEquals(Config.defaults().shifts(), service.settings(tenant, other).current().shifts());
            assertEquals(custom, service.settings(tenant, customDevice).current());
            assertEquals(1L, (long) jdbc.queryForObject("SELECT COUNT(*) FROM production_migration WHERE name=?",
                    Long.class, ProductionDefaultScheduleMigration.NAME));
            // An intentional user schedule made after this one-time migration is left alone.
            legacySchedule(device);
            assertTrue(restarted.tick());
            assertEquals(LEGACY_DEFAULT_SHIFTS, service.settings(tenant, device).current().shifts());
        }
    }

    @Test
    public void failedHistoricalRebuildRollsBackConfigurationAndSummariesAndCanBeRetried() {
        legacySchedule(device);
        count(0, 1000, false);
        count(2000, 1010, false);
        jdbc.execute("CREATE FUNCTION reject_production_rebuild() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN "
                + "RAISE EXCEPTION 'test rebuild failure'; END $$");
        jdbc.execute("CREATE TRIGGER reject_production_rebuild BEFORE INSERT ON production_summary "
                + "FOR EACH ROW EXECUTE FUNCTION reject_production_rebuild()");
        try {
            assertThrows(org.springframework.dao.DataAccessException.class, () -> service.migrateDefaultSchedule(tenant, device));
            assertEquals(LEGACY_DEFAULT_SHIFTS, service.settings(tenant, device).current().shifts());
            assertEquals(10, quantity(0, 2000));
            assertEquals(10L, (long) jdbc.queryForObject("SELECT SUM(quantity) FROM production_summary WHERE device_id=? AND shift_index=-1", Long.class, device.getId()));
        } finally {
            jdbc.execute("DROP TRIGGER reject_production_rebuild ON production_summary");
            jdbc.execute("DROP FUNCTION reject_production_rebuild()");
        }
        assertTrue(service.migrateDefaultSchedule(tenant, device));
        assertEquals(Config.defaults().shifts(), service.settings(tenant, device).current().shifts());
        assertEquals(10, quantity(0, 2000));
    }

    @Test
    public void historicalRebuildAllocatesLargeCountersExactlyAcrossTheNewShiftBoundary() {
        legacySchedule(device);
        long boundary = 12 * 60 * MINUTE;
        count(boundary - 1, 0, false);
        count(boundary + 1, Long.MAX_VALUE, false);
        assertTrue(service.migrateDefaultSchedule(tenant, device));
        var rows = service.report(tenant, device, start, start + 86400000, "shift", null).rows();
        assertEquals(Long.MAX_VALUE / 2, rows.getFirst().quantity());
        assertEquals(Long.MAX_VALUE / 2 + 1, rows.getLast().quantity());
        assertEquals(Long.MAX_VALUE, quantity(0, 86400000));
    }

    @Test
    public void historicalMigrationRepairsLegacyZeroReboundsBeforeRebuildingDailyAndShiftTotals() {
        legacySchedule(device);
        count(0, 1924, false);
        count(1000, 0, false);
        count(1352, 1924, false);
        // Reproduce the old persisted calculation for a PLC startup bounce.
        jdbc.update("UPDATE production_sample SET gap=false,reset=(ts=?),delta=CASE WHEN ts=? THEN 1924 ELSE 0 END WHERE device_id=?",
                start + 1000, start + 1352, device.getId());
        jdbc.update("UPDATE production_minute SET quantity=1924,observed_ms=1352,estimated_ms=0,resets=1 WHERE device_id=?", device.getId());
        jdbc.update("UPDATE production_summary SET quantity=1924,observed_ms=1352,estimated_ms=0,resets=1 WHERE device_id=?", device.getId());
        assertTrue(service.migrateDefaultSchedule(tenant, device));
        assertEquals(0, quantity(0, MINUTE));
        assertEquals(0L, (long) jdbc.queryForObject("SELECT SUM(quantity) FROM production_summary WHERE device_id=? AND shift_index=-1", Long.class, device.getId()));
        assertEquals(0L, (long) jdbc.queryForObject("SELECT SUM(quantity) FROM production_minute WHERE device_id=?", Long.class, device.getId()));
        assertEquals(0L, (long) jdbc.queryForObject("SELECT COUNT(*) FROM production_sample WHERE device_id=? AND reset", Long.class, device.getId()));
    }

    private List<TsKvEntry> infraredEntries() {
        long[][] values = {{0, 14}, {2000, 606}, {5696, 512}, {9385, 0}, {13080, 1}, {16767, 8}};
        return java.util.Arrays.stream(values).map(value -> (TsKvEntry) new BasicTsKvEntry(
                start + value[0], new LongDataEntry("totalCount", value[1]))).toList();
    }

    @Test
    public void arrivingZeroRemovesTheInfraredIntermediateAndKeepsOneResetAndEightNewUnits() {
        var entries = infraredEntries();
        service.record(tenant, device, entries.subList(0, 3), false);
        assertEquals(1104, quantity(0, 60000));

        service.record(tenant, device, entries.subList(3, 4), false);
        assertEquals(592, quantity(0, 60000));
        service.record(tenant, device, entries.subList(4, 6), false);

        assertEquals(600, quantity(0, 60000));
        assertEquals(512L, (long) jdbc.queryForObject("SELECT counter FROM production_sample WHERE device_id=? AND ts=?", Long.class, device.getId(), start + 5696));
        assertEquals(0L, (long) jdbc.queryForObject("SELECT delta FROM production_sample WHERE device_id=? AND ts=?", Long.class, device.getId(), start + 5696));
        assertEquals(1L, (long) jdbc.queryForObject("SELECT COUNT(*) FROM production_sample WHERE device_id=? AND reset", Long.class, device.getId()));
        assertEquals(7385L, (long) jdbc.queryForObject("SELECT SUM(estimated_ms) FROM production_minute WHERE device_id=?", Long.class, device.getId()));
        assertEquals(600L, (long) jdbc.queryForObject("SELECT SUM(quantity) FROM production_summary WHERE device_id=? AND shift_index=-1", Long.class, device.getId()));
        assertEquals(600L, (long) jdbc.queryForObject("SELECT SUM(quantity) FROM production_summary WHERE device_id=? AND shift_index>=0", Long.class, device.getId()));
        setupServiceAgain();
        service.record(tenant, device, entries, false);
        assertEquals(600, quantity(0, 60000));
    }

    @Test
    public void lateZeroAndExplicitOverwriteCanConfirmAndRevokeIntermediateSuppression() {
        var entries = infraredEntries();
        service.record(tenant, device, entries.subList(0, 3), false);
        count(9385, 520, false);
        assertEquals(1112, quantity(0, 60000));

        count(9385, 0, true);
        assertEquals(592, quantity(0, 60000));
        count(9385, 520, true);
        assertEquals(1112, quantity(0, 60000));
        assertEquals(0L, (long) jdbc.queryForObject("SELECT SUM(estimated_ms) FROM production_minute WHERE device_id=?", Long.class, device.getId()));
        count(9385, 0, true);
        service.record(tenant, device, entries.subList(4, 6), false);
        assertEquals(600, quantity(0, 60000));
    }

    @Test
    public void outOfOrderZeroRepairsTheAlreadyStoredSuccessorAndAllAggregates() {
        var entries = infraredEntries();
        service.record(tenant, device, entries.subList(0, 3), false);
        service.record(tenant, device, entries.subList(4, 6), false);
        assertEquals(1112, quantity(0, 60000));

        service.record(tenant, device, entries.subList(3, 4), false);

        assertEquals(600, quantity(0, 60000));
        assertEquals(1L, (long) jdbc.queryForObject("SELECT COUNT(*) FROM production_sample WHERE device_id=? AND reset", Long.class, device.getId()));
        assertEquals(600L, (long) jdbc.queryForObject("SELECT SUM(delta) FROM production_sample WHERE device_id=?", Long.class, device.getId()));
    }

    @Test
    public void unchangedHistoricalReplayAndRepairRemoveTheLegacy512WithoutChangingSourceCounters() {
        var entries = infraredEntries();
        service.record(tenant, device, entries, false);
        String original = jdbc.queryForObject("SELECT md5(string_agg(ts||':'||counter||':'||COALESCE(previous_ts,-1),',' ORDER BY ts)) FROM production_sample WHERE device_id=?", String.class, device.getId());
        // Recreate the old algorithm's coherent source facts, minute totals and two summary totals.
        jdbc.update("UPDATE production_sample SET delta=CASE WHEN ts=? THEN 512 ELSE 0 END,reset=true,gap=false WHERE device_id=? AND ts IN (?,?)",
                start + 5696, device.getId(), start + 5696, start + 9385);
        jdbc.update("UPDATE production_minute SET quantity=quantity+512,observed_ms=observed_ms+7385,estimated_ms=estimated_ms-7385,resets=resets+1 WHERE device_id=? AND minute_ts=?",
                device.getId(), start);
        jdbc.update("UPDATE production_summary SET quantity=quantity+512,observed_ms=observed_ms+7385,estimated_ms=estimated_ms-7385,resets=resets+1 WHERE device_id=?", device.getId());
        assertEquals(1112, quantity(0, 60000));

        assertTrue(service.reconcile(tenant, device, entries.subList(3, 4), service.revision(device)));
        assertEquals(600, quantity(0, 60000));
        service.record(tenant, device, entries, false);

        assertEquals(600, quantity(0, 60000));
        assertEquals(original, jdbc.queryForObject("SELECT md5(string_agg(ts||':'||counter||':'||COALESCE(previous_ts,-1),',' ORDER BY ts)) FROM production_sample WHERE device_id=?", String.class, device.getId()));
        assertEquals(600L, (long) jdbc.queryForObject("SELECT SUM(quantity) FROM production_minute WHERE device_id=?", Long.class, device.getId()));
        assertEquals(600L, (long) jdbc.queryForObject("SELECT SUM(quantity) FROM production_summary WHERE device_id=? AND shift_index=-1", Long.class, device.getId()));
        assertEquals(600L, (long) jdbc.queryForObject("SELECT SUM(quantity) FROM production_summary WHERE device_id=? AND shift_index>=0", Long.class, device.getId()));
        assertEquals(1L, (long) jdbc.queryForObject("SELECT SUM(resets) FROM production_minute WHERE device_id=?", Long.class, device.getId()));
    }

    @Test
    public void transientZeroBurstAfterAnOutageDoesNotAddOldProductionAndSurvivesRestart() {
        count(-36 * 60 * MINUTE, 1924, false);
        count(0, 0, false);
        for (int i = 346; i <= 351; i++) count(i, 0, false);
        count(352, 1924, false);
        assertEquals(0, quantity(0, 60000));
        assertEquals(7L, (long) jdbc.queryForObject("SELECT COUNT(*) FROM production_sample WHERE device_id=? AND counter=0",
                Long.class, device.getId()));
        assertEquals(0L, (long) jdbc.queryForObject("SELECT COUNT(*) FROM production_sample WHERE device_id=? AND reset",
                Long.class, device.getId()));
        setupServiceAgain();
        count(352, 1924, false);
        count(9 * MINUTE, 1924, false);
        count(9 * MINUTE + 1000, 0, false);
        count(9 * MINUTE + 37000, 0, false);
        count(9 * MINUTE + 38000, 1, false);
        count(59 * MINUTE, 6241, false);
        assertEquals(6241, quantity(0, 60 * MINUTE));
        assertEquals(6241L, (long) jdbc.queryForObject("SELECT SUM(quantity) FROM production_minute WHERE device_id=?", Long.class, device.getId()));
        assertEquals(6241L, (long) jdbc.queryForObject("SELECT SUM(quantity) FROM production_summary WHERE device_id=? AND shift_index=-1", Long.class, device.getId()));
        assertEquals(1L, (long) jdbc.queryForObject("SELECT COUNT(*) FROM production_sample WHERE device_id=? AND reset", Long.class, device.getId()));
    }

    @Test
    public void lateZerosAndOverwrittenDistantBaselinesReconcileAllDependentContributions() {
        long reconnect = 36 * 60 * MINUTE;
        count(0, 1924, false);
        count(reconnect + 352, 1924, false);
        count(reconnect, 0, false);
        assertEquals(0, quantity(reconnect, reconnect + MINUTE));
        count(0, 1900, true);
        assertEquals(1924, quantity(reconnect, reconnect + MINUTE));
        count(0, 1924, true);
        assertEquals(0, quantity(reconnect, reconnect + MINUTE));
        count(reconnect, 1, true);
        assertEquals(1923, quantity(reconnect, reconnect + MINUTE));
        assertEquals(1924, quantity(0, reconnect + MINUTE));
        count(reconnect, 0, true);
        assertEquals(0, quantity(reconnect, reconnect + MINUTE));
        assertEquals(0L, (long) jdbc.queryForObject("SELECT SUM(quantity) FROM production_minute WHERE device_id=?", Long.class, device.getId()));
        assertEquals(0L, (long) jdbc.queryForObject("SELECT SUM(quantity) FROM production_summary WHERE device_id=? AND shift_index=-1", Long.class, device.getId()));
        assertEquals(0L, (long) jdbc.queryForObject("SELECT COUNT(*) FROM production_minute WHERE device_id=? AND (observed_ms<0 OR estimated_ms<0 OR resets<0)", Long.class, device.getId()));
    }

    @Test
    public void replayOfUnchangedLegacyCountersRepairsStoredTotalsAndDoesNotReintroduceTheBounce() {
        count(0, 1924, false);
        count(1000, 0, false);
        count(1100, 0, false);
        count(1352, 1924, false);
        // Recreate the persisted output of the old algorithm without changing any raw counters.
        jdbc.update("UPDATE production_sample SET gap=false,reset=(ts=?),delta=CASE WHEN ts=? THEN 1924 ELSE 0 END WHERE device_id=?",
                start + 1000, start + 1352, device.getId());
        jdbc.update("UPDATE production_minute SET quantity=1924,observed_ms=1352,estimated_ms=0,resets=1 WHERE device_id=?", device.getId());
        jdbc.update("UPDATE production_summary SET quantity=1924,observed_ms=1352,estimated_ms=0,resets=1 WHERE device_id=?", device.getId());
        assertEquals(1924, quantity(0, MINUTE));
        count(1352, 1924, false);
        assertEquals(0, quantity(0, MINUTE));
        List<TsKvEntry> replay = List.of(new BasicTsKvEntry(start + 1000, new LongDataEntry("totalCount", 0L)),
                new BasicTsKvEntry(start + 1352, new LongDataEntry("totalCount", 1924L)));
        assertTrue(service.reconcile(tenant, device, replay, service.revision(device)));
        assertEquals(0, quantity(0, MINUTE));
        assertEquals(0L, (long) jdbc.queryForObject("SELECT SUM(quantity) FROM production_summary WHERE device_id=? AND shift_index=-1", Long.class, device.getId()));
        assertEquals(1352L, (long) jdbc.queryForObject("SELECT SUM(estimated_ms) FROM production_minute WHERE device_id=?", Long.class, device.getId()));
        assertEquals(0L, (long) jdbc.queryForObject("SELECT SUM(observed_ms) FROM production_minute WHERE device_id=?", Long.class, device.getId()));
        assertEquals(0L, (long) jdbc.queryForObject("SELECT SUM(resets) FROM production_minute WHERE device_id=?", Long.class, device.getId()));
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
    public void repeatedUnchangedSavesDoNotCreateConfigurationVersions() {
        service.settings(tenant, device);
        long revision = service.revision(device);
        for (int i = 1; i <= 3; i++) {
            Settings saved = service.saveSettings(tenant, device,
                    new SaveSettings(Config.defaults(), LocalDate.now().plusDays(i).toString()));
            assertEquals(1, saved.versions().size());
        }
        assertEquals(revision, service.revision(device));
        assertFullDefaultShifts(start);
    }

    @Test
    public void movingAndCancellingAPendingScheduleKeepsOnlyMeaningfulVersions() {
        count(0, 1000, false);
        count(2000, 1010, false);
        var threeShifts = new Config("张", "Asia/Shanghai", 2,
                List.of(new Shift("早班", "08:00"), new Shift("中班", "16:00"), new Shift("夜班", "00:00")));
        LocalDate date = LocalDate.now(ZoneId.of("Asia/Shanghai")).plusDays(2);
        service.saveSettings(tenant, device, new SaveSettings(threeShifts, date.toString()));
        long revision = service.revision(device);
        Settings repeated = service.saveSettings(tenant, device, new SaveSettings(threeShifts, date.toString()));
        assertEquals(2, repeated.versions().size());
        assertEquals(revision, service.revision(device));
        Settings moved = service.saveSettings(tenant, device, new SaveSettings(threeShifts, date.plusDays(1).toString()));
        assertEquals(2, moved.versions().size());
        assertEquals(dayStart(date.plusDays(1), Config.defaults()), moved.versions().getLast().effectiveTs());

        Settings cancelled = service.saveSettings(tenant, device, new SaveSettings(
                new Config("张", "Asia/Shanghai", 2, Config.defaults().shifts()), date.plusDays(1).toString()));

        assertEquals(1, cancelled.versions().size());
        assertEquals("张", cancelled.current().unit());
        assertTrue(service.revision(device) > revision);
        assertFullDefaultShifts(dayStart(date, Config.defaults()));
        assertEquals(10, quantity(0, 86400000));
    }

    @Test
    public void savingAnExistingRedundantPendingVersionRestoresTheFullNightShift() {
        count(0, 1000, false);
        count(2000, 1010, false);
        long tomorrow = dayStart(LocalDate.now(ZoneId.of("Asia/Shanghai")).plusDays(1), Config.defaults());
        configVersion(tomorrow - 30 * MINUTE, Config.defaults());
        assertEquals(3, service.report(tenant, device, tomorrow - 86400000, tomorrow, "shift", null).rows().size());
        long revision = service.revision(device);

        Settings saved = service.saveSettings(tenant, device, new SaveSettings(
                new Config("张", "Asia/Shanghai", 2, Config.defaults().shifts()), null));

        assertEquals(1, saved.versions().size());
        assertEquals("张", saved.current().unit());
        assertTrue(service.revision(device) > revision);
        assertFullDefaultShifts(tomorrow - 86400000);
        assertEquals(10, quantity(0, 86400000));
    }

    @Test
    public void removingHistoricalDuplicateVersionsRebuildsSummaryKeysAndPreservesAllCounters() {
        service.settings(tenant, device);
        long split = 24 * 60 * MINUTE - 30 * MINUTE;
        configVersion(start + split, Config.defaults());
        count(0, 1000, false);
        count(split - 1000, 1010, false);
        count(split + 1000, 1013, false);
        count(86400000 - 1000, 1020, false);
        assertEquals(3, service.report(tenant, device, start, start + 86400000, "shift", null).rows().size());
        String counters = jdbc.queryForObject("SELECT md5(string_agg(ts||':'||counter||':'||delta,',' ORDER BY ts)) FROM production_sample WHERE device_id=?", String.class, device.getId());

        Settings saved = service.saveSettings(tenant, device, new SaveSettings(Config.defaults(), null));

        assertEquals(1, saved.versions().size());
        assertFullDefaultShifts(start);
        assertEquals(20, quantity(0, 86400000));
        assertEquals(counters, jdbc.queryForObject("SELECT md5(string_agg(ts||':'||counter||':'||delta,',' ORDER BY ts)) FROM production_sample WHERE device_id=?", String.class, device.getId()));
        assertEquals(0L, (long) jdbc.queryForObject("SELECT COUNT(*) FROM production_summary WHERE device_id=? AND config_ts<>0", Long.class, device.getId()));
        assertEquals(20L, (long) jdbc.queryForObject("SELECT SUM(quantity) FROM production_summary WHERE device_id=? AND shift_index=-1", Long.class, device.getId()));
        assertEquals(20L, (long) jdbc.queryForObject("SELECT SUM(quantity) FROM production_summary WHERE device_id=? AND shift_index>=0", Long.class, device.getId()));
        // Later input still uses the retained configuration key, including updates to an existing sample.
        count(split + 1000, 1015, true);
        assertEquals(20, quantity(0, 86400000));
    }

    @Test
    public void cleanupPreservesChangesToTimezoneReportingIntervalAndReturnsToAnEarlierSchedule() {
        service.settings(tenant, device);
        var interval = new Config("贴", "Asia/Shanghai", 5, Config.defaults().shifts());
        var timezone = new Config("贴", "UTC", 5, Config.defaults().shifts());
        configVersion(start, interval);
        configVersion(start + 86400000, timezone);
        configVersion(start + 2 * 86400000, Config.defaults());

        Settings saved = service.saveSettings(tenant, device, new SaveSettings(
                new Config("张", "Asia/Shanghai", 2, Config.defaults().shifts()), null));

        assertEquals(4, saved.versions().size());
        assertEquals(5, saved.versions().get(1).config().reportIntervalSeconds());
        assertEquals("UTC", saved.versions().get(2).config().timezone());
        assertTrue(saved.versions().stream().allMatch(version -> "张".equals(version.config().unit())));
    }

    @Test
    public void failedDuplicateCleanupRollsBackUnitVersionRemovalAndSummariesTogether() {
        service.settings(tenant, device);
        configVersion(start + 1000, Config.defaults());
        count(0, 1000, false);
        count(2000, 1010, false);
        long revision = service.revision(device);
        jdbc.execute("CREATE FUNCTION reject_duplicate_cleanup() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN "
                + "RAISE EXCEPTION 'test duplicate cleanup failure'; END $$");
        jdbc.execute("CREATE TRIGGER reject_duplicate_cleanup BEFORE INSERT ON production_summary "
                + "FOR EACH ROW EXECUTE FUNCTION reject_duplicate_cleanup()");
        try {
            assertThrows(org.springframework.dao.DataAccessException.class, () -> service.saveSettings(tenant, device,
                    new SaveSettings(new Config("张", "Asia/Shanghai", 2, Config.defaults().shifts()), null)));
            assertEquals(2, service.settings(tenant, device).versions().size());
            assertEquals("贴", service.settings(tenant, device).current().unit());
            assertEquals(revision, service.revision(device));
            assertEquals(10, quantity(0, 2000));
        } finally {
            jdbc.execute("DROP TRIGGER reject_duplicate_cleanup ON production_summary");
            jdbc.execute("DROP FUNCTION reject_duplicate_cleanup()");
        }
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
