// SPDX-FileCopyrightText: Copyright The ThingsBoard Authors
// SPDX-License-Identifier: Apache-2.0
package org.thingsboard.server.service.production;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.thingsboard.common.util.JacksonUtil;
import org.thingsboard.server.common.data.id.DeviceId;
import org.thingsboard.server.common.data.id.TenantId;
import org.thingsboard.server.common.data.kv.TsKvEntry;
import org.thingsboard.server.dao.timeseries.TimeseriesService;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.time.DayOfWeek;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.thingsboard.server.service.production.ProductionCalculator.*;
import static org.thingsboard.server.service.production.ProductionModels.*;

@Service
@Profile("!install")
@Slf4j
public class DeviceProductionService {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate write;
    private final TransactionTemplate read;
    private final TimeseriesService timeseries;

    public DeviceProductionService(JdbcTemplate jdbc, PlatformTransactionManager transactions, TimeseriesService timeseries) {
        this.jdbc = jdbc;
        this.timeseries = timeseries;
        this.write = new TransactionTemplate(transactions);
        this.read = new TransactionTemplate(transactions);
        this.read.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        this.read.setReadOnly(true);
    }

    @PostConstruct
    public void initSchema() {
        write.executeWithoutResult(tx -> {
            jdbc.execute("SELECT pg_advisory_xact_lock(7536891047216478432)");
            ScriptUtils.executeSqlScript(DataSourceUtils.getConnection(Objects.requireNonNull(jdbc.getDataSource())),
                    new ClassPathResource("production-schema.sql"));
        });
    }

    private void ensureDevice(TenantId tenant, DeviceId device) {
        jdbc.update("INSERT INTO production_device(device_id, tenant_id) VALUES (?, ?) ON CONFLICT (device_id) DO NOTHING",
                device.getId(), tenant.getId());
        jdbc.queryForObject("SELECT revision FROM production_device WHERE device_id=? FOR UPDATE", Long.class, device.getId());
        jdbc.update("UPDATE production_device SET tenant_id=? WHERE device_id=? AND tenant_id<>?", tenant.getId(), device.getId(), tenant.getId());
        jdbc.update("INSERT INTO production_config(device_id, effective_ts, config) VALUES (?, 0, ?::jsonb) ON CONFLICT DO NOTHING",
                device.getId(), JacksonUtil.toString(Config.defaults()));
    }

    public Settings settings(TenantId tenant, DeviceId device) {
        write.executeWithoutResult(tx -> ensureDevice(tenant, device));
        List<ConfigVersion> versions = versions(device.getId());
        long now = System.currentTimeMillis();
        return new Settings(versions, at(versions, now).config(), now);
    }

    public Settings saveSettings(TenantId tenant, DeviceId device, SaveSettings request) {
        if (request == null) throw new IllegalArgumentException("请提供产量配置");
        validate(request.config());
        LocalDate effective;
        try { effective = request.effectiveDate() == null || request.effectiveDate().isBlank() ? null : LocalDate.parse(request.effectiveDate()); }
        catch (java.time.DateTimeException e) { throw new IllegalArgumentException("无效的生效生产日期", e); }
        write.executeWithoutResult(tx -> {
            ensureDevice(tenant, device);
            List<ConfigVersion> history = versions(device.getId());
            long now = System.currentTimeMillis();
            Config current = at(history, now).config();
            ConfigVersion pending = history.stream().filter(version -> version.effectiveTs() > now).findFirst().orElse(null);
            Config target = pending == null ? current : pending.config();
            boolean scheduleChanged = !sameSchedule(request.config(), target)
                    || pending != null && effective != null && dayStart(effective, current) != pending.effectiveTs();
            if (scheduleChanged) {
                if (effective == null) throw new IllegalArgumentException("请提供班次生效生产日期");
                // Apply schedule changes at the old production-day boundary; keep quantity allocation intact.
                long effectiveTs = dayStart(effective, current);
                if (effectiveTs <= now) throw new IllegalArgumentException("班次配置须从未来生产日生效，历史统计保持原班次配置");
                if (effectiveTs > now + MAX_RANGE) throw new IllegalArgumentException("配置生效日期须在未来一年内");
                Long last = jdbc.queryForObject("SELECT MAX(ts) FROM production_sample WHERE device_id=?", Long.class, device.getId());
                if (last != null && last >= effectiveTs) throw new IllegalArgumentException("该日期已有产量记录，请选择更晚的生产日");
                jdbc.update("DELETE FROM production_config WHERE device_id=? AND effective_ts>?", device.getId(), now);
                jdbc.update("INSERT INTO production_config(device_id, effective_ts, config) VALUES (?, ?, ?::jsonb)",
                        device.getId(), effectiveTs, JacksonUtil.toString(request.config()));
            }
            // A device's unit is a display label, so rename it immediately without rebuilding any aggregates.
            jdbc.update("UPDATE production_config SET config=jsonb_set(config, '{unit}', to_jsonb(CAST(? AS text))) WHERE device_id=?",
                    request.config().unit(), device.getId());
        });
        return settings(tenant, device);
    }

