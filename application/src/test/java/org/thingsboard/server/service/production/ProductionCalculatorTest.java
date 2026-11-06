// SPDX-FileCopyrightText: Copyright The ThingsBoard Authors
// SPDX-License-Identifier: Apache-2.0
package org.thingsboard.server.service.production;

import org.junit.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.Assert.*;
import static org.thingsboard.server.service.production.ProductionCalculator.*;
import static org.thingsboard.server.service.production.ProductionModels.*;

public class ProductionCalculatorTest {
    private List<Sample> samples(long[][] counters) {
        List<Sample> result = new ArrayList<>();
        Sample previous = null;
        for (long[] counter : counters) {
            previous = reading(counter[0], counter[1], previous, 2);
            result.add(previous);
        }
        return result;
    }

    @Test
    public void suppressesTheCapturedBurstOfZerosWithoutCountingTheRestored1924() {
        long reconnect = 1792800003939L;
        var raw = samples(new long[][]{{1792668459698L, 1924}, {reconnect, 0},
                {reconnect + 346, 0}, {reconnect + 347, 0}, {reconnect + 348, 0},
                {reconnect + 349, 0}, {reconnect + 350, 0}, {reconnect + 351, 0},
                {reconnect + 352, 1924}, {1792800551328L, 1924},
                {1792800552320L, 0}, {1792800589000L, 0}, {1792800589459L, 1}, {1792803524852L, 6241}});
        assertEquals(8165, raw.stream().mapToLong(Sample::delta).sum());
        var corrected = suppressTransientZeros(raw, List.of(new ConfigVersion(0, Config.defaults())));
        assertEquals(6241, corrected.stream().mapToLong(Sample::delta).sum());
        assertEquals(1, corrected.stream().filter(Sample::reset).count());
        for (int i = 1; i <= 8; i++) {
            assertEquals(raw.get(i).counter(), corrected.get(i).counter());
            assertEquals(raw.get(i).previousTs(), corrected.get(i).previousTs());
            assertTrue(corrected.get(i).gap());
            assertFalse(corrected.get(i).reset());
            assertEquals(0, corrected.get(i).delta());
        }
    }

    @Test
    public void preservesRealResetsThatGrowToADifferentValueOrPersistForAReportInterval() {
        for (long[][] counters : List.of(new long[][]{{0, 100}, {1000, 0}, {1500, 7}},
                new long[][]{{0, 100}, {1000, 0}, {3000, 100}},
                new long[][]{{0, 100}, {1000, 0}, {1500, 1}, {1900, 100}},
                new long[][]{{0, 0}, {352, 1924}})) {
            var raw = samples(counters);
            assertEquals(raw, suppressTransientZeros(raw, List.of(new ConfigVersion(0, Config.defaults()))));
        }
    }

    @Test
    public void usesTheConfigurationAtTheFirstZeroAndCapsTheWindowAtTenSeconds() {
        var raw = samples(new long[][]{{0, 100}, {1000, 0}, {2500, 100}});
        var oneSecond = new Config("贴", "Asia/Shanghai", 1, Config.defaults().shifts());
        assertEquals(raw, suppressTransientZeros(raw, List.of(new ConfigVersion(0, oneSecond),
                new ConfigVersion(2000, Config.defaults()))));
        var slow = new Config("贴", "Asia/Shanghai", 60, Config.defaults().shifts());
        assertEquals(0, suppressTransientZeros(raw, List.of(new ConfigVersion(0, slow)))
                .stream().mapToLong(Sample::delta).sum());
        var longReset = samples(new long[][]{{0, 100}, {1000, 0}, {11000, 100}});
        assertEquals(longReset, suppressTransientZeros(longReset, List.of(new ConfigVersion(0, slow))));
    }

    @Test
    public void excludesTheConfirmedInfraredResetIntermediate512AndPreservesNewProduction() {
        var raw = samples(new long[][]{{1793430959058L, 14}, {1793431690576L, 606},
                {1793431694272L, 512}, {1793431697961L, 0}, {1793431701656L, 1}, {1793431705343L, 8}});
        assertEquals(1112, raw.stream().mapToLong(Sample::delta).sum());

        var corrected = suppressTransientZeros(raw, List.of(new ConfigVersion(0, Config.defaults())));

        assertEquals(600, corrected.stream().mapToLong(Sample::delta).sum());
        assertEquals(512, corrected.get(2).counter());
        assertEquals(raw.get(2).previousTs(), corrected.get(2).previousTs());
        assertEquals(0, corrected.get(2).delta());
        assertFalse(corrected.get(2).reset());
        assertTrue(corrected.get(2).gap());
        assertTrue(corrected.get(3).reset());
        assertTrue(corrected.get(3).gap());
        assertEquals(1, corrected.stream().filter(Sample::reset).count());
        assertEquals(1, corrected.get(4).delta());
        assertEquals(7, corrected.get(5).delta());
    }

    @Test
    public void confirmsMultipleDescendingAndRepeatedIntermediateValuesOnlyAfterZeroArrives() {
        var raw = samples(new long[][]{{0, 1000}, {1000, 768}, {2000, 768}, {4000, 512},
                {6000, 256}, {9999, 0}, {12000, 3}});
        var history = List.of(new ConfigVersion(0, Config.defaults()));
        var unfinished = raw.subList(0, 5);
        assertEquals(unfinished, suppressTransientZeros(unfinished, history));

        var corrected = suppressTransientZeros(raw, history);

        assertEquals(3, corrected.stream().mapToLong(Sample::delta).sum());
        assertEquals(1, corrected.stream().filter(Sample::reset).count());
        for (int i = 1; i <= 4; i++) {
            assertEquals(raw.get(i).counter(), corrected.get(i).counter());
            assertEquals(0, corrected.get(i).delta());
            assertFalse(corrected.get(i).reset());
        }
    }

    @Test
    public void preservesDeclinesWithoutZeroWithInterveningGrowthOrOutsideTheConfirmationWindow() {
        for (long[][] counters : List.of(new long[][]{{0, 606}, {1000, 512}, {5000, 512}},
                new long[][]{{0, 606}, {1000, 512}, {11000, 0}, {13000, 8}},
                new long[][]{{0, 606}, {1000, 512}, {4000, 513}, {5000, 0}, {7000, 8}},
                new long[][]{{0, 0}, {1000, 512}, {2000, 0}, {3000, 8}})) {
            var raw = samples(counters);
            assertEquals(raw, suppressTransientZeros(raw, List.of(new ConfigVersion(0, Config.defaults()))));
        }
    }

    @Test
    public void restoresTheOriginalBaselineWhenResetIntermediatesPrecedeAZeroRebound() {
        var raw = samples(new long[][]{{0, 606}, {1000, 512}, {4000, 0}, {4352, 606}, {8000, 610}});
        var corrected = suppressTransientZeros(raw, List.of(new ConfigVersion(0, Config.defaults())));
        assertEquals(4, corrected.stream().mapToLong(Sample::delta).sum());
        assertEquals(0, corrected.stream().filter(Sample::reset).count());
        // A real new count of 512 must not be mistaken for a recovery of the invalid intermediate.
        var different = samples(new long[][]{{0, 606}, {1000, 512}, {4000, 0}, {4352, 512}});
        assertEquals(512, suppressTransientZeros(different, List.of(new ConfigVersion(0, Config.defaults())))
                .stream().mapToLong(Sample::delta).sum());
    }

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
