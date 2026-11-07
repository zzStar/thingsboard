// SPDX-FileCopyrightText: Copyright The ThingsBoard Authors
// SPDX-License-Identifier: Apache-2.0
package org.thingsboard.server.service.production;

import com.google.common.util.concurrent.Futures;
import org.junit.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.thingsboard.server.common.data.Device;
import org.thingsboard.server.common.data.id.DeviceId;
import org.thingsboard.server.common.data.id.TenantId;
import org.thingsboard.server.common.data.kv.BasicTsKvEntry;
import org.thingsboard.server.common.data.kv.LongDataEntry;
import org.thingsboard.server.dao.timeseries.TimeseriesService;

import java.time.*;
import java.time.temporal.TemporalAdjusters;
import java.util.*;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.thingsboard.server.service.production.ProductionBoardModels.*;
import static org.thingsboard.server.service.production.ProductionCalculator.*;
import static org.thingsboard.server.service.production.ProductionModels.*;

public class ProductionBoardQueryServiceTest {
    private static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:18");
    private static JdbcTemplate jdbc;
    private DeviceProductionService production;
    private ProductionBoardQueryService boards;
    private TimeseriesService timeseries;
    private TenantId tenant;
    private BoardConfig config;
    @BeforeClass public static void database() {
        PG.start();
        jdbc = new JdbcTemplate(new DriverManagerDataSource(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword()));
        jdbc.execute("CREATE TABLE customer(id uuid PRIMARY KEY,tenant_id uuid,title text)");
        jdbc.execute("CREATE TABLE device(id uuid PRIMARY KEY,tenant_id uuid,customer_id uuid,name text,label text)");
    }
    @AfterClass public static void stop() { PG.stop(); }
    @Before public void setup() {
        var ts = mock(TimeseriesService.class);
        timeseries = ts;
        when(ts.findLatest(any(), any(), eq("speed"))).thenReturn(Futures.immediateFuture(Optional.empty()));
        when(ts.findAll(any(), any(), anyList())).thenReturn(Futures.immediateFuture(List.of()));
        var tx = new DataSourceTransactionManager(jdbc.getDataSource());
        production = new DeviceProductionService(jdbc, tx, ts);
        production.initSchema();
        boards = new ProductionBoardQueryService(jdbc, tx, ts);
        org.springframework.test.util.ReflectionTestUtils.setField(boards, "maxTsIntervals", 350L);
        tenant = new TenantId(UUID.randomUUID());
        config = new ProductionBoardConfigParser().validate(new BoardConfig(1, "测试", new Source("ALL_VISIBLE", List.of(), List.of()),
                "productionType", List.of("折页机"), null));
    }
    private Member device(String type) {
        var device = new Device(new DeviceId(UUID.randomUUID()));
        device.setName("设备" + UUID.randomUUID()); device.setTenantId(tenant);
        jdbc.update("INSERT INTO device(id,tenant_id,name) VALUES (?,?,?)", device.getId().getId(), tenant.getId(), device.getName());
        return new Member(device, type, null, null);
    }
    private void count(Member d, long ts, long count) {
        production.record(tenant, d.device().getId(), List.of(new BasicTsKvEntry(ts, new LongDataEntry("totalCount", count))), false);
    }
    private Snapshot snapshot(List<Member> members) {
        return boards.snapshot(tenant, config, new Selection("widget", "ALL", null, null, 0), members, "config");
    }
    @Test public void currentPartialShiftMatchesExistingReportsAndNaturalElapsedHours() {
        Member member = device("折页机");
        long now = System.currentTimeMillis();
        long baseline = now - 4 * MINUTE - 1234;
        count(member, baseline, 100); count(member, baseline + 2 * MINUTE, 220);
        count(member, now - 2000, 3); // reset contributes 3, including partial minute allocation
        Snapshot result = snapshot(List.of(member));
        var row = result.frames().getFirst().devices().getFirst();
        var cfg = Config.defaults();
        var today = productionDate(result.computedAsOf(), cfg);
        long month = dayStart(today.withDayOfMonth(1), cfg);
        long expected = production.report(tenant, member.device().getId(), month, result.computedAsOf(), "day", null)
                .rows().stream().mapToLong(Row::quantity).sum();
        assertEquals(123L, expected);
        assertEquals(expected, row.totals().month().longValue());
        assertEquals(result.computedAsOf() - month, row.totals().elapsedMs());
        assertEquals(expected * 3600000.0 / row.totals().elapsedMs(), row.totals().averageSpeed(), 0.000001);
        assertEquals(expected, row.shifts().stream().filter(s -> s.month() != null).mapToLong(ShiftRow::month).sum());
    }
    @Test public void closedShiftsAndCrossMonthWeekMatchTheExistingProductionReport() {
        Member member = device("印刷机");
        var cfg = Config.defaults();
        var today = productionDate(System.currentTimeMillis(), cfg);
        long month = dayStart(today.withDayOfMonth(1), cfg);
        long week = dayStart(today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)), cfg);
        long start = Math.min(month, week);
        count(member, start + MINUTE, 100);
        count(member, System.currentTimeMillis() - 2000, 700); // long interval >24h allocation
        var result = snapshot(List.of(member));
        var row = result.frames().getFirst().devices().getFirst();
        for (var range : List.of(Map.entry(month, row.totals().month()), Map.entry(week, row.totals().week()),
                Map.entry(dayStart(today, cfg), row.totals().today()))) {
            long quantity = production.report(tenant, member.device().getId(), range.getKey(), result.computedAsOf(), "day", null)
                    .rows().stream().mapToLong(Row::quantity).sum();
            assertEquals(quantity, range.getValue().longValue());
        }
    }
    @Test public void unknownAndObservedZeroStayDifferentAndUnitsSplitCategories() {
        var unknown = device("折页机"); var zero = device("折页机"); var other = device("折页机");
        long now = System.currentTimeMillis();
        count(zero, now - 5000, 20); count(zero, now - 2000, 20);
        production.settings(tenant, other.device().getId());
        jdbc.update("UPDATE production_config SET config=jsonb_set(config,'{unit}','\"张\"') WHERE device_id=?", other.id());
        var result = snapshot(List.of(unknown, zero, other));
        assertEquals(2, result.types().size());
        var devices = result.frames().stream().flatMap(f -> f.devices().stream()).toList();
        assertNull(devices.stream().filter(d -> d.id().equals(unknown.id())).findFirst().orElseThrow().totals().today());
        assertEquals(Long.valueOf(0), devices.stream().filter(d -> d.id().equals(zero.id())).findFirst().orElseThrow().totals().today());
        assertEquals(3, result.deviceChoices().size());
    }
    @Test public void tenTypesRotateThreeThreeThreeOneAndDeviceOverflowIsFair() {
        List<TypeInfo> types = new ArrayList<>();
        for (int i = 0; i < 10; i++) types.add(new TypeInfo("t"+i,"类型"+i,"贴","Asia/Shanghai", i == 0 ? 15 : 1, 0, null));
        var plan = ProductionBoardQueryService.plan(types, config.display());
        assertEquals(List.of(3,3,3,1,3,3), plan.stream().map(p -> p.types().size()).toList());
        assertEquals(2, plan.getLast().types().getFirst().devicePage());
        assertEquals("t9", plan.get(3).types().getFirst().typeId());
    }
    @Test public void speedServiceFailureDoesNotDiscardDeviceProduction() {
        var member = device("折页机");
        long now = System.currentTimeMillis();
        count(member, now - 5000, 100); count(member, now - 2000, 130);
        when(timeseries.findAll(any(), any(), anyList())).thenThrow(new IllegalStateException("speed store unavailable"));
        var result = boards.snapshot(tenant, config, new Selection("widget", "DEVICE", null, member.id(), 0), List.of(member), "cfg");
        assertTrue(result.speedUnavailable());
        assertEquals(Long.valueOf(30), result.frames().getFirst().devices().getFirst().totals().today());
        assertTrue(result.speed().isEmpty());
    }
    @Test public void noDevicesNeedsNoDatabaseReadsAndDoesNotInventTotals() {
        var result = snapshot(List.of());
        assertTrue(result.types().isEmpty()); assertEquals(1, result.pageCount()); assertNull(result.dataAsOf());
    }
    @Test public void completeDeviceShiftRowsDefineDetailPageCapacity() {
        List<DeviceRow> rows = new ArrayList<>();
        for (int i=0;i<10;i++) rows.add(new DeviceRow(UUID.randomUUID(), "设备", "t", "贴", "Asia/Shanghai", "2026-11-06",
                null, Collections.nCopies(3, new ShiftRow("班次","08:00","16:00",null,null,null,null,0)), List.of(), null,null,null,10));
        assertEquals(List.of(4,4,2), ProductionBoardQueryService.detailPages(rows, 13).stream().map(List::size).toList());
    }
}