    private List<ConfigVersion> versions(UUID device) {
        List<ConfigVersion> history = jdbc.query("SELECT effective_ts, config::text FROM production_config WHERE device_id=? ORDER BY effective_ts",
                (rs, n) -> new ConfigVersion(rs.getLong(1), JacksonUtil.fromString(rs.getString(2), Config.class)), device);
        if (history.isEmpty()) return history;
        // Also honor unit edits already saved in a pending version by older releases.
        String unit = history.getLast().config().unit();
        return history.stream().map(version -> {
            Config config = version.config();
            return new ConfigVersion(version.effectiveTs(), new Config(unit, config.timezone(), config.reportIntervalSeconds(), config.shifts()));
        }).toList();
    }

    private static boolean sameSchedule(Config first, Config second) {
        return first.timezone().equals(second.timezone()) && first.reportIntervalSeconds() == second.reportIntervalSeconds()
                && first.shifts().equals(second.shifts());
    }

    public void record(TenantId tenant, DeviceId device, List<TsKvEntry> entries, boolean overwrite) {
        TreeMap<Long, Long> counters = new TreeMap<>();
        for (TsKvEntry entry : entries) {
            if (!"totalCount".equals(entry.getKey())) continue;
            try {
                long value = new BigDecimal(entry.getValueAsString()).longValueExact();
                if (value < 0 || entry.getTs() < 0 || entry.getTs() > System.currentTimeMillis() + 300_000) continue;
                counters.put(entry.getTs(), value);
            } catch (NumberFormatException | ArithmeticException ignored) {
                log.debug("Ignored non-integer production counter for device {}", device);
            }
        }
        if (counters.isEmpty()) return;
        write.executeWithoutResult(tx -> {
            ensureDevice(tenant, device);
            List<ConfigVersion> history = versions(device.getId());
            for (var entry : counters.entrySet()) {
                Sample old = sample(device.getId(), entry.getKey());
                if (old != null && (!overwrite || old.counter() == entry.getValue())) continue;
                Sample previous = neighbor(device.getId(), entry.getKey(), true);
                Sample next = neighbor(device.getId(), entry.getKey(), false);
                if (old != null) contribute(device.getId(), old, history, -1);
                if (next != null) contribute(device.getId(), next, history, -1);
                Sample current = reading(entry.getKey(), entry.getValue(), previous, at(history, entry.getKey()).config().reportIntervalSeconds());
                persist(device.getId(), current);
                contribute(device.getId(), current, history, 1);
                if (next != null) {
                    Sample corrected = reading(next.ts(), next.counter(), current, at(history, next.ts()).config().reportIntervalSeconds());
                    persist(device.getId(), corrected);
                    contribute(device.getId(), corrected, history, 1);
                }
            }
            jdbc.update("UPDATE production_device SET revision=revision+1 WHERE device_id=?", device.getId());
        });
    }

