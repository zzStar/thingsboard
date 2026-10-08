// SPDX-FileCopyrightText: Copyright ThingsBoard, Inc.
// SPDX-License-Identifier: BUSL-1.1
package org.thingsboard.server.dao.subscription;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.thingsboard.server.common.data.id.TenantId;

import static org.junit.jupiter.api.Assertions.*;

class LocalSubscriptionServiceTest {
    @Test
    void defaultContextSelectsOnlyLocalPolicy() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.register(LocalSubscriptionService.class, InstallSubscriptionService.class,
                    BasicSubscriptionService.class, BasicLicenseActivationService.class);
            context.refresh();
            assertEquals(1, context.getBeansOfType(SubscriptionService.class).size());
            assertInstanceOf(LocalSubscriptionService.class, context.getBean(SubscriptionService.class));
            assertTrue(context.getBeansOfType(LicenseActivationService.class).isEmpty());
        }
    }

    @Test
    void installationKeepsItsExistingPolicy() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().setActiveProfiles("install");
            context.register(LocalSubscriptionService.class, InstallSubscriptionService.class, BasicSubscriptionService.class);
            context.refresh();
            assertEquals(1, context.getBeansOfType(SubscriptionService.class).size());
            assertEquals(InstallSubscriptionService.class, context.getBean(SubscriptionService.class).getClass());
        }
    }

    @Test
    void localPolicyAllowsSetupWithoutDevelopmentEntitlement() {
        LocalSubscriptionService service = new LocalSubscriptionService();
        assertTrue(service.isLicenseActivated());
        assertFalse(service.isNonProductionMode());
        assertFalse(service.isDevelopment(TenantId.SYS_TENANT_ID));
        assertDoesNotThrow(() -> service.createDeviceAllowed(TenantId.SYS_TENANT_ID));
        assertDoesNotThrow(() -> service.whiteLabelingAllowed(TenantId.SYS_TENANT_ID));
        assertEquals(-1, service.getSubscriptionInfo().getMaxDevices());
        assertEquals(0, service.getSubscriptionInfo().getMaxAiCredits());
        assertTrue(service.getSubscriptionInfo().isOffline());
        assertNotNull(service.refreshLicense());
    }
}
