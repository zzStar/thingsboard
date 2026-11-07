// SPDX-FileCopyrightText: Copyright The ThingsBoard Authors
// SPDX-License-Identifier: Apache-2.0
package org.thingsboard.server.service.production;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.thingsboard.server.common.data.Dashboard;
import org.thingsboard.server.common.data.exception.ThingsboardException;
import org.thingsboard.server.common.data.id.*;
import org.thingsboard.server.common.data.permission.Operation;
import org.thingsboard.server.common.data.permission.Resource;
import org.thingsboard.server.dao.dashboard.DashboardService;
import org.thingsboard.server.service.security.model.SecurityUser;
import org.thingsboard.server.service.security.permission.AccessControlService;

import java.util.UUID;

import static org.thingsboard.server.service.production.ProductionBoardModels.*;

@Service
@Profile("!install")
@RequiredArgsConstructor
public class ProductionBoardService {
    private final DashboardService dashboards;
    private final AccessControlService permissions;
    private final ProductionBoardConfigParser parser;
    private final ProductionBoardAccessService access;
    private final ProductionBoardQueryService query;

    public Dashboard dashboard(SecurityUser user, UUID id) throws ThingsboardException {
        Dashboard dashboard = dashboards.findDashboardById(user.getTenantId(), new DashboardId(id));
        if (dashboard == null || !user.getTenantId().equals(dashboard.getTenantId())) throw ProductionBoardAccessService.denied();
        permissions.checkPermission(user, Resource.DASHBOARD, Operation.READ, dashboard.getId(), dashboard);
        return dashboard;
    }

    public Snapshot preview(SecurityUser user, UUID dashboardId, Selection selection) throws Exception {
        BoardConfig config = parser.parse(dashboard(user, dashboardId), selection.widgetId());
        return query.snapshot(user.getTenantId(), config, selection, access.resolve(user, config),
                ProductionBoardQueryService.fingerprint(config));
    }

}
