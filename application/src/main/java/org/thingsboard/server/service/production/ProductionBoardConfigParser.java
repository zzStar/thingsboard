// SPDX-FileCopyrightText: Copyright The ThingsBoard Authors
// SPDX-License-Identifier: Apache-2.0
package org.thingsboard.server.service.production;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;
import org.thingsboard.common.util.JacksonUtil;
import org.thingsboard.server.common.data.Dashboard;

import java.util.List;
import java.util.Set;

import static org.thingsboard.server.service.production.ProductionBoardModels.*;

@Component
public class ProductionBoardConfigParser {
    public static final String WIDGET_FQN = "system.production_wallboard";
    public static final int MAX_DEVICES = 300;

    public BoardConfig parse(Dashboard dashboard, String widgetId) {
        if (widgetId == null || widgetId.length() > 100) throw new IllegalArgumentException("请选择产量大屏组件");
        JsonNode widgets = dashboard.getConfiguration() == null ? null : dashboard.getConfiguration().get("widgets");
        JsonNode widget = widgets == null ? null : widgets.get(widgetId);
        if (widget == null || !WIDGET_FQN.equals(widget.path("typeFullFqn").asText())) {
            throw new IllegalArgumentException("请选择系统设备产量大屏组件");
        }
        return validate(JacksonUtil.treeToValue(widget.path("config").path("settings"), BoardConfig.class));
    }

    public BoardConfig validate(BoardConfig config) {
        if (config == null || config.configVersion() != 1) throw new IllegalArgumentException("不支持的产量大屏配置版本");
        String title = config.title() == null ? "设备产量" : config.title().strip();
        if (title.isEmpty() || title.length() > 100) throw new IllegalArgumentException("大屏标题须为1至100个字符");
        Source source = config.source();
        if (source == null || source.mode() == null || !Set.of("ALL_VISIBLE", "DEVICE_GROUPS", "EXPLICIT_DEVICES").contains(source.mode())) {
            throw new IllegalArgumentException("无效的设备范围");
        }
        List<java.util.UUID> groups = source.groupIds() == null ? List.of() : source.groupIds();
        List<java.util.UUID> devices = source.deviceIds() == null ? List.of() : source.deviceIds();
        if (groups.stream().anyMatch(java.util.Objects::isNull) || devices.stream().anyMatch(java.util.Objects::isNull)
                || groups.size() > 20 || devices.size() > MAX_DEVICES
                || "DEVICE_GROUPS".equals(source.mode()) && groups.isEmpty()
                || "EXPLICIT_DEVICES".equals(source.mode()) && devices.isEmpty()) {
            throw new IllegalArgumentException("请选择有效设备或设备组，每个大屏最多300台设备、20个组");
        }
        String attribute = config.typeAttribute() == null ? "productionType" : config.typeAttribute();
        if (!attribute.matches("[A-Za-z][A-Za-z0-9_]{0,63}")) throw new IllegalArgumentException("无效的设备分类属性键");
        List<String> order = config.typeOrder() == null ? List.of() : config.typeOrder();
        if (order.size() > 100 || order.stream().anyMatch(s -> s == null || s.isBlank() || s.length() > 64)) {
            throw new IllegalArgumentException("类型排序最多100项，类型名称最多64个字符");
        }
        Display display = config.display() == null ? new Display("auto", 3, 7, 16, 20, 5) : config.display();
        if (display.theme() == null || !Set.of("auto", "light", "dark").contains(display.theme())
                || display.typesPerFrame() < 1 || display.typesPerFrame() > 3
                || display.devicesPerCard() < 1 || display.devicesPerCard() > 7
                || display.detailRowBudget() < 6 || display.detailRowBudget() > 16
                || display.rotationSeconds() < 10 || display.rotationSeconds() > 300
                || display.refreshSeconds() < 5 || display.refreshSeconds() > 60) {
            throw new IllegalArgumentException("无效的大屏容量或刷新间隔");
        }
        return new BoardConfig(1, title, new Source(source.mode(), List.copyOf(groups), List.copyOf(devices)),
                attribute, List.copyOf(order), display);
    }
}
