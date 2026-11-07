// SPDX-FileCopyrightText: Copyright The ThingsBoard Authors
// SPDX-License-Identifier: Apache-2.0
package org.thingsboard.server.service.production;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.thingsboard.common.util.JacksonUtil;
import org.thingsboard.server.common.data.id.TenantId;
import org.thingsboard.server.common.data.widget.WidgetTypeDetails;
import org.thingsboard.server.common.data.widget.WidgetsBundle;
import org.thingsboard.server.dao.widget.WidgetTypeService;
import org.thingsboard.server.dao.widget.WidgetsBundleService;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;

/** Adds only the missing compiled system widget; never reloads or replaces existing system bundles. */
@Component
@Profile("!install")
@RequiredArgsConstructor
public class ProductionBoardWidgetInstaller {
    private final WidgetTypeService widgets;
    private final WidgetsBundleService bundles;
    private final JdbcTemplate jdbc;
    private final PlatformTransactionManager transactions;

    @PostConstruct
    public void install() throws Exception {
        String json;
        try (var stream = new ClassPathResource("production-wallboard-widget.json").getInputStream()) {
            json = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
        var descriptor = JacksonUtil.fromString(json, WidgetTypeDetails.class);
        new TransactionTemplate(transactions).executeWithoutResult(tx -> {
            jdbc.execute("SELECT pg_advisory_xact_lock(2026110601)");
            if (widgets.findWidgetTypeDetailsByTenantIdAndFqn(TenantId.SYS_TENANT_ID, descriptor.getFqn()) == null) {
                descriptor.setTenantId(TenantId.SYS_TENANT_ID);
                widgets.saveWidgetType(descriptor);
            }
            var bundle = bundles.findWidgetsBundleByTenantIdAndAlias(TenantId.SYS_TENANT_ID, "production");
            if (bundle == null) {
                bundle = new WidgetsBundle();
                bundle.setTenantId(TenantId.SYS_TENANT_ID);
                bundle.setAlias("production");
                bundle.setTitle("生产看板");
                bundle = bundles.saveWidgetsBundle(bundle);
            }
            var fqns = new ArrayList<>(widgets.findWidgetFqnsByWidgetsBundleId(TenantId.SYS_TENANT_ID, bundle.getId()));
            if (!fqns.contains(descriptor.getFqn())) {
                fqns.add(descriptor.getFqn());
                widgets.updateWidgetsBundleWidgetFqns(TenantId.SYS_TENANT_ID, bundle.getId(), fqns);
            }
        });
    }
}