    private Sample mapSample(ResultSet rs, int row) throws SQLException {
        return new Sample(rs.getLong("ts"), rs.getLong("counter"), (Long) rs.getObject("previous_ts"),
                rs.getLong("delta"), rs.getBoolean("reset"), rs.getBoolean("gap"));
    }

    private Sample sample(UUID device, long ts) {
        var result = jdbc.query("SELECT * FROM production_sample WHERE device_id=? AND ts=?", this::mapSample, device, ts);
        return result.isEmpty() ? null : result.getFirst();
    }

    private Sample neighbor(UUID device, long ts, boolean previous) {
        var result = jdbc.query("SELECT * FROM production_sample WHERE device_id=? AND ts " + (previous ? "<" : ">")
                        + " ? ORDER BY ts " + (previous ? "DESC" : "ASC") + " LIMIT 1", this::mapSample, device, ts);
        return result.isEmpty() ? null : result.getFirst();
    }

    private void persist(UUID device, Sample sample) {
        jdbc.update("INSERT INTO production_sample(device_id, ts, counter, previous_ts, delta, reset, gap) VALUES (?,?,?,?,?,?,?) "
                        + "ON CONFLICT (device_id, ts) DO UPDATE SET counter=EXCLUDED.counter, previous_ts=EXCLUDED.previous_ts, "
                        + "delta=EXCLUDED.delta, reset=EXCLUDED.reset, gap=EXCLUDED.gap",
                device, sample.ts(), sample.counter(), sample.previousTs(), sample.delta(), sample.reset(), sample.gap());
    }

    private void contribute(UUID device, Sample sample, List<ConfigVersion> history, int sign) {
        if (sample.previousTs() == null) return;
        long from = sample.previousTs(), until = sample.ts();
        // Very long outages remain in the 2-second/source facts. Do not create millions of empty minute buckets.
        if (until - from <= 24 * 60 * MINUTE) {
            List<Object[]> minutes = new ArrayList<>();
            for (long minute = Math.floorDiv(from, MINUTE) * MINUTE; minute < until; minute += MINUTE) {
                long duration = Math.min(until, minute + MINUTE) - Math.max(from, minute);
                minutes.add(new Object[]{device, minute, sign * allocate(sample, minute, minute + MINUTE),
                        sample.gap() ? 0 : sign * duration, sample.gap() ? sign * duration : 0,
                        sample.reset() && until > minute && until <= minute + MINUTE ? sign : 0});
            }
            jdbc.batchUpdate("INSERT INTO production_minute(device_id,minute_ts,quantity,observed_ms,estimated_ms,resets) VALUES (?,?,?,?,?,?) "
                    + "ON CONFLICT (device_id,minute_ts) DO UPDATE SET quantity=production_minute.quantity+EXCLUDED.quantity, "
                    + "observed_ms=production_minute.observed_ms+EXCLUDED.observed_ms, estimated_ms=production_minute.estimated_ms+EXCLUDED.estimated_ms, "
                    + "resets=production_minute.resets+EXCLUDED.resets", minutes);
        }
        List<Object[]> summaries = new ArrayList<>();
        List<Period> periods = new ArrayList<>(periods(history, from, until, false));
        periods.addAll(periods(history, from, until, true));
        for (Period period : periods) {
            long duration = Math.min(until, period.endTs()) - Math.max(from, period.startTs());
            summaries.add(new Object[]{device, period.configTs(), java.sql.Date.valueOf(period.productionDate()), period.shiftIndex(),
                    period.startTs(), period.endTs(), sign * allocate(sample, period.startTs(), period.endTs()),
                    sample.gap() ? 0 : sign * duration, sample.gap() ? sign * duration : 0,
                    sample.reset() && until > period.startTs() && until <= period.endTs() ? sign : 0});
        }
        jdbc.batchUpdate("INSERT INTO production_summary(device_id,config_ts,production_date,shift_index,start_ts,end_ts,quantity,observed_ms,estimated_ms,resets) "
                + "VALUES (?,?,?,?,?,?,?,?,?,?) ON CONFLICT (device_id,config_ts,production_date,shift_index) DO UPDATE SET "
                + "quantity=production_summary.quantity+EXCLUDED.quantity, observed_ms=production_summary.observed_ms+EXCLUDED.observed_ms, "
                + "estimated_ms=production_summary.estimated_ms+EXCLUDED.estimated_ms, resets=production_summary.resets+EXCLUDED.resets", summaries);
    }

