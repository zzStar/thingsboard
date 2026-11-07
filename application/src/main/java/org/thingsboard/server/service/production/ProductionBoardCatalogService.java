// SPDX-FileCopyrightText: Copyright The ThingsBoard Authors
// SPDX-License-Identifier: Apache-2.0
package org.thingsboard.server.service.production;

import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.thingsboard.common.util.JacksonUtil;
import org.thingsboard.server.common.data.DashboardInfo;
import org.thingsboard.server.common.data.EntityType;
import org.thingsboard.server.common.data.id.EntityGroupId;
import org.thingsboard.server.common.data.exception.ThingsboardException;
import org.thingsboard.server.common.data.page.PageData;
import org.thingsboard.server.common.data.page.PageLink;
import org.thingsboard.server.common.data.page.SortOrder;
import org.thingsboard.server.common.data.permission.Operation;
import org.thingsboard.server.common.data.permission.Resource;
import org.thingsboard.server.dao.entity.EntityService;
import org.thingsboard.server.service.security.model.SecurityUser;
import org.thingsboard.server.service.security.permission.AccessControlService;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import static org.thingsboard.server.service.production.ProductionBoardModels.BoardEntry;

/** Catalog projection over native authorized Dashboards, without a separate screen registry or ACL. */
@Service
@Profile("!install")
public class ProductionBoardCatalogService {
    private final EntityService entities;
    private final AccessControlService permissions;
    private final NamedParameterJdbcTemplate sql;

    public ProductionBoardCatalogService(EntityService entities, AccessControlService permissions, JdbcTemplate jdbc) {
        this.entities = entities; this.permissions = permissions; this.sql = new NamedParameterJdbcTemplate(jdbc);
    }

    public PageData<BoardEntry> list(SecurityUser user, int pageSize, int page, String textSearch) throws ThingsboardException {
        if (pageSize < 1 || pageSize > 100 || page < 0 || page > 100000 || textSearch != null && textSearch.length() > 255) {
            throw new IllegalArgumentException("无效的大屏列表查询参数");
        }
        PageLink nativePage = new PageLink(100, 0, textSearch, new SortOrder("createdTime", SortOrder.Direction.DESC));
        Set<EntityGroupId> writableGroups = new java.util.HashSet<>(user.getUserPermissions()
                .getGroupPermissionsByEntityTypeAndOperation(EntityType.DASHBOARD, Operation.WRITE).getEntityGroupIds());
        List<BoardEntry> result = new ArrayList<>();
        int scanned = 0;
        while (true) {
            PageData<DashboardInfo> allowed = entities.findUserEntities(user.getTenantId(), user.getCustomerId(),
                    user.getUserPermissions(), EntityType.DASHBOARD, Operation.READ, null, nativePage);
            scanned += allowed.getData().size();
            if (scanned > 10000) throw new IllegalArgumentException("可访问看板较多，请输入名称缩小查询范围");
            if (!allowed.getData().isEmpty()) {
                Map<UUID, Boolean> production = new HashMap<>();
                sql.query("SELECT id,configuration::text FROM dashboard WHERE tenant_id=:tenant AND id IN (:ids)",
                        Map.of("tenant", user.getTenantId().getId(), "ids", allowed.getData().stream().map(d -> d.getId().getId()).toList()), rs -> {
                            var config = JacksonUtil.toJsonNode(rs.getString(2));
                            var widgets = config == null ? null : config.get("widgets");
                            if (widgets != null && widgets.isContainerNode()) {
                                for (var widget : widgets) {
                                    if (ProductionBoardConfigParser.WIDGET_FQN.equals(widget.path("typeFullFqn").asText())) {
                                        production.put(rs.getObject(1, UUID.class), true); break;
                                    }
                                }
                            }
                        });
                for (DashboardInfo dashboard : allowed.getData()) {
                    if (!production.containsKey(dashboard.getId().getId()) || !permissions.hasPermission(user,
                            Resource.DASHBOARD, Operation.READ, dashboard.getId(), dashboard)) continue;
                    boolean canEdit = permissions.hasPermission(user, Resource.DASHBOARD, Operation.WRITE, dashboard.getId(), dashboard);
                    UUID editGroupId = canEdit && dashboard.getGroups() != null ? dashboard.getGroups().stream()
                            .filter(group -> writableGroups.contains(group.getId())).map(group -> group.getId().getId()).findFirst().orElse(null) : null;
                    result.add(new BoardEntry(dashboard.getId().getId(), dashboard.getTitle(), dashboard.getCreatedTime(), canEdit, editGroupId));
                }
            }
            if (!allowed.hasNext()) break;
            nativePage = nativePage.nextPageLink();
        }
        int start = Math.min(page * pageSize, result.size());
        int end = Math.min(start + pageSize, result.size());
        return new PageData<>(List.copyOf(result.subList(start, end)), (result.size() + pageSize - 1) / pageSize,
                result.size(), end < result.size());
    }
}
