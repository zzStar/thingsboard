// SPDX-FileCopyrightText: Copyright The ThingsBoard Authors
// SPDX-License-Identifier: Apache-2.0
package org.thingsboard.server.service.production;

import org.thingsboard.server.common.data.kv.Aggregation;
import org.thingsboard.server.common.data.kv.BaseReadTsKvQuery;

public final class ProductionSpeedQuery {
    private ProductionSpeedQuery() {}

    public static BaseReadTsKvQuery create(long start, long end, long maxIntervals) {
        if (start < 0 || end <= start || maxIntervals < 1) {
            throw new IllegalArgumentException("速度查询范围及最大聚合区间数必须有效");
        }
        int points = (int) Math.min(1440, maxIntervals);
        long interval = Math.max(60_000, 1 + (end - start - 1) / points);
        return new BaseReadTsKvQuery("speed", start, end, interval, points, Aggregation.AVG, "ASC");
    }
}
