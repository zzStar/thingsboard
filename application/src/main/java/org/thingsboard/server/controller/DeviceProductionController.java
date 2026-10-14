// SPDX-FileCopyrightText: Copyright The ThingsBoard Authors
// SPDX-License-Identifier: Apache-2.0
package org.thingsboard.server.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.thingsboard.server.common.data.Device;
import org.thingsboard.server.common.data.exception.ThingsboardException;
import org.thingsboard.server.common.data.id.DeviceId;
import org.thingsboard.server.common.data.kv.TsKvEntry;
import org.thingsboard.server.common.data.permission.Operation;
import org.thingsboard.server.dao.timeseries.TimeseriesService;
import org.thingsboard.server.queue.util.TbCoreComponent;
import org.thingsboard.server.service.production.DeviceProductionService;
import org.thingsboard.server.service.production.ProductionBackfillService;
import org.thingsboard.server.service.production.ProductionScreenService;
import org.thingsboard.server.service.production.ProductionSpeedQuery;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.thingsboard.server.service.production.ProductionModels.*;
import static org.thingsboard.server.service.production.ProductionCalculator.MAX_RANGE;

@RestController
@TbCoreComponent
@RequestMapping("/api/production/{deviceId}")
@RequiredArgsConstructor
public class DeviceProductionController extends BaseController {
    private final DeviceProductionService production;
    private final TimeseriesService timeseries;
    private final ProductionBackfillService backfills;
    private final ProductionScreenService screens;
    @Value("${database.ts_max_intervals:700}")
    private long maxTsIntervals = 700;

    @GetMapping("/screen-link")
    @PreAuthorize("hasAuthority('TENANT_ADMIN')")
    public ScreenLink screenLink(@PathVariable String deviceId) throws ThingsboardException {
        Device device = screenDevice(deviceId);
        return screens.link(device.getTenantId(), device.getId());
    }

    @PostMapping("/screen-link")
    @PreAuthorize("hasAuthority('TENANT_ADMIN')")
    public ScreenLink createScreenLink(@PathVariable String deviceId, @RequestParam(defaultValue = "false") boolean rotate)
            throws ThingsboardException {
        Device device = screenDevice(deviceId);
        return screens.create(device.getTenantId(), device.getId(), rotate);
    }

    @DeleteMapping("/screen-link")
    @PreAuthorize("hasAuthority('TENANT_ADMIN')")
    public void revokeScreenLink(@PathVariable String deviceId) throws ThingsboardException {
        Device device = screenDevice(deviceId);
        screens.revoke(device.getTenantId(), device.getId());
    }

    private Device screenDevice(String deviceId) throws ThingsboardException {
        Device device = device(deviceId, Operation.READ_TELEMETRY);
        checkDeviceId(device.getId(), Operation.WRITE_ATTRIBUTES);
        return device;
    }

    @GetMapping("/backfill")
    @PreAuthorize("hasAuthority('TENANT_ADMIN')")
    public Backfill backfill(@PathVariable String deviceId) throws ThingsboardException {
        Device device = device(deviceId, Operation.READ_TELEMETRY);
        return backfills.get(device.getId());
    }

    @PostMapping("/backfill")
    @PreAuthorize("hasAuthority('TENANT_ADMIN')")
    public Backfill startBackfill(@PathVariable String deviceId, @RequestBody BackfillRequest request) throws ThingsboardException {
        Device device = device(deviceId, Operation.WRITE_TELEMETRY);
        return backfills.start(device.getTenantId(), device.getId(), request);
    }

    private Device device(String id, Operation operation) throws ThingsboardException {
        return checkDeviceId(new DeviceId(UUID.fromString(id)), operation);
    }

    @GetMapping("/settings")
    @PreAuthorize("hasAnyAuthority('TENANT_ADMIN', 'CUSTOMER_USER')")
    public Settings settings(@PathVariable String deviceId) throws ThingsboardException {
        Device device = device(deviceId, Operation.READ_TELEMETRY);
        return production.settings(device.getTenantId(), device.getId());
    }

    @PostMapping("/settings")
    @PreAuthorize("hasAuthority('TENANT_ADMIN')")
    public Settings saveSettings(@PathVariable String deviceId, @RequestBody SaveSettings settings) throws ThingsboardException {
        Device device = device(deviceId, Operation.WRITE_ATTRIBUTES);
        return production.saveSettings(device.getTenantId(), device.getId(), settings);
    }

    @GetMapping("/overview")
    @PreAuthorize("hasAnyAuthority('TENANT_ADMIN', 'CUSTOMER_USER')")
    public Overview overview(@PathVariable String deviceId) throws ThingsboardException {
        Device device = device(deviceId, Operation.READ_TELEMETRY);
        return production.overview(device.getTenantId(), device.getId());
    }

    @GetMapping("/report")
    @PreAuthorize("hasAnyAuthority('TENANT_ADMIN', 'CUSTOMER_USER')")
    public Report report(@PathVariable String deviceId, @RequestParam long startTs, @RequestParam long endTs,
                         @RequestParam(defaultValue = "day") String groupBy,
                         @RequestParam(required = false) Integer shift) throws ThingsboardException {
        Device device = device(deviceId, Operation.READ_TELEMETRY);
        return production.report(device.getTenantId(), device.getId(), startTs, endTs, groupBy, shift);
    }

    @GetMapping("/speed")
    @PreAuthorize("hasAnyAuthority('TENANT_ADMIN', 'CUSTOMER_USER')")
    public List<TsKvEntry> speed(@PathVariable String deviceId, @RequestParam long startTs, @RequestParam long endTs) throws Exception {
        Device device = device(deviceId, Operation.READ_TELEMETRY);
        if (startTs < 0 || endTs <= startTs || endTs - startTs > MAX_RANGE) throw new IllegalArgumentException("无效的速度查询范围");
        return timeseries.findAll(device.getTenantId(), device.getId(), List.of(
                ProductionSpeedQuery.create(startTs, endTs, maxTsIntervals))).get(30, TimeUnit.SECONDS);
    }
}
