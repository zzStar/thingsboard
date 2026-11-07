// SPDX-FileCopyrightText: Copyright The ThingsBoard Authors
// SPDX-License-Identifier: Apache-2.0
package org.thingsboard.server.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.thingsboard.server.queue.util.TbCoreComponent;
import org.thingsboard.server.service.production.ProductionBoardService;
import org.thingsboard.server.service.production.ProductionBoardCatalogService;
import org.thingsboard.server.common.data.page.PageData;

import java.util.UUID;
import static org.thingsboard.server.service.production.ProductionBoardModels.*;

@RestController
@TbCoreComponent
@RequiredArgsConstructor
@RequestMapping("/api/production/boards")
@PreAuthorize("hasAnyAuthority('TENANT_ADMIN', 'CUSTOMER_USER')")
public class ProductionBoardController extends BaseController {
    private final ProductionBoardService boards;

    private final ProductionBoardCatalogService catalog;

    @GetMapping
    public ResponseEntity<PageData<BoardEntry>> list(@RequestParam(defaultValue = "20") int pageSize,
            @RequestParam(defaultValue = "0") int page, @RequestParam(required = false) String textSearch) throws Exception {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(catalog.list(getCurrentUser(), pageSize, page, textSearch));
    }

    @PostMapping("/{dashboardId}/snapshot")
    public ResponseEntity<Snapshot> snapshot(@PathVariable UUID dashboardId, @RequestBody Selection selection) throws Exception {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(boards.preview(getCurrentUser(), dashboardId, selection));
    }
}
