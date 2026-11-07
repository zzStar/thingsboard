// SPDX-FileCopyrightText: Copyright The ThingsBoard Authors
// SPDX-License-Identifier: Apache-2.0
package org.thingsboard.server.controller;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.Before;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.thingsboard.server.dao.widget.WidgetTypeService;
import org.thingsboard.server.common.data.id.TenantId;
import org.thingsboard.server.service.production.ProductionBoardWidgetInstaller;
import org.junit.Test;
import org.thingsboard.common.util.JacksonUtil;
import org.thingsboard.server.common.data.*;
import org.thingsboard.server.common.data.group.EntityGroup;
import org.thingsboard.server.common.data.permission.Operation;
import org.thingsboard.server.dao.service.DaoSqlTest;

import java.util.List;
import java.util.Map;
import static org.junit.Assert.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.thingsboard.server.service.production.ProductionBoardModels.*;

@DaoSqlTest
@TestPropertySource(properties = "database.ts_max_intervals=350")
public class ProductionBoardControllerTest extends AbstractControllerTest {
    @Autowired private ProductionBoardWidgetInstaller installer;
    @Autowired private WidgetTypeService boardWidgetTypes;
    private Dashboard board;
    private Device device;
    private final Selection all = new Selection("wall", "ALL", null, null, 0);
    @Before public void setupBoard() throws Exception {
        loginTenantAdmin();
        Device draft = new Device(); draft.setName("Board device"); draft.setType("default");
        device = doPost("/api/device", draft, Device.class);
        Dashboard draftBoard = new Dashboard(); draftBoard.setTitle("Production board");
        draftBoard.setConfiguration(JacksonUtil.valueToTree(Map.of("widgets", Map.of("wall", Map.of(
                "typeFullFqn", "system.production_wallboard", "config", Map.of("settings", Map.of(
                        "configVersion", 1, "title", "Production board", "source", Map.of("mode", "ALL_VISIBLE"))))))));
        board = doPost("/api/dashboard", draftBoard, Dashboard.class);
    }
    private String api() { return "/api/production/boards/" + board.getId() + "/snapshot"; }
    private EntityGroup group(EntityType type, String id) throws Exception {
        EntityGroup group = new EntityGroup(); group.setName("Board " + type); group.setType(type);
        group = doPost("/api/entityGroup", group, EntityGroup.class);
        doPost("/api/entityGroup/" + group.getId() + "/addEntities", List.of(id)).andExpect(status().isOk());
        return group;
    }
    private void publicLogin(String id) throws Exception {
        resetTokens();
        JsonNode tokens = doPost("/api/auth/login/public", Map.of("publicId", id), JsonNode.class);
        token = tokens.get("token").asText();
    }
    @Test public void tenantSnapshotReadsNativePermissionsAndHidesImplementationDetails() throws Exception {
        var result = doPost(api(), all, Snapshot.class);
        assertTrue(result.deviceChoices().stream().anyMatch(d -> d.id().equals(device.getId().getId())));
        doPost(api(), all).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.key").doesNotExist()).andExpect(jsonPath("$.publisher").doesNotExist());
        doPost(api(), new Selection("arbitrary", "ALL", null, null, 0)).andExpect(status().isBadRequest());
    }
    @Test public void publicDashboardAloneDoesNotExposePrivateDevicesAndGroupRevocationApplies() throws Exception {
        var dashboardGroup = group(EntityType.DASHBOARD, board.getId().toString());
        var deviceGroup = group(EntityType.DEVICE, device.getId().toString());
        doPost("/api/entityGroup/" + dashboardGroup.getId() + "/makePublic").andExpect(status().isOk());
        dashboardGroup = doGet("/api/entityGroup/" + dashboardGroup.getId(), EntityGroup.class);
        String publicId = dashboardGroup.getAdditionalInfo().get("publicCustomerId").asText();
        publicLogin(publicId);
        assertTrue(doPost(api(), all, Snapshot.class).deviceChoices().isEmpty());
        loginTenantAdmin();
        doPost("/api/entityGroup/" + deviceGroup.getId() + "/makePublic").andExpect(status().isOk());
        publicLogin(publicId);
        var visible = doPost(api(), all, Snapshot.class);
        assertEquals(List.of(device.getId().getId()), visible.deviceChoices().stream().map(DeviceChoice::id).toList());
        String existingPublicToken = token;
        loginTenantAdmin();
        doPost("/api/entityGroup/" + deviceGroup.getId() + "/makePrivate").andExpect(status().isOk());
        token = existingPublicToken;
        assertTrue(doPost(api(), all, Snapshot.class).deviceChoices().isEmpty());
        loginTenantAdmin();
        doPost("/api/entityGroup/" + dashboardGroup.getId() + "/makePrivate").andExpect(status().isOk());
        publicLogin(publicId);
        doPost(api(), all).andExpect(status().isForbidden());
    }
    @Test public void nativeCustomerRoleNeedsBothAttributeAndTelemetryRead() throws Exception {
        Device owned = new Device(); owned.setName("Customer board device"); owned.setType("default"); owned.setCustomerId(customerId);
        device = doPost("/api/device", owned, Device.class);
        var userGroup = new EntityGroup(); userGroup.setName("Board viewers"); userGroup.setType(EntityType.USER); userGroup.setOwnerId(customerId);
        userGroup = doPost("/api/entityGroup", userGroup, EntityGroup.class);
        doPost("/api/entityGroup/" + userGroup.getId() + "/addEntities", List.of(customerUserId.getId().toString()));
        var deviceGroup = findGroupByOwnerIdTypeAndName(customerId, EntityType.DEVICE, EntityGroup.GROUP_ALL_NAME);
        var dashboardGroup = group(EntityType.DASHBOARD, board.getId().toString());
        var readRole = createGroupRole(customerId, "Read entities", List.of(Operation.READ));
        createGroupPermission(userGroup.getId(), readRole.getId(), deviceGroup.getId(), EntityType.DEVICE);
        createGroupPermission(userGroup.getId(), readRole.getId(), dashboardGroup.getId(), EntityType.DASHBOARD);
        loginCustomerUser();
        assertTrue(doPost(api(), all, Snapshot.class).deviceChoices().isEmpty());
        loginTenantAdmin();
        var attrRole = createGroupRole(customerId, "Read attributes", List.of(Operation.READ_ATTRIBUTES));
        createGroupPermission(userGroup.getId(), attrRole.getId(), deviceGroup.getId(), EntityType.DEVICE);
        loginCustomerUser();
        assertTrue(doPost(api(), all, Snapshot.class).deviceChoices().isEmpty());
        loginTenantAdmin();
        var tsRole = createGroupRole(customerId, "Read telemetry", List.of(Operation.READ_TELEMETRY));
        createGroupPermission(userGroup.getId(), tsRole.getId(), deviceGroup.getId(), EntityType.DEVICE);
        loginCustomerUser();
        assertEquals(1, doPost(api(), all, Snapshot.class).deviceChoices().size());
    }
    @Test public void overlappingNativeDeviceGroupsAreDeduplicatedAndSettingsAreReadFromTheDashboard() throws Exception {
        EntityGroup first = group(EntityType.DEVICE, device.getId().toString());
        EntityGroup second = new EntityGroup(); second.setName("Second production group"); second.setType(EntityType.DEVICE);
        second = doPost("/api/entityGroup", second, EntityGroup.class);
        doPost("/api/entityGroup/" + second.getId() + "/addEntities", List.of(device.getId().toString()));
        var settings = (com.fasterxml.jackson.databind.node.ObjectNode) board.getConfiguration().at("/widgets/wall/config/settings");
        settings.set("source", JacksonUtil.valueToTree(Map.of("mode", "DEVICE_GROUPS", "groupIds", List.of(first.getId().toString(), second.getId().toString()))));
        doPost("/api/dashboard", board, Dashboard.class);
        var result = doPost(api(), all, Snapshot.class);
        assertEquals(1, result.deviceChoices().size());
        assertEquals(1, result.types().getFirst().deviceCount());
        doPost(api(), new Selection("wall", "DEVICE", null, java.util.UUID.randomUUID(), 0)).andExpect(status().isBadRequest());
    }
    @Test public void systemWidgetBootstrapIsIdempotentAndKeepsExistingWidgetEdits() throws Exception {
        var before = boardWidgetTypes.findWidgetTypeDetailsByTenantIdAndFqn(TenantId.SYS_TENANT_ID, "production_wallboard");
        assertNotNull(before);
        String name = before.getName();
        before.setName("Existing production widget");
        var saved = boardWidgetTypes.saveWidgetType(before);
        try {
            installer.install(); installer.install();
            var after = boardWidgetTypes.findWidgetTypeDetailsByTenantIdAndFqn(TenantId.SYS_TENANT_ID, "production_wallboard");
            assertEquals(saved.getId(), after.getId()); assertEquals("Existing production widget", after.getName());
        } finally { saved.setName(name); boardWidgetTypes.saveWidgetType(saved); }
    }
    @Test public void catalogFiltersByWidgetRatherThanTitleAndPaginatesAfterFiltering() throws Exception {
        Dashboard ordinary = new Dashboard(); ordinary.setTitle("Production dashboard without production widget");
        doPost("/api/dashboard", ordinary, Dashboard.class);
        Dashboard another = new Dashboard(); another.setTitle("Another production screen"); another.setConfiguration(board.getConfiguration());
        another = doPost("/api/dashboard", another, Dashboard.class);
        JsonNode first = doGet("/api/production/boards?pageSize=1&page=0", JsonNode.class);
        JsonNode second = doGet("/api/production/boards?pageSize=1&page=1", JsonNode.class);
        assertEquals(2, first.get("totalElements").asInt());
        assertEquals(1, first.get("data").size()); assertTrue(first.get("hasNext").asBoolean());
        assertNotEquals(first.at("/data/0/id").asText(), second.at("/data/0/id").asText());
        assertTrue(first.at("/data/0/canEdit").asBoolean());
        JsonNode searched = doGet("/api/production/boards?pageSize=20&page=0&textSearch=Another", JsonNode.class);
        assertEquals(1, searched.get("totalElements").asInt());
        assertEquals(another.getId().toString(), searched.at("/data/0/id").asText());
        doGet("/api/production/boards?pageSize=0").andExpect(status().isBadRequest());
        doGet("/api/production/boards?page=-1").andExpect(status().isBadRequest());
        doGet("/api/production/boards").andExpect(header().string("Cache-Control", "no-store"));
    }
    @Test public void catalogUsesNativeSharedReadAndScopedWriteWithoutGrantingDeviceAccess() throws Exception {
        EntityGroup dashboardGroup = group(EntityType.DASHBOARD, board.getId().toString());
        loginCustomerUser();
        assertEquals(0, doGet("/api/production/boards", JsonNode.class).get("totalElements").asInt());
        loginTenantAdmin();
        EntityGroup viewers = new EntityGroup(); viewers.setName("Production screen viewers"); viewers.setType(EntityType.USER); viewers.setOwnerId(customerId);
        viewers = doPost("/api/entityGroup", viewers, EntityGroup.class);
        doPost("/api/entityGroup/" + viewers.getId() + "/addEntities", List.of(customerUserId.getId().toString()));
        var readRole = createGroupRole(customerId, "Production screen read", List.of(Operation.READ));
        createGroupPermission(viewers.getId(), readRole.getId(), dashboardGroup.getId(), EntityType.DASHBOARD);
        loginCustomerUser();
        JsonNode readOnly = doGet("/api/production/boards", JsonNode.class);
        assertEquals(1, readOnly.get("totalElements").asInt());
        assertFalse(readOnly.at("/data/0/canEdit").asBoolean());
        assertTrue(doPost(api(), all, Snapshot.class).deviceChoices().isEmpty());
        loginTenantAdmin();
        var writeRole = createGroupRole(customerId, "Production screen write", List.of(Operation.WRITE));
        createGroupPermission(viewers.getId(), writeRole.getId(), dashboardGroup.getId(), EntityType.DASHBOARD);
        loginCustomerUser();
        JsonNode writable = doGet("/api/production/boards", JsonNode.class);
        assertTrue(writable.at("/data/0/canEdit").asBoolean());
        assertEquals(dashboardGroup.getId().toString(), writable.at("/data/0/editGroupId").asText());
        loginDifferentTenant();
        assertEquals(0, doGet("/api/production/boards", JsonNode.class).get("totalElements").asInt());
    }
    @Test public void anotherTenantAndUnassignedCustomerCannotReadTheDashboard() throws Exception {
        loginCustomerUser(); doPost(api(), all).andExpect(status().isForbidden());
        loginDifferentTenant(); doPost(api(), all).andExpect(status().isForbidden());
    }
}
