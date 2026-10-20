// SPDX-FileCopyrightText: Copyright The ThingsBoard Authors
// SPDX-License-Identifier: Apache-2.0
package org.thingsboard.server.service.production;

import java.util.List;

public final class ProductionModels {
    private ProductionModels() {}

    public record Shift(String name, String start) {}

    public record Config(String unit, String timezone, int reportIntervalSeconds, List<Shift> shifts) {
        public static Config defaults() {
            return new Config("贴", "Asia/Shanghai", 2,
                    List.of(new Shift("白班", "07:00"), new Shift("夜班", "19:00")));
        }
    }

    public record ConfigVersion(long effectiveTs, Config config) {}
    public record Settings(List<ConfigVersion> versions, Config current, long now) {}
    public record SaveSettings(Config config, String effectiveDate) {}
    public record BackfillRequest(long startTs, long endTs) {}
    public record Backfill(long startTs, long endTs, long cursorTs, long processed, String status, String error) {}
    public record ScreenLink(String key, long createdTs) {}
    public record SpeedPoint(long ts, String value) {}
    public record ScreenView(String deviceName, String customerName, Overview overview, List<SpeedPoint> speed, Report output, boolean speedUnavailable) {}
    public record Sample(long ts, long counter, Long previousTs, long delta, boolean reset, boolean gap) {}
    public record Period(long configTs, String productionDate, int shiftIndex, String shiftName,
                         String unit, String timezone, long startTs, long endTs) {}
    public record Row(String productionDate, int shiftIndex, String shiftName, String unit, String timezone,
                      long startTs, long endTs, long quantity, long observedMs, long estimatedMs,
                      long resets, boolean partial, String status) {}
    public record Report(long startTs, long endTs, long dataAsOf, String groupBy, List<Row> rows) {}
    public record Overview(Config config, String productionDate, long dayStart, long dayEnd,
                           long today, long week, long month, List<Row> shifts,
                           Long firstSampleTs, Long lastSampleTs, Double speed, Long speedTs,
                           String status, long now) {}
}
