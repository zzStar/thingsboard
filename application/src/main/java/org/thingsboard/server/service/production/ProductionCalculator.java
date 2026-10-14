// SPDX-FileCopyrightText: Copyright The ThingsBoard Authors
// SPDX-License-Identifier: Apache-2.0
package org.thingsboard.server.service.production;

import java.math.BigInteger;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

import static org.thingsboard.server.service.production.ProductionModels.*;

public final class ProductionCalculator {
    public static final long MINUTE = 60_000;
    public static final long MAX_RANGE = 366L * 24 * 60 * MINUTE;
    private ProductionCalculator() {}

    public static void validate(Config config) {
        if (config == null || config.unit() == null || config.unit().isBlank() || config.unit().length() > 16) {
            throw new IllegalArgumentException("产量单位不能为空，且最多16个字符");
        }
        if (config.timezone() == null) throw new IllegalArgumentException("时区不能为空");
        ZoneId zone;
        try { zone = ZoneId.of(config.timezone()); }
        catch (java.time.DateTimeException e) { throw new IllegalArgumentException("无效的统计时区", e); }
        if (!zone.getRules().isFixedOffset() && !"Asia/Shanghai".equals(zone.getId())) {
            throw new IllegalArgumentException("当前支持上海时区或固定时差时区");
        }
        if (config.reportIntervalSeconds() < 1 || config.reportIntervalSeconds() > 3600) {
            throw new IllegalArgumentException("上报间隔必须在1至3600秒之间");
        }
        if (config.shifts() == null || config.shifts().size() < 2 || config.shifts().size() > 3) {
            throw new IllegalArgumentException("请选择两班制或三班制");
        }
        var names = new HashSet<String>();
        int previousOffset = -1;
        if (config.shifts().stream().anyMatch(java.util.Objects::isNull)) throw new IllegalArgumentException("班次不能为空");
        int origin = minuteOfDay(config.shifts().getFirst().start());
        for (Shift shift : config.shifts()) {
            if (shift.name() == null || shift.name().isBlank() || shift.name().length() > 24 || !names.add(shift.name())) {
                throw new IllegalArgumentException("班次名称不能为空、不能重复，且最多24个字符");
            }
            int offset = Math.floorMod(minuteOfDay(shift.start()) - origin, 1440);
            if (offset <= previousOffset) {
                throw new IllegalArgumentException("班次开始时间须按生产日顺序排列且不能重复");
            }
            previousOffset = offset;
        }
    }

    private static int minuteOfDay(String value) {
        if (value == null || !value.matches("\\d{2}:\\d{2}")) throw new IllegalArgumentException("班次时间格式须为HH:mm");
        LocalTime time;
        try { time = LocalTime.parse(value); }
        catch (java.time.DateTimeException e) { throw new IllegalArgumentException("无效的班次时间", e); }
        return time.getHour() * 60 + time.getMinute();
    }

    public static LocalDate productionDate(long ts, Config config) {
        var time = Instant.ofEpochMilli(ts).atZone(ZoneId.of(config.timezone()));
        return time.toLocalTime().isBefore(LocalTime.parse(config.shifts().getFirst().start()))
                ? time.toLocalDate().minusDays(1) : time.toLocalDate();
    }

    public static long dayStart(LocalDate date, Config config) {
        return date.atTime(LocalTime.parse(config.shifts().getFirst().start()))
                .atZone(ZoneId.of(config.timezone())).toInstant().toEpochMilli();
    }

    public static ConfigVersion at(List<ConfigVersion> versions, long ts) {
        ConfigVersion result = versions.getFirst();
        for (ConfigVersion version : versions) {
            if (version.effectiveTs() > ts) break;
            result = version;
        }
        return result;
    }

    public static List<Period> periods(List<ConfigVersion> versions, long start, long end, boolean daily) {
        List<Period> result = new ArrayList<>();
        for (int v = 0; v < versions.size(); v++) {
            var version = versions.get(v);
            long from = Math.max(start, version.effectiveTs());
            long until = Math.min(end, v + 1 < versions.size() ? versions.get(v + 1).effectiveTs() : Long.MAX_VALUE);
            if (from >= until) continue;
            Config config = version.config();
            LocalDate date = productionDate(from, config);
            LocalDate last = productionDate(until - 1, config);
            while (!date.isAfter(last)) {
                long dayStart = dayStart(date, config), dayEnd = dayStart(date.plusDays(1), config);
                int count = daily ? 1 : config.shifts().size();
                for (int i = 0; i < count; i++) {
                    int origin = minuteOfDay(config.shifts().getFirst().start());
                    long a = daily ? dayStart : dayStart + Math.floorMod(minuteOfDay(config.shifts().get(i).start()) - origin, 1440) * MINUTE;
                    long b = daily || i + 1 == count ? dayEnd
                            : dayStart + Math.floorMod(minuteOfDay(config.shifts().get(i + 1).start()) - origin, 1440) * MINUTE;
                    a = Math.max(a, version.effectiveTs());
                    b = Math.min(b, v + 1 < versions.size() ? versions.get(v + 1).effectiveTs() : Long.MAX_VALUE);
                    if (a < end && b > start && a < b) {
                        result.add(new Period(version.effectiveTs(), date.toString(), daily ? -1 : i,
                                daily ? "全天" : config.shifts().get(i).name(), config.unit(), config.timezone(), a, b));
                    }
                }
                date = date.plusDays(1);
            }
        }
        return result;
    }

    public static Sample reading(long ts, long value, Sample previous, int intervalSeconds) {
        if (value < 0 || ts < 0) throw new IllegalArgumentException("计数和时间不能为负数");
        if (previous == null) return new Sample(ts, value, null, 0, false, false);
        if (ts <= previous.ts()) throw new IllegalArgumentException("读数时间须晚于前一读数");
        boolean reset = value < previous.counter();
        return new Sample(ts, value, previous.ts(), reset ? value : value - previous.counter(),
                reset, ts - previous.ts() > Math.max(10_000, intervalSeconds * 3000L));
    }

    // Cumulative integer allocation conserves every unit across adjacent range/shift/minute boundaries.
    public static long allocate(Sample sample, long from, long to) {
        if (sample.previousTs() == null || from >= to) return 0;
        long a = Math.max(from, sample.previousTs()), b = Math.min(to, sample.ts());
        if (a >= b) return 0;
        BigInteger delta = BigInteger.valueOf(sample.delta());
        BigInteger duration = BigInteger.valueOf(sample.ts() - sample.previousTs());
        return delta.multiply(BigInteger.valueOf(b - sample.previousTs())).divide(duration)
                .subtract(delta.multiply(BigInteger.valueOf(a - sample.previousTs())).divide(duration)).longValueExact();
    }
}