    public Report report(TenantId tenant, DeviceId device, long start, long end, String groupBy, Integer shift) {
        if (start < 0 || end <= start || end - start > MAX_RANGE) throw new IllegalArgumentException("请选择不超过366天的有效日期时间范围");
        if (!List.of("day", "shift").contains(groupBy)) throw new IllegalArgumentException("统计维度须为day或shift");
        if (shift != null && (shift < 0 || shift > 2)) throw new IllegalArgumentException("无效的班次编号");
        settings(tenant, device);
        return read.execute(tx -> reportInternal(device.getId(), start, end, groupBy, shift));
    }

    private Report reportInternal(UUID device, long start, long end, String groupBy, Integer shift) {
        List<ConfigVersion> history = versions(device);
        long now = System.currentTimeMillis();
        long until = Math.min(end, now);
        List<Row> rows = new ArrayList<>();
        for (Period period : periods(history, start, end, "day".equals(groupBy) && shift == null)) {
            if (shift != null && period.shiftIndex() != shift) continue;
            long a = Math.max(start, period.startTs()), b = Math.min(until, period.endTs());
            long[] totals = b > a ? totals(device, period, a, b) : new long[4];
            boolean partial = a != period.startTs() || Math.min(end, period.endTs()) != period.endTs();
            long tolerance = Math.max(10_000, at(history, Math.max(a, 0)).config().reportIntervalSeconds() * 3000L);
            String status = b <= a ? "NOT_STARTED" : totals[1] + totals[2] == 0 ? "NO_DATA"
                    : totals[2] > 0 || totals[1] + tolerance < b - a ? "INCOMPLETE" : now < period.endTs() ? "IN_PROGRESS" : "COMPLETE";
            // Splitting a positive interval at a query/shift boundary requires an estimated allocation.
            if (b > a && boundaryEstimate(device, a, b) && !"INCOMPLETE".equals(status)) status = "ESTIMATED";
            rows.add(new Row(period.productionDate(), period.shiftIndex(), period.shiftName(), period.unit(), period.timezone(),
                    a, Math.max(a, Math.min(end, period.endTs())), totals[0], totals[1], totals[2], totals[3], partial, status));
        }
        Long last = jdbc.queryForObject("SELECT MAX(ts) FROM production_sample WHERE device_id=?", Long.class, device);
        return new Report(start, end, last == null ? 0 : last, groupBy, rows);
    }

    private long[] totals(UUID device, Period period, long start, long end) {
        if (start == period.startTs() && end == period.endTs()) {
            var stored = jdbc.query("SELECT quantity,observed_ms,estimated_ms,resets FROM production_summary "
                            + "WHERE device_id=? AND config_ts=? AND production_date=? AND shift_index=?",
                    (rs, n) -> new long[]{rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getLong(4)},
                    device, period.configTs(), java.sql.Date.valueOf(period.productionDate()), period.shiftIndex());
            return stored.isEmpty() ? new long[4] : stored.getFirst();
        }
        long firstFull = Math.floorDiv(start + MINUTE - 1, MINUTE) * MINUTE;
        long lastFull = Math.floorDiv(end, MINUTE) * MINUTE;
        long[] result = new long[4];
        if (firstFull < lastFull) {
            result = jdbc.queryForObject("SELECT COALESCE(SUM(quantity),0),COALESCE(SUM(observed_ms),0),"
                            + "COALESCE(SUM(estimated_ms),0),COALESCE(SUM(resets),0) FROM production_minute "
                            + "WHERE device_id=? AND minute_ts>=? AND minute_ts<?",
                    (rs, n) -> new long[]{rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getLong(4)}, device, firstFull, lastFull);
        }
        for (Sample sample : jdbc.query("SELECT * FROM production_sample WHERE device_id=? AND ts>? AND previous_ts<? "
                        + "AND (ts-previous_ts>? OR previous_ts<? OR ts>?)", this::mapSample,
                device, start, end, 24 * 60 * MINUTE, firstFull, lastFull)) {
            if (sample.ts() - sample.previousTs() > 24 * 60 * MINUTE || firstFull >= lastFull) {
                add(result, sample, start, end);
            } else {
                add(result, sample, start, Math.min(end, firstFull));
                add(result, sample, Math.max(start, lastFull), end);
            }
        }
        return result;
    }

