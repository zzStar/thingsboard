// SPDX-FileCopyrightText: Copyright The ThingsBoard Authors
// SPDX-License-Identifier: Apache-2.0
package org.thingsboard.server.controller;

import org.junit.Before;
import org.junit.Test;
import org.thingsboard.server.common.data.Device;
import org.thingsboard.server.common.data.Customer;
import org.thingsboard.server.common.data.EntityType;
import org.thingsboard.server.common.data.group.EntityGroup;
import org.thingsboard.server.common.data.permission.Operation;
import org.thingsboard.server.common.data.permission.Resource;
import org.thingsboard.server.dao.service.DaoSqlTest;
import org.springframework.test.context.TestPropertySource;

import java.time.LocalDate;
import java.time.Duration;
import java.util.Map;
import java.util.List;

import static org.junit.Assert.*;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.thingsboard.server.service.production.ProductionModels.*;

@DaoSqlTest
@TestPropertySource(properties = "database.ts_max_intervals=350")
public class DeviceProductionControllerTest extends AbstractControllerTest {
    private Device device;

    @Before
    public void setupProductionDevice() throws Exception {
        loginTenantAdmin();
        Device draft = new Device();
        draft.setName("Production integration test");
        draft.setType("default");
        device = doPost("/api/device", draft, Device.class);
    }

    private String api(String path) { return "/api/production/" + device.getId().getId() + path; }

    @Test
    public void speedTrendAcceptsReportedAndFullDayRangesUnderACustomDatabaseLimit() throws Exception {
        doGet(api("/speed?startTs=1791932400000&endTs=1791980244710")).andExpect(status().isOk());
        long now = System.currentTimeMillis();
        doGet(api("/speed?startTs=" + (now - 86400000L) + "&endTs=" + now)).andExpect(status().isOk());
    }

    @Test
    public void savedTelemetryAutomaticallyFeedsProductionAndSpeed() throws Exception {
        long start = System.currentTimeMillis() - 12000;
        long[] values = {1000, 1010, 0, 7, 0, 3};
        for (int i = 0; i < values.length; i++) {
            String json = "{\"ts\":" + (start + i * 2000) + ",\"values\":{\"totalCount\":" + values[i] + ",\"speed\":8400}}";
            doPostAsync("/api/plugins/telemetry/DEVICE/" + device.getId().getId() + "/timeseries/ANY", json, String.class, status().isOk());
        }
        Report report = doGet(api("/report?startTs=" + start + "&endTs=" + (start + 10000) + "&groupBy=day"), Report.class);
        assertEquals(20, report.rows().stream().mapToLong(Row::quantity).sum());
        Overview overview = doGet(api("/overview"), Overview.class);
        assertEquals("贴", overview.config().unit());
        assertEquals(8400, overview.speed(), 0);
        assertNotNull(overview.lastSampleTs());
        var speed = doGet(api("/speed?startTs=" + start + "&endTs=" + System.currentTimeMillis()),
                com.fasterxml.jackson.databind.JsonNode.class);
        assertFalse(speed.isEmpty());
        assertEquals("speed", speed.get(0).get("key").asText());
        assertEquals(8400, Double.parseDouble(speed.get(0).get("value").asText()), 0);
    }

    @Test
    public void validatesRangeAndSchedulesThreeShifts() throws Exception {
        doGet(api("/report?startTs=20&endTs=10")).andExpect(status().isBadRequest());
        Config config = new Config("页", "Asia/Shanghai", 2,
                List.of(new Shift("早班", "07:00"), new Shift("中班", "15:00"), new Shift("夜班", "23:00")));
        Settings saved = doPost(api("/settings"), new SaveSettings(config, LocalDate.now().plusDays(2).toString()), Settings.class);
        assertEquals(2, saved.versions().size());
        assertEquals(3, saved.versions().getLast().config().shifts().size());
        assertEquals("页", saved.current().unit());
        assertEquals(2, saved.current().shifts().size());
    }

    @Test
    public void customerCannotManageTenantUnassignedDevice() throws Exception {
        loginCustomerUser();
        doGet(api("/overview")).andExpect(status().isForbidden());
        doPost(api("/settings"), new SaveSettings(Config.defaults(), LocalDate.now().plusDays(2).toString()))
                .andExpect(status().isForbidden());
        doPost(api("/screen-link")).andExpect(status().isForbidden());
    }

    private Device ownedDevice(String name) throws Exception {
        Device draft = new Device();
        draft.setName(name);
        draft.setType("default");
        draft.setCustomerId(customerId);
        return doPost("/api/device", draft, Device.class);
    }

