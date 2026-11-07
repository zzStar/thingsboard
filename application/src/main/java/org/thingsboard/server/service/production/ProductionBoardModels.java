// SPDX-FileCopyrightText: Copyright The ThingsBoard Authors
// SPDX-License-Identifier: Apache-2.0
package org.thingsboard.server.service.production;

import org.thingsboard.server.common.data.Device;
import org.thingsboard.server.common.data.id.EntityId;

import java.util.List;
import java.util.UUID;

/** The public read model deliberately contains neither Dashboard scripts nor device credentials. */
public final class ProductionBoardModels {
    private ProductionBoardModels() {}

    public record Source(String mode, List<UUID> groupIds, List<UUID> deviceIds) {}
    public record Display(String theme, int typesPerFrame, int devicesPerCard, int detailRowBudget,
                          int rotationSeconds, int refreshSeconds) {}
    public record BoardConfig(int configVersion, String title, Source source, String typeAttribute,
                              List<String> typeOrder, Display display) {}
    public record BoardEntry(UUID id, String title, long createdTime, boolean canEdit, UUID editGroupId) {}
    public record Selection(String widgetId, String view, String typeId, UUID deviceId, Integer page) {}
    public record Member(Device device, String productionType, Double speed, Long speedTs) {
        public UUID id() { return device.getId().getId(); }
        public EntityId owner() { return device.getOwnerId(); }
    }
    public record Totals(Long today, Long week, Long month, Double averageSpeed, long elapsedMs) {}
    public record ShiftRow(String name, String start, String end, Long today, Long week, Long month,
                           Double averageSpeed, long elapsedMs) {}
    public record DayPoint(String date, Long quantity) {}
    public record DeviceRow(UUID id, String name, String typeId, String unit, String timezone,
                            String productionDate, Totals totals, List<ShiftRow> shifts, List<DayPoint> trend,
                            Long lastSampleTs, Double speed, Long speedTs, int staleAfterSeconds) {}
    public record TypeInfo(String id, String name, String unit, String timezone, int deviceCount, int noDataCount, Totals totals) {}
    public record DeviceChoice(UUID id, String name, String typeId) {}
    public record FrameSpec(String typeId, int devicePage) {}
    public record FramePlan(List<FrameSpec> types) {}
    public record TypeFrame(TypeInfo type, List<DeviceRow> devices, List<ShiftRow> shiftTotals, List<DayPoint> trend, int devicePage, int devicePages) {}
    public record Snapshot(BoardConfig config, long computedAsOf, Long dataAsOf, String configRevision,
                           String effectiveScopeRevision, String view, String typeId,
                           UUID deviceId, int page, int pageCount, List<TypeInfo> types, List<DeviceChoice> deviceChoices,
                           List<FramePlan> plan, List<TypeFrame> frames, List<ProductionModels.SpeedPoint> speed,
                           boolean speedUnavailable) {}
}
