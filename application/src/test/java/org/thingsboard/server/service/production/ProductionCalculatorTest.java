// SPDX-FileCopyrightText: Copyright The ThingsBoard Authors
// SPDX-License-Identifier: Apache-2.0
package org.thingsboard.server.service.production;

import org.junit.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Random;

import static org.junit.Assert.*;
import static org.thingsboard.server.service.production.ProductionCalculator.*;
import static org.thingsboard.server.service.production.ProductionModels.*;

public class ProductionCalculatorTest {
    @Test
    public void countsMultipleResetsWithoutCountingInitialCounter() {
        Sample previous = null;
        long quantity = 0;
        int resets = 0;
        long[] readings = {1000, 1010, 1010, 0, 7, 0, 3};
        for (int i = 0; i < readings.length; i++) {
            previous = reading(1000L + i * 2000L, readings[i], previous, 2);
            quantity += previous.delta();
            if (previous.reset()) resets++;
        }
        assertEquals(20, quantity);
        assertEquals(2, resets);
    }

    @Test
    public void earlyMorningBelongsToPreviousProductionDay() {
        Config config = Config.defaults();
        long start = dayStart(LocalDate.of(2026, 10, 13), config);
        assertEquals(LocalDate.of(2026, 10, 12), productionDate(start - 1, config));
        assertEquals(LocalDate.of(2026, 10, 13), productionDate(start, config));
    }

    @Test
    public void threeShiftsCoverTheWholeProductionDay() {
        Config config = new Config("贴", "Asia/Shanghai", 2,
                List.of(new Shift("早班", "07:00"), new Shift("中班", "15:00"), new Shift("夜班", "23:00")));
        validate(config);
        long start = dayStart(LocalDate.of(2026, 10, 13), config);
        var periods = periods(List.of(new ConfigVersion(0, config)), start, start + 86400000, false);
        assertEquals(3, periods.size());
        assertEquals(start, periods.getFirst().startTs());
        assertEquals(start + 86400000, periods.getLast().endTs());
        for (int i = 0; i < 2; i++) assertEquals(periods.get(i).endTs(), periods.get(i + 1).startTs());
    }

    @Test
    public void rangeAllocationConservesCountsIncludingLargeValues() {
        Random random = new Random(2841);
        for (int i = 0; i < 1000; i++) {
            long delta = i == 0 ? Long.MAX_VALUE : random.nextInt(10000);
            Sample sample = new Sample(9000, delta, 1000L, delta, false, false);
            long boundary = 1000 + random.nextInt(8001);
            assertEquals(delta, allocate(sample, 1000, boundary) + allocate(sample, boundary, 9000));
        }
    }

    @Test
    public void configVersionsPreserveHistoricalShiftsAndUnits() {
        Config old = Config.defaults();
        Config next = new Config("页", "Asia/Shanghai", 2, List.of(new Shift("早班", "08:00"), new Shift("晚班", "20:00")));
        long effective = dayStart(LocalDate.of(2026, 10, 14), old);
        var versions = List.of(new ConfigVersion(0, old), new ConfigVersion(effective, next));
        var periods = periods(versions, effective - 1000, effective + 7200000, false);
        assertEquals("夜班", periods.getFirst().shiftName());
        assertEquals("贴", periods.getFirst().unit());
        assertEquals(effective, periods.getFirst().endTs());
        assertEquals(effective, periods.get(1).startTs());
        assertEquals("页", periods.getLast().unit());
        for (int i = 0; i + 1 < periods.size(); i++) assertEquals(periods.get(i).endTs(), periods.get(i + 1).startTs());
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsRepeatedShiftTimes() {
        validate(new Config("贴", "Asia/Shanghai", 2, List.of(new Shift("早班", "07:00"), new Shift("夜班", "07:00"))));
    }

    @Test
    public void outageIsFlaggedAndStoppedCounterProducesZero() {
        Sample start = reading(1000, 100, null, 2);
        assertEquals(0, reading(3000, 100, start, 2).delta());
        assertFalse(reading(3000, 100, start, 2).gap());
        assertTrue(reading(13000, 110, start, 2).gap());
    }
}
