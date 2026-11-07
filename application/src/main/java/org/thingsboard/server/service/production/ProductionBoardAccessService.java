// SPDX-FileCopyrightText: Copyright The ThingsBoard Authors
// SPDX-License-Identifier: Apache-2.0
package org.thingsboard.server.service.production;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.thingsboard.server.common.data.Device;
import org.thingsboard.server.common.data.EntityType;
import org.thingsboard.server.common.data.exception.ThingsboardErrorCode;
import org.thingsboard.server.common.data.exception.ThingsboardException;
import org.thingsboard.server.common.data.id.DeviceId;
import org.thingsboard.server.common.data.query.*;
import org.thingsboard.server.dao.device.DeviceService;
import org.thingsboard.server.service.query.EntityQueryService;
import org.thingsboard.server.service.security.model.SecurityUser;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.thingsboard.server.service.production.ProductionBoardModels.*;
import static org.thingsboard.server.service.production.ProductionBoardConfigParser.MAX_DEVICES;

@Service
@Profile("!install")
@RequiredArgsConstructor
public class ProductionBoardAccessService {
    private final EntityQueryService queries;
    private final DeviceService devices;
    /** EntityQueryService applies the current user's native merged permissions, including public customers. */
    public List<Member> resolve(SecurityUser user, BoardConfig config) throws Exception {
        List<EntityFilter> filters = new ArrayList<>();
        Source source = config.source();
        if ("DEVICE_GROUPS".equals(source.mode())) {
            for (UUID id : source.groupIds()) {
                EntityGroupFilter filter = new EntityGroupFilter();
                filter.setGroupType(EntityType.DEVICE);
                filter.setEntityGroup(id.toString());
                filters.add(filter);
            }
        } else if ("EXPLICIT_DEVICES".equals(source.mode())) {
            var ids = source.deviceIds();
            if (ids.isEmpty()) return List.of();
            EntityListFilter filter = new EntityListFilter();
            filter.setEntityType(EntityType.DEVICE);
            filter.setEntityList(ids.stream().map(UUID::toString).toList());
            filters.add(filter);
        } else {
            EntityTypeFilter filter = new EntityTypeFilter();
            filter.setEntityType(EntityType.DEVICE);
            filters.add(filter);
        }
        Map<UUID, EntityData> allowed = new LinkedHashMap<>();
        int scanned = 0;
        for (EntityFilter filter : filters) {
            EntityDataQuery query = new EntityDataQuery(filter,
                    new EntityDataPageLink(200, 0, null,
                            new EntityDataSortOrder(new EntityKey(EntityKeyType.ENTITY_FIELD, "name"))),
                    List.of(new EntityKey(EntityKeyType.ENTITY_FIELD, "name")),
                    List.of(new EntityKey(EntityKeyType.SERVER_ATTRIBUTE, config.typeAttribute()),
                            new EntityKey(EntityKeyType.TIME_SERIES, "speed")), List.of());
            while (true) {
                var page = queries.findEntityDataByQuery(user, query);
                scanned += page.getData().size();
                if (scanned > 10000) throw new IllegalArgumentException("设备范围过大，请选择具体设备组");
                for (EntityData entity : page.getData()) {
                    UUID id = entity.getEntityId().getId();
                    if (entity.isReadAttrs() && entity.isReadTs()) {
                        allowed.put(id, entity);
                    }
                }
                if (allowed.size() > MAX_DEVICES) throw new IllegalArgumentException("每个产量大屏最多300台设备，请缩小范围");
                if (!page.hasNext()) break;
                query = query.next();
            }
        }
        if (allowed.isEmpty()) return List.of();
        List<Device> entities = devices.findDevicesByTenantIdAndIdsAsync(user.getTenantId(),
                allowed.keySet().stream().map(DeviceId::new).toList()).get(5, TimeUnit.SECONDS);
        List<Member> result = new ArrayList<>();
        for (Device device : entities) {
            UUID id = device.getId().getId();
            if (!user.getTenantId().equals(device.getTenantId()) || !allowed.containsKey(id)) continue;
            var data = allowed.get(id);
            String category = value(data, EntityKeyType.SERVER_ATTRIBUTE, config.typeAttribute());
            if (category == null || category.isBlank()) category = "未分类";
            if (category.length() > 64) category = category.substring(0, 64);
            Double speed = null;
            String raw = value(data, EntityKeyType.TIME_SERIES, "speed");
            try {
                if (raw != null) {
                    double number = Double.parseDouble(raw);
                    if (Double.isFinite(number) && number >= 0) speed = number;
                }
            } catch (NumberFormatException ignored) { /* Malformed telemetry is unknown, never zero. */ }
            var speedValue = data.getLatest() == null ? null : data.getLatest().getOrDefault(EntityKeyType.TIME_SERIES, Map.of()).get("speed");
            result.add(new Member(device, category.strip(), speed, speedValue == null ? null : speedValue.getTs()));
        }
        result.sort(Comparator.comparing((Member m) -> displayName(m.device())).thenComparing(Member::id));
        return result;
    }

    private static String value(EntityData data, EntityKeyType type, String key) {
        if (data.getLatest() == null) return null;
        var value = data.getLatest().getOrDefault(type, Map.of()).get(key);
        return value == null ? null : value.getValue();
    }

    public static String displayName(Device device) {
        return device.getLabel() != null && !device.getLabel().isBlank() ? device.getLabel() : device.getName();
    }

    public static ThingsboardException denied() {
        return new ThingsboardException("没有此产量看板或设备范围的操作权限", ThingsboardErrorCode.PERMISSION_DENIED);
    }
}
