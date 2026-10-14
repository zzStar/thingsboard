// SPDX-FileCopyrightText: Copyright The ThingsBoard Authors
// SPDX-License-Identifier: Apache-2.0
package org.thingsboard.server.service.production;

import org.junit.Test;

import static org.junit.Assert.*;

public class ProductionSpeedQueryTest {
    @Test
    public void reportedRangeFitsTheDefaultDatabaseLimit() {
        var query = ProductionSpeedQuery.create(1791932400000L, 1791980244710L, 700);
        assertTrue(query.getInterval() > 60_000);
        assertTrue((query.getEndTs() - query.getStartTs() + query.getInterval() - 1) / query.getInterval() <= 700);
        assertEquals(700, query.getLimit());
        assertEquals("speed", query.getKey());
    }

    @Test
    public void keepsMinuteResolutionForShortRanges() {
        assertEquals(60_000, ProductionSpeedQuery.create(0, 6 * 3600_000L, 700).getInterval());
        assertEquals(60_000, ProductionSpeedQuery.create(0, 1, 700).getInterval());
    }

    @Test
    public void respectsCustomLimitsAndLargeRangesWithoutOverflow() {
        for (long maximum : new long[]{1, 300, 700, 2000, Long.MAX_VALUE}) {
            for (long range : new long[]{700 * 60_000L, 700 * 60_000L + 1, 24 * 3600_000L,
                    ProductionCalculator.MAX_RANGE, Long.MAX_VALUE}) {
                var query = ProductionSpeedQuery.create(0, range, maximum);
                long count = 1 + (range - 1) / query.getInterval();
                assertTrue(count <= maximum);
                assertTrue(count <= query.getLimit());
            }
        }
    }

    @Test
    public void rejectsInvalidRangesAndLimits() {
        assertThrows(IllegalArgumentException.class, () -> ProductionSpeedQuery.create(10, 10, 700));
        assertThrows(IllegalArgumentException.class, () -> ProductionSpeedQuery.create(-1, 10, 700));
        assertThrows(IllegalArgumentException.class, () -> ProductionSpeedQuery.create(0, 10, 0));
    }
}
