// SPDX-FileCopyrightText: Copyright ThingsBoard, Inc.
// SPDX-License-Identifier: BUSL-1.1
package org.thingsboard.server.dao.subscription;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.thingsboard.server.common.data.LicenseInfo;
import org.thingsboard.server.common.data.id.TenantId;
import org.thingsboard.server.common.data.subscription.SubscriptionInfo;

/** Local entitlement policy for this fork, independent of the external subscription service. */
@Service
@Profile("!licensed & !install & !test")
public class LocalSubscriptionService extends InstallSubscriptionService {

    @Override
    public boolean edgeEnabled(TenantId tenantId) {
        return true;
    }

    @Override
    public boolean trendzEnabled(TenantId tenantId) {
        return true;
    }

    @Override
    public LicenseInfo getLicenseInfo() {
        LicenseInfo info = new LicenseInfo();
        info.setPlan("Local");
        info.setWhiteLabelingEnabled(true);
        return info;
    }

    @Override
    public SubscriptionInfo getSubscriptionInfo() {
        SubscriptionInfo info = new SubscriptionInfo();
        info.setSubscriptionPlanName("Local");
        info.setOffline(true);
        info.setPerpetual(true);
        info.setMaxDevices(-1);
        info.setMaxAssets(-1);
        info.setMaxEdges(-1);
        info.setMaxAgents(-1);
        info.setMaxInstances(-1);
        // An external AI service's credits cannot be supplied by a local entitlement policy.
        info.setMaxAiCredits(0);
        info.setWhiteLabelingEnabled(true);
        info.setEdgeEnabled(true);
        info.setTrendzEnabled(true);
        info.setPlanEdgeEnabled(true);
        info.setPlanTrendzEnabled(true);
        return info;
    }

    @Override
    public SubscriptionInfo refreshLicense() {
        return getSubscriptionInfo();
    }
}
