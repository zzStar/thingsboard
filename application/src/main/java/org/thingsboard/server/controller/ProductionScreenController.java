// SPDX-FileCopyrightText: Copyright The ThingsBoard Authors
// SPDX-License-Identifier: Apache-2.0
package org.thingsboard.server.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.thingsboard.server.common.data.id.DeviceId;
import org.thingsboard.server.queue.util.TbCoreComponent;
import org.thingsboard.server.service.production.ProductionModels.ScreenView;
import org.thingsboard.server.service.production.ProductionScreenService;

import java.util.UUID;

@RestController
@TbCoreComponent
@RequestMapping("/api/noauth/production/{deviceId}/screen")
@RequiredArgsConstructor
public class ProductionScreenController {
    private final ProductionScreenService screens;

    @GetMapping
    public ResponseEntity<ScreenView> view(@PathVariable UUID deviceId,
                                         @RequestHeader(value = "X-Production-Screen-Key", required = false) String key) throws Exception {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(screens.view(new DeviceId(deviceId), key));
    }
}