    private void grantCustomerProductionRole(Operation... operations) throws Exception {
        EntityGroup users = new EntityGroup();
        users.setName("Production operators");
        users.setType(EntityType.USER);
        users.setOwnerId(customerId);
        users = doPost("/api/entityGroup", users, EntityGroup.class);
        doPost("/api/entityGroup/" + users.getId() + "/addEntities", List.of(customerUserId.getId().toString()))
                .andExpect(status().isOk());
        var role = createGroupRole(customerId, "Production operations",
                operations.length == 0 ? List.of(Operation.READ, Operation.READ_TELEMETRY, Operation.WRITE_ATTRIBUTES, Operation.WRITE_TELEMETRY)
                        : List.of(operations));
        var devices = findGroupByOwnerIdTypeAndName(customerId, EntityType.DEVICE, EntityGroup.GROUP_ALL_NAME);
        createGroupPermission(users.getId(), role.getId(), devices.getId(), EntityType.DEVICE);
    }

    @Test
    public void ordinaryCustomerWithConfiguredRoleHasFullProductionLifecycle() throws Exception {
        device = ownedDevice("Customer production lifecycle");
        long start = System.currentTimeMillis() - 10000;
        for (int i = 0; i < 2; i++) {
            doPostAsync("/api/plugins/telemetry/DEVICE/" + device.getId().getId() + "/timeseries/ANY",
                    "{\"ts\":" + (start + i * 2000) + ",\"values\":{\"totalCount\":" + (1000 + 10 * i) + ",\"speed\":8400}}",
                    String.class, status().isOk());
        }
        grantCustomerProductionRole();
        loginCustomerUser();
        doGet("/api/user/devices?pageSize=50&page=0&textSearch=Customer").andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].name").value(device.getName()));
        doGet(api("/overview")).andExpect(status().isOk()).andExpect(jsonPath("$.today").value(10));
        doGet(api("/settings")).andExpect(status().isOk());
        doGet(api("/speed?startTs=" + start + "&endTs=" + System.currentTimeMillis())).andExpect(status().isOk());
        doGet(api("/report?startTs=" + start + "&endTs=" + (start + 2000) + "&groupBy=shift"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.rows[0].quantity").value(10));
        Config changed = new Config("张", "Asia/Shanghai", 2,
                List.of(new Shift("早班", "07:00"), new Shift("中班", "15:00"), new Shift("夜班", "23:00")));
        Settings saved = doPost(api("/settings"), new SaveSettings(changed, LocalDate.now().plusDays(2).toString()), Settings.class);
        assertEquals("张", saved.current().unit());
        assertEquals(3, saved.versions().getLast().config().shifts().size());
        doGet(api("/backfill")).andExpect(status().isOk());
        doPost(api("/backfill"), new BackfillRequest(start, System.currentTimeMillis() - 1000), Backfill.class);
        await().atMost(Duration.ofSeconds(15)).until(() -> "COMPLETE".equals(doGet(api("/backfill"), Backfill.class).status()));
        ScreenLink link = doPost(api("/screen-link"), ScreenLink.class);
        assertEquals(link, doGet(api("/screen-link"), ScreenLink.class));
        mockMvc.perform(get(screen()).header("X-Production-Screen-Key", link.key())).andExpect(status().isOk())
                .andExpect(jsonPath("$.overview.config.unit").value("张"));
        ScreenLink rotated = doPost(api("/screen-link?rotate=true"), ScreenLink.class);
        assertNotEquals(link.key(), rotated.key());
        mockMvc.perform(get(screen()).header("X-Production-Screen-Key", link.key())).andExpect(status().isNotFound());
        doDelete(api("/screen-link")).andExpect(status().isOk());
        mockMvc.perform(get(screen()).header("X-Production-Screen-Key", rotated.key())).andExpect(status().isNotFound());
    }

    @Test
    public void ordinaryCustomerReadOnlyRoleCannotMutateProduction() throws Exception {
        device = ownedDevice("Read-only customer production");
        grantCustomerProductionRole(Operation.READ, Operation.READ_TELEMETRY);
        loginCustomerUser();
        doGet(api("/overview")).andExpect(status().isOk());
        doGet(api("/settings")).andExpect(status().isOk());
        doGet(api("/backfill")).andExpect(status().isOk());
        doPost(api("/settings"), new SaveSettings(Config.defaults(), null)).andExpect(status().isForbidden());
        doGet(api("/screen-link")).andExpect(status().isForbidden());
        doPost(api("/screen-link")).andExpect(status().isForbidden());
        doDelete(api("/screen-link")).andExpect(status().isForbidden());
        doPost(api("/backfill"), new BackfillRequest(0, 1000)).andExpect(status().isForbidden());
    }

    private void assertProductionDenied() throws Exception {
        long start = System.currentTimeMillis() - 10000;
        var denied = status().is(org.hamcrest.Matchers.isOneOf(403, 404));
        for (String suffix : List.of("/settings", "/overview", "/screen-link", "/backfill",
                "/report?startTs=" + start + "&endTs=" + (start + 5000),
                "/speed?startTs=" + start + "&endTs=" + (start + 5000))) {
            doGet(api(suffix)).andExpect(denied);
        }
        doPost(api("/settings"), new SaveSettings(Config.defaults(), null)).andExpect(denied);
        doPost(api("/backfill"), new BackfillRequest(start, start + 5000)).andExpect(denied);
        doPost(api("/screen-link")).andExpect(denied);
        doDelete(api("/screen-link")).andExpect(denied);
    }

    @Test
    public void configuredCustomerRoleCannotAccessOtherCustomerOrTenantDevices() throws Exception {
        grantCustomerProductionRole();
        loginCustomerUser();
        assertProductionDenied(); // The setup device is owned by the tenant.
        loginTenantAdmin();
        Customer other = new Customer();
        other.setTitle("Other customer production isolation");
        other = doPost("/api/customer", other, Customer.class);
        Device draft = new Device();
        draft.setName("Private other customer device");
        draft.setType("default");
        draft.setCustomerId(other.getId());
        device = doPost("/api/device", draft, Device.class);
        loginCustomerUser();
        assertProductionDenied();
        loginTenantAdmin();
        device = ownedDevice("Private first tenant device");
        loginDifferentTenantCustomer();
        assertProductionDenied();
    }

    @Test
    public void productionRetainsRestrictedTenantAdminPermissions() throws Exception {
        loginAsRestrictedTenantAdmin(tenantId, Map.of(Resource.DEVICE, List.of(Operation.READ, Operation.READ_TELEMETRY)));
        doGet(api("/overview")).andExpect(status().isOk());
        doPost(api("/settings"), new SaveSettings(Config.defaults(), null)).andExpect(status().isForbidden());
        doPost(api("/screen-link")).andExpect(status().isForbidden());
        doPost(api("/backfill"), new BackfillRequest(0, 1000)).andExpect(status().isForbidden());
    }

    private String screen() { return "/api/noauth/production/" + device.getId().getId() + "/screen"; }

    @Test
    public void anonymousScreenShowsDeviceCustomerAndRefreshesItsName() throws Exception {
        Customer customer = new Customer();
        customer.setTitle("产量看板测试客户");
        customer = doPost("/api/customer", customer, Customer.class);
        Device owned = new Device();
        owned.setName("客户设备");
        owned.setType("default");
        owned.setCustomerId(customer.getId());
        device = doPost("/api/device", owned, Device.class);
        ScreenLink link = doPost(api("/screen-link"), ScreenLink.class);
        mockMvc.perform(get(screen()).header("X-Production-Screen-Key", link.key())).andExpect(status().isOk())
                .andExpect(jsonPath("$.customerName").value("产量看板测试客户"))
                .andExpect(jsonPath("$.deviceName").value("客户设备"));
        customer.setTitle("更新后的客户名称");
        doPost("/api/customer", customer, Customer.class);
        mockMvc.perform(get(screen()).header("X-Production-Screen-Key", link.key())).andExpect(status().isOk())
                .andExpect(jsonPath("$.customerName").value("更新后的客户名称"));
        assertEquals(link, doGet(api("/screen-link"), ScreenLink.class));
    }

    @Test
    public void unitRenameUpdatesOverviewReportsAndExistingAnonymousScreenImmediately() throws Exception {
        long start = System.currentTimeMillis() - 5000;
        for (int i = 0; i < 2; i++) {
            doPostAsync("/api/plugins/telemetry/DEVICE/" + device.getId().getId() + "/timeseries/ANY",
                    "{\"ts\":" + (start + i * 2000) + ",\"values\":{\"totalCount\":" + (1000 + 10 * i) + ",\"speed\":8400}}",
                    String.class, status().isOk());
        }
        ScreenLink link = doPost(api("/screen-link"), ScreenLink.class);
        Config defaults = Config.defaults();
        Settings saved = doPost(api("/settings"), new SaveSettings(
                new Config("张", defaults.timezone(), defaults.reportIntervalSeconds(), defaults.shifts()), null), Settings.class);
        assertEquals("张", saved.current().unit());
        assertEquals(1, saved.versions().size());
        Overview overview = doGet(api("/overview"), Overview.class);
        assertEquals("张", overview.config().unit());
        assertEquals(8400, overview.speed(), 0);
        Report report = doGet(api("/report?startTs=" + start + "&endTs=" + (start + 2000)), Report.class);
        assertEquals(10, report.rows().stream().mapToLong(Row::quantity).sum());
        assertTrue(report.rows().stream().allMatch(row -> "张".equals(row.unit())));
        assertEquals(link, doGet(api("/screen-link"), ScreenLink.class));
        mockMvc.perform(get(screen()).header("X-Production-Screen-Key", link.key())).andExpect(status().isOk())
                .andExpect(jsonPath("$.overview.config.unit").value("张"))
                .andExpect(jsonPath("$.overview.today").value(10))
                .andExpect(jsonPath("$.overview.speed").value(8400))
                .andExpect(jsonPath("$.output.rows[0].unit").value("张"));
    }

    @Test
    public void permanentScreenIsAnonymousAndLimitedToOneDevice() throws Exception {
        ScreenLink link = doPost(api("/screen-link"), ScreenLink.class);
        assertEquals(43, link.key().length());
        assertEquals(link, doPost(api("/screen-link"), ScreenLink.class));
        assertEquals(link, doGet(api("/screen-link"), ScreenLink.class));
        jdbcTemplate.update("UPDATE production_screen_link SET created_ts=0 WHERE device_id=?", device.getId().getId());
        Device other = new Device();
        other.setName("Another production screen");
        other.setType("default");
        other = doPost("/api/device", other, Device.class);
        ScreenLink otherLink = doPost("/api/production/" + other.getId().getId() + "/screen-link", ScreenLink.class);
        assertNotEquals(link.key(), otherLink.key());
        long start = System.currentTimeMillis() - 5000;
        for (int i = 0; i < 2; i++) {
            doPostAsync("/api/plugins/telemetry/DEVICE/" + device.getId().getId() + "/timeseries/ANY",
                    "{\"ts\":" + (start + i * 2000) + ",\"values\":{\"totalCount\":" + (1000 + 10 * i) + ",\"speed\":8400}}",
                    String.class, status().isOk());
        }
        // These requests deliberately carry no login token, including the screen key on a protected API.
        mockMvc.perform(get(screen()).header("X-Production-Screen-Key", link.key())).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.deviceName").value(device.getName()))
                .andExpect(jsonPath("$.customerName").value(""))
                .andExpect(jsonPath("$.overview.today").value(10))
                .andExpect(jsonPath("$.overview.speed").value(8400))
                .andExpect(jsonPath("$.speed").isNotEmpty())
                .andExpect(jsonPath("$.key").doesNotExist());
        mockMvc.perform(get("/api/noauth/production/" + other.getId().getId() + "/screen")
                .header("X-Production-Screen-Key", link.key())).andExpect(status().isNotFound());
        mockMvc.perform(get(api("/overview")).header("X-Production-Screen-Key", link.key())).andExpect(status().isUnauthorized());
        mockMvc.perform(post(api("/screen-link"))).andExpect(status().isUnauthorized());
        mockMvc.perform(post(screen()).header("X-Production-Screen-Key", link.key())).andExpect(status().isMethodNotAllowed());
    }

    @Test
    public void screenRejectsMissingKeysAndInvalidatesRotatedRevokedAndDeletedLinks() throws Exception {
        ScreenLink link = doPost(api("/screen-link"), ScreenLink.class);
        mockMvc.perform(get(screen())).andExpect(status().isNotFound());
        mockMvc.perform(get(screen()).header("X-Production-Screen-Key", "x".repeat(43))).andExpect(status().isNotFound());
        ScreenLink replacement = doPost(api("/screen-link?rotate=true"), ScreenLink.class);
        assertNotEquals(link.key(), replacement.key());
        mockMvc.perform(get(screen()).header("X-Production-Screen-Key", link.key())).andExpect(status().isNotFound());
        mockMvc.perform(get(screen()).header("X-Production-Screen-Key", replacement.key())).andExpect(status().isOk());
        doDelete(api("/screen-link")).andExpect(status().isOk());
        mockMvc.perform(get(screen()).header("X-Production-Screen-Key", replacement.key())).andExpect(status().isNotFound());
        ScreenLink restored = doPost(api("/screen-link"), ScreenLink.class);
        assertNotEquals(replacement.key(), restored.key());
        doDelete("/api/device/" + device.getId().getId()).andExpect(status().isOk());
        mockMvc.perform(get(screen()).header("X-Production-Screen-Key", restored.key())).andExpect(status().isNotFound());
    }
}