    private static void add(long[] result, Sample sample, long start, long end) {
        long duration = Math.min(sample.ts(), end) - Math.max(sample.previousTs(), start);
        if (duration <= 0) return;
        result[0] += allocate(sample, start, end);
        result[sample.gap() ? 2 : 1] += duration;
        if (sample.reset() && sample.ts() > start && sample.ts() <= end) result[3]++;
    }

    private boolean boundaryEstimate(UUID device, long start, long end) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM production_sample WHERE device_id=? AND delta>0 "
                + "AND ((previous_ts<? AND ts>?) OR (previous_ts<? AND ts>?)))", Boolean.class, device, start, start, end, end));
    }

    public Overview overview(TenantId tenant, DeviceId device) {
        settings(tenant, device);
        return read.execute(tx -> {
            long now = System.currentTimeMillis();
            List<ConfigVersion> history = versions(device.getId());
            Config config = at(history, now).config();
            LocalDate date = productionDate(now, config);
            var dayPeriods = periods(history, dayStart(date.minusDays(1), config), dayStart(date.plusDays(2), config), true)
                    .stream().filter(period -> period.productionDate().equals(date.toString())).toList();
            long start = dayPeriods.stream().mapToLong(Period::startTs).min().orElse(dayStart(date, config));
            long end = dayPeriods.stream().mapToLong(Period::endTs).max().orElse(dayStart(date.plusDays(1), config));
            Report today = reportInternal(device.getId(), start, now, "day", null);
            Report shifts = reportInternal(device.getId(), start, end, "shift", null);
            LocalDate weekStart = date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
            LocalDate monthStart = date.withDayOfMonth(1);
            long week = sealedDays(device.getId(), weekStart, date) + sum(today);
            long month = sealedDays(device.getId(), monthStart, date) + sum(today);
            Long first = jdbc.queryForObject("SELECT MIN(ts) FROM production_sample WHERE device_id=?", Long.class, device.getId());
            Long last = jdbc.queryForObject("SELECT MAX(ts) FROM production_sample WHERE device_id=?", Long.class, device.getId());
            Double speed = null;
            Long speedTs = null;
            try {
                var latest = timeseries.findLatest(tenant, device, "speed").get(5, TimeUnit.SECONDS);
                if (latest.isPresent()) {
                    speed = Double.parseDouble(latest.get().getValueAsString());
                    if (!Double.isFinite(speed) || speed < 0) speed = null;
                    speedTs = latest.get().getTs();
                }
            } catch (Exception e) {
                if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                log.debug("Unable to read production speed for device {}", device, e);
            }
            String status = today.rows().isEmpty() ? "NO_DATA" : today.rows().getFirst().status();
            return new Overview(config, date.toString(), start, end, sum(today), week, month, shifts.rows(), first, last, speed, speedTs, status, now);
        });
    }

    private static long sum(Report report) {
        return report.rows().stream().mapToLong(Row::quantity).sum();
    }

    private long sealedDays(UUID device, LocalDate start, LocalDate end) {
        return jdbc.queryForObject("SELECT COALESCE(SUM(quantity),0) FROM production_summary "
                + "WHERE device_id=? AND shift_index=-1 AND production_date>=? AND production_date<?", Long.class,
                device, java.sql.Date.valueOf(start), java.sql.Date.valueOf(end));
    }
}
