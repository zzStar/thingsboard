// SPDX-FileCopyrightText: Copyright The ThingsBoard Authors
// SPDX-License-Identifier: Apache-2.0
package org.thingsboard.server.service.production;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.context.annotation.DependsOn;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.thingsboard.common.util.JacksonUtil;
import org.thingsboard.server.common.data.id.DeviceId;
import org.thingsboard.server.common.data.id.TenantId;
import org.thingsboard.server.dao.timeseries.TimeseriesService;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAdjusters;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.thingsboard.server.service.production.ProductionBoardAccessService.displayName;
import static org.thingsboard.server.service.production.ProductionBoardModels.*;
import static org.thingsboard.server.service.production.ProductionCalculator.*;
import static org.thingsboard.server.service.production.ProductionModels.*;

/** All queries are bounded by the already-authorized device set; never resolves permissions itself. */
@Service
@Profile("!install")
@DependsOn("deviceProductionService")
public class ProductionBoardQueryService {
    private final NamedParameterJdbcTemplate sql;
    private final TransactionTemplate read;
    private final TimeseriesService timeseries;
    @Value("${database.ts_max_intervals:700}")
    private long maxTsIntervals;

    public ProductionBoardQueryService(JdbcTemplate jdbc, PlatformTransactionManager transactions, TimeseriesService timeseries) {
        this.sql = new NamedParameterJdbcTemplate(jdbc);
        this.timeseries = timeseries;
        read = new TransactionTemplate(transactions);
        read.setReadOnly(true);
        read.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }

    public Snapshot snapshot(TenantId tenant, BoardConfig config, Selection selection, List<Member> members,
                             String configRevision) {
        long now = System.currentTimeMillis();
        List<DeviceRow> rows = members.isEmpty() ? List.of() : read.execute(tx -> project(tenant, members, now));
        String scopeRevision = fingerprint(members.stream().map(m -> List.of(m.id(), m.owner(), m.productionType())).toList());
        Map<String, List<DeviceRow>> buckets = rows.stream().collect(Collectors.groupingBy(DeviceRow::typeId));
        Map<UUID, Member> memberById = members.stream().collect(Collectors.toMap(Member::id, Function.identity()));
        List<TypeInfo> types = buckets.entrySet().stream().map(entry -> {
            var ds = entry.getValue();
            DeviceRow first = ds.getFirst();
            return new TypeInfo(entry.getKey(), memberById.get(first.id()).productionType(), first.unit(), first.timezone(),
                    ds.size(), (int) ds.stream().filter(d -> d.totals().today() == null).count(), aggregate(ds));
        }).sorted(Comparator.comparingInt((TypeInfo t) -> {
            int index = config.typeOrder().indexOf(t.name());
            return index < 0 ? Integer.MAX_VALUE : index;
        }).thenComparing(TypeInfo::name).thenComparing(TypeInfo::id)).toList();
        List<DeviceChoice> choices = rows.stream().map(d -> new DeviceChoice(d.id(), d.name(), d.typeId())).toList();
        String view = selection == null || selection.view() == null ? "ALL" : selection.view();
        if (!Set.of("ALL", "TYPE", "DEVICE").contains(view)) throw new IllegalArgumentException("无效的产量视图");
        int requestedPage = selection == null || selection.page() == null ? 0 : selection.page();
        if (requestedPage < 0 || requestedPage > 10000) throw new IllegalArgumentException("无效的页码");
        String selectedType = selection == null ? null : selection.typeId();
        UUID selectedDevice = selection == null ? null : selection.deviceId();
        List<FramePlan> plan;
        List<TypeFrame> frames = new ArrayList<>();
        int page;
        int pageCount;
        List<SpeedPoint> speed = List.of();
        boolean speedUnavailable = false;
        if ("ALL".equals(view)) {
            plan = plan(types, config.display());
            pageCount = Math.max(1, plan.size());
            page = requestedPage < pageCount ? requestedPage : 0;
            if (!plan.isEmpty()) for (FrameSpec spec : plan.get(page).types()) {
                TypeInfo info = types.stream().filter(t -> t.id().equals(spec.typeId())).findFirst().orElseThrow();
                List<DeviceRow> all = buckets.get(info.id());
                int capacity = config.display().devicesPerCard();
                frames.add(new TypeFrame(info, slice(all, spec.devicePage(), capacity), List.of(), trend(all),
                        spec.devicePage(), pages(all.size(), capacity)));
            }
            selectedType = null;
            selectedDevice = null;
        } else {
            if ("DEVICE".equals(view)) {
                UUID choice = selectedDevice;
                DeviceRow device = rows.stream().filter(d -> d.id().equals(choice)).findFirst()
                        .orElseThrow(() -> new IllegalArgumentException("所选设备不在当前有效范围内"));
                selectedType = device.typeId();
            }
            String typeId = selectedType;
            TypeInfo info = types.stream().filter(t -> t.id().equals(typeId)).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("所选类型不在当前有效范围内"));
            List<DeviceRow> all = "DEVICE".equals(view)
                    ? rows.stream().filter(d -> d.id().equals(selection.deviceId())).toList() : buckets.get(typeId);
            List<ShiftRow> totals = "DEVICE".equals(view) ? List.of() : shiftTotals(all);
            List<List<DeviceRow>> detailPages = detailPages(all, config.display().detailRowBudget() - totals.size());
            pageCount = Math.max(1, detailPages.size());
            page = requestedPage < pageCount ? requestedPage : 0;
            frames.add(new TypeFrame("DEVICE".equals(view)
                    ? new TypeInfo(info.id(), info.name(), info.unit(), info.timezone(), 1,
                    all.getFirst().totals().today() == null ? 1 : 0, aggregate(all)) : info,
                    detailPages.get(page), totals, trend(all), page, pageCount));
            plan = List.of();
            if ("DEVICE".equals(view)) {
                var row = all.getFirst();
                java.util.concurrent.Future<List<org.thingsboard.server.common.data.kv.TsKvEntry>> query = null;
                try {
                    query = timeseries.findAll(tenant, new DeviceId(row.id()), List.of(
                            ProductionSpeedQuery.create(now - 3_600_000, now, maxTsIntervals)));
                    speed = query.get(5, TimeUnit.SECONDS).stream().map(p -> new SpeedPoint(p.getTs(), p.getValueAsString())).toList();
                } catch (Exception e) {
                    if (query != null) query.cancel(true);
                    if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                    speedUnavailable = true;
                }
            }
        }
        Long dataAsOf = rows.stream().map(DeviceRow::lastSampleTs).filter(Objects::nonNull).max(Long::compare).orElse(null);
        return new Snapshot(config, now, dataAsOf, configRevision, scopeRevision,
                view, selectedType, selectedDevice, page, pageCount, types, choices, plan, frames, speed, speedUnavailable);
    }

    public static List<FramePlan> plan(List<TypeInfo> types, Display display) {
        List<FramePlan> result = new ArrayList<>();
        int rounds = types.stream().mapToInt(t -> pages(t.deviceCount(), display.devicesPerCard())).max().orElse(0);
        for (int round = 0; round < rounds; round++) {
            for (int start = 0; start < types.size(); start += display.typesPerFrame()) {
                List<TypeInfo> group = types.subList(start, Math.min(start + display.typesPerFrame(), types.size()));
                if (group.stream().mapToInt(t -> pages(t.deviceCount(), display.devicesPerCard())).max().orElse(0) <= round) continue;
                int index = round;
                result.add(new FramePlan(group.stream().map(t -> new FrameSpec(t.id(),
                        index % pages(t.deviceCount(), display.devicesPerCard()))).toList()));
            }
        }
        return result;
    }

    public static List<List<DeviceRow>> detailPages(List<DeviceRow> devices, int budget) {
        List<List<DeviceRow>> pages = new ArrayList<>();
        List<DeviceRow> current = new ArrayList<>();
        int used = 0;
        for (DeviceRow row : devices) {
            int size = Math.max(1, row.shifts().size());
            if (size > budget) throw new IllegalArgumentException("设备历史班次配置过多，请在设备历史页面查看");
            if (used + size > budget && !current.isEmpty()) { pages.add(List.copyOf(current)); current.clear(); used = 0; }
            current.add(row);
            used += size;
        }
        if (!current.isEmpty()) pages.add(List.copyOf(current));
        return pages;
    }

    private static int pages(int size, int capacity) { return Math.max(1, (size + capacity - 1) / capacity); }
    private static <T> List<T> slice(List<T> list, int page, int capacity) {
        return List.copyOf(list.subList(page * capacity, Math.min((page + 1) * capacity, list.size())));
    }
    public static String fingerprint(Object value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(JacksonUtil.toString(value).getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    private List<DeviceRow> project(TenantId tenant, List<Member> members, long now) {
        List<UUID> ids = members.stream().map(Member::id).toList();
        Map<String, Object> parameters = new HashMap<>(Map.of("ids", ids, "tenant", tenant.getId(), "now", now));
        Map<UUID, List<ConfigVersion>> configs = new HashMap<>();
        sql.query("SELECT c.device_id,c.effective_ts,c.config::text FROM production_config c JOIN device d ON d.id=c.device_id "
                + "WHERE c.device_id IN (:ids) AND d.tenant_id=:tenant ORDER BY c.device_id,c.effective_ts", parameters, rs -> {
            UUID id = rs.getObject(1, UUID.class);
            configs.computeIfAbsent(id, key -> new ArrayList<>()).add(new ConfigVersion(rs.getLong(2),
                    JacksonUtil.fromString(rs.getString(3), Config.class)));
        });
        Map<UUID, Bounds> bounds = new LinkedHashMap<>();
        LocalDate earliest = null;
        for (Member member : members) {
            var history = configs.getOrDefault(member.id(), List.of(new ConfigVersion(0, Config.defaults())));
            String unit = history.getLast().config().unit();
            history = history.stream().map(v -> new ConfigVersion(v.effectiveTs(), new Config(unit, v.config().timezone(),
                    v.config().reportIntervalSeconds(), v.config().shifts()))).toList();
            Config config = at(history, now).config();
            LocalDate today = productionDate(now, config);
            LocalDate month = today.withDayOfMonth(1), week = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
            LocalDate start = month.isBefore(week) ? month : week;
            earliest = earliest == null || start.isBefore(earliest) ? start : earliest;
            List<Period> periods = periods(history, dayStart(start.minusDays(2), config), dayStart(today.plusDays(2), config), false)
                    .stream().filter(p -> !LocalDate.parse(p.productionDate()).isBefore(start)
                            && !LocalDate.parse(p.productionDate()).isAfter(today)).toList();
            bounds.put(member.id(), new Bounds(config, today, week, month, periods));
        }
        parameters.put("start", java.sql.Date.valueOf(earliest));
        Map<String, Values> values = new HashMap<>();
        sql.query("SELECT s.device_id,s.config_ts,s.production_date,s.shift_index,s.quantity,s.observed_ms+s.estimated_ms "
                        + "FROM production_summary s JOIN device d ON d.id=s.device_id WHERE s.device_id IN (:ids) "
                        + "AND d.tenant_id=:tenant AND s.production_date>=:start AND s.shift_index>=0 AND s.end_ts<=:now",
                parameters, rs -> { values.put(key(rs.getObject(1, UUID.class), rs.getLong(2), rs.getString(3), rs.getInt(4)),
                        new Values(rs.getLong(5), rs.getLong(6))); });
        List<Request> partial = new ArrayList<>();
        bounds.forEach((id, b) -> b.periods().stream().filter(p -> p.startTs() < now && p.endTs() > now)
                .forEach(p -> partial.add(new Request(id, p, now))));
        if (!partial.isEmpty()) loadPartial(partial, values, tenant);
        Map<UUID, Long[]> sampleTimes = new HashMap<>();
        sql.query("SELECT d.id,(SELECT MIN(s.ts) FROM production_sample s WHERE s.device_id=d.id AND s.ts<=:now),"
                        + "(SELECT MAX(s.ts) FROM production_sample s WHERE s.device_id=d.id AND s.ts<=:now) "
                        + "FROM device d WHERE d.id IN (:ids) AND d.tenant_id=:tenant", parameters,
                rs -> { sampleTimes.put(rs.getObject(1, UUID.class), new Long[]{(Long) rs.getObject(2), (Long) rs.getObject(3)}); });
        List<DeviceRow> result = new ArrayList<>();
        for (Member member : members) {
            Bounds b = bounds.get(member.id());
            Long[] times = sampleTimes.getOrDefault(member.id(), new Long[2]);
            Map<String, Accumulator> shifts = new LinkedHashMap<>();
            Map<String, Accumulator> days = new TreeMap<>();
            Accumulator total = new Accumulator();
            for (Period p : b.periods()) {
                long elapsed = Math.max(0, Math.min(p.endTs(), now) - p.startTs());
                Values value = values.getOrDefault(key(member.id(), p), new Values(0, 0));
                boolean known = value.coverage > 0 || value.quantity > 0
                        || times[0] != null && times[0] >= p.startTs() && times[0] < Math.min(p.endTs(), now + 1);
                LocalDate date = LocalDate.parse(p.productionDate());
                String start = clock(p.startTs(), p.timezone()), end = clock(p.endTs(), p.timezone());
                String shiftKey = p.shiftName() + "\0" + start + "\0" + end;
                Accumulator shift = shifts.computeIfAbsent(shiftKey, k -> new Accumulator(p.shiftName(), start, end));
                total.add(date, b, value.quantity, elapsed, known);
                shift.add(date, b, value.quantity, elapsed, known);
                if (!date.isBefore(b.month())) days.computeIfAbsent(p.productionDate(), k -> new Accumulator())
                        .add(date, b, value.quantity, elapsed, known);
            }
            List<ShiftRow> shiftRows = shifts.values().stream().map(Accumulator::shift).toList();
            String typeId = fingerprint(List.of(member.productionType(), b.config().unit(), b.config().timezone()));
            result.add(new DeviceRow(member.id(), displayName(member.device()), typeId, b.config().unit(), b.config().timezone(),
                    b.today().toString(), total.totals(), shiftRows, days.entrySet().stream()
                    .map(entry -> new DayPoint(entry.getKey(), entry.getValue().totals().month())).toList(),
                    times[1], member.speed(), member.speedTs(), Math.max(10, b.config().reportIntervalSeconds() * 3)));
        }
        return result;
    }

    private void loadPartial(List<Request> requests, Map<String, Values> values, TenantId tenant) {
        Map<String, Object> params = new HashMap<>(Map.of("tenant", tenant.getId()));
        List<String> tuples = new ArrayList<>();
        for (int i = 0; i < requests.size(); i++) {
            Request r = requests.get(i);
            tuples.add("(:d" + i + "::uuid,:a" + i + "::bigint,:b" + i + "::bigint,:f" + i + "::bigint,:l" + i + "::bigint)");
            params.put("d" + i, r.device()); params.put("a" + i, r.period().startTs()); params.put("b" + i, r.end());
            params.put("f" + i, r.firstFull()); params.put("l" + i, r.lastFull());
        }
        String cte = "WITH r(device_id,a,b,f,l) AS (VALUES " + String.join(",", tuples) + ") ";
        Map<UUID, Request> byDevice = requests.stream().collect(Collectors.toMap(Request::device, Function.identity()));
        sql.query(cte + "SELECT r.device_id,COALESCE(SUM(m.quantity),0),COALESCE(SUM(m.observed_ms+m.estimated_ms),0) "
                + "FROM r JOIN device d ON d.id=r.device_id AND d.tenant_id=:tenant LEFT JOIN production_minute m "
                + "ON m.device_id=r.device_id AND m.minute_ts>=r.f AND m.minute_ts<r.l GROUP BY r.device_id", params, rs -> {
            UUID id = rs.getObject(1, UUID.class);
            values.put(key(id, byDevice.get(id).period()), new Values(rs.getLong(2), rs.getLong(3)));
        });
        sql.query(cte + "SELECT s.* FROM r JOIN device d ON d.id=r.device_id AND d.tenant_id=:tenant "
                + "JOIN production_sample s ON s.device_id=r.device_id AND s.ts>r.a AND s.previous_ts<r.b "
                + "AND (s.ts-s.previous_ts>86400000 OR s.previous_ts<r.f OR s.ts>r.l)", params, rs -> {
            UUID id = rs.getObject("device_id", UUID.class);
            Request r = byDevice.get(id);
            Sample sample = new Sample(rs.getLong("ts"), rs.getLong("counter"), (Long) rs.getObject("previous_ts"),
                    rs.getLong("delta"), rs.getBoolean("reset"), rs.getBoolean("gap"));
            Values value = values.computeIfAbsent(key(id, r.period()), k -> new Values(0, 0));
            if (sample.ts() - sample.previousTs() > 86400000 || r.firstFull() >= r.lastFull()) {
                value.add(sample, r.period().startTs(), r.end());
            } else {
                value.add(sample, r.period().startTs(), Math.min(r.end(), r.firstFull()));
                value.add(sample, Math.max(r.period().startTs(), r.lastFull()), r.end());
            }
        });
    }

    private static String key(UUID id, Period p) { return key(id, p.configTs(), p.productionDate(), p.shiftIndex()); }
    private static String key(UUID id, long config, String date, int shift) { return id + ":" + config + ":" + date + ":" + shift; }
    private static String clock(long ts, String zone) { return Instant.ofEpochMilli(ts).atZone(ZoneId.of(zone)).format(DateTimeFormatter.ofPattern("HH:mm")); }
    private record Bounds(Config config, LocalDate today, LocalDate week, LocalDate month, List<Period> periods) {}
    private record Request(UUID device, Period period, long end) {
        long firstFull() { return Math.floorDiv(period.startTs() + MINUTE - 1, MINUTE) * MINUTE; }
        long lastFull() { return Math.floorDiv(end, MINUTE) * MINUTE; }
    }
    private static class Values {
        long quantity, coverage;
        Values(long quantity, long coverage) { this.quantity = quantity; this.coverage = coverage; }
        void add(Sample sample, long from, long until) {
            long duration = Math.min(sample.ts(), until) - Math.max(sample.previousTs(), from);
            if (duration > 0) { quantity += allocate(sample, from, until); coverage += duration; }
        }
    }
    private static class Accumulator {
        long today, week, month, elapsed;
        boolean todayKnown, weekKnown, monthKnown;
        String name, start, end;
        Accumulator() {}
        Accumulator(String name, String start, String end) { this.name = name; this.start = start; this.end = end; }
        void add(LocalDate date, Bounds b, long quantity, long ms, boolean known) {
            if (date.equals(b.today())) { today += quantity; todayKnown |= known; }
            if (!date.isBefore(b.week())) { week += quantity; weekKnown |= known; }
            if (!date.isBefore(b.month())) { month += quantity; monthKnown |= known; elapsed += ms; }
        }
        Totals totals() { return new Totals(todayKnown ? today : null, weekKnown ? week : null, monthKnown ? month : null,
                monthKnown && elapsed > 0 ? month * 3_600_000.0 / elapsed : null, elapsed); }
        ShiftRow shift() { Totals t = totals(); return new ShiftRow(name, start, end, t.today(), t.week(), t.month(), t.averageSpeed(), elapsed); }
    }
    private static Long nullableSum(List<Long> values) {
        return values.stream().allMatch(Objects::isNull) ? null : values.stream().filter(Objects::nonNull).mapToLong(Long::longValue).sum();
    }
    public static Totals aggregate(List<DeviceRow> rows) {
        Long month = nullableSum(rows.stream().map(r -> r.totals().month()).toList());
        long elapsed = rows.stream().filter(r -> r.totals().month() != null).mapToLong(r -> r.totals().elapsedMs()).sum();
        return new Totals(nullableSum(rows.stream().map(r -> r.totals().today()).toList()),
                nullableSum(rows.stream().map(r -> r.totals().week()).toList()), month,
                month != null && elapsed > 0 ? month * 3_600_000.0 / elapsed : null, elapsed);
    }
    private static List<DayPoint> trend(List<DeviceRow> devices) {
        Map<String, List<Long>> days = new TreeMap<>();
        for (DeviceRow device : devices) for (DayPoint point : device.trend()) {
            days.computeIfAbsent(point.date(), date -> new ArrayList<>()).add(point.quantity());
        }
        return days.entrySet().stream().map(e -> new DayPoint(e.getKey(), nullableSum(e.getValue()))).toList();
    }
    private static List<ShiftRow> shiftTotals(List<DeviceRow> rows) {
        var first = rows.getFirst().shifts();
        if (rows.stream().anyMatch(row -> row.shifts().size() != first.size())) return List.of();
        for (int i = 0; i < first.size(); i++) {
            int at = i;
            ShiftRow ref = first.get(i);
            if (rows.stream().anyMatch(row -> !List.of(row.shifts().get(at).name(), row.shifts().get(at).start(), row.shifts().get(at).end())
                    .equals(List.of(ref.name(), ref.start(), ref.end())))) return List.of();
        }
        List<ShiftRow> result = new ArrayList<>();
        for (int i = 0; i < first.size(); i++) {
            int at = i;
            List<ShiftRow> shifts = rows.stream().map(r -> r.shifts().get(at)).toList();
            Long month = nullableSum(shifts.stream().map(ShiftRow::month).toList());
            long elapsed = shifts.stream().filter(s -> s.month() != null).mapToLong(ShiftRow::elapsedMs).sum();
            ShiftRow ref = first.get(i);
            result.add(new ShiftRow(ref.name(), ref.start(), ref.end(), nullableSum(shifts.stream().map(ShiftRow::today).toList()),
                    nullableSum(shifts.stream().map(ShiftRow::week).toList()), month,
                    month != null && elapsed > 0 ? month * 3_600_000.0 / elapsed : null, elapsed));
        }
        return result;
    }
}
