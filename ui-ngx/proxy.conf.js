// SPDX-FileCopyrightText: Copyright The ThingsBoard Authors
// SPDX-License-Identifier: Apache-2.0
const backendPort = process.env.TB_DEV_HTTP_PORT || "8080";
const forwardUrl = `http://localhost:${backendPort}`;
const wsForwardUrl = `ws://localhost:${backendPort}`;
const ruleNodeUiforwardUrl = forwardUrl;

const PROXY_CONFIG = {
  "/api": {
    "target": forwardUrl,
    "secure": false,
  },
  "/static/rulenode": {
    "target": ruleNodeUiforwardUrl,
    "secure": false,
  },
  "/static/widgets": {
    "target": forwardUrl,
    "secure": false,
  },
  "/oauth2": {
    "target": forwardUrl,
    "secure": false,
  },
  "/login/oauth2": {
    "target": forwardUrl,
    "secure": false,
  },
  "/api/ws": {
    "target": wsForwardUrl,
    "ws": true,
    "secure": false
  },
};

module.exports = PROXY_CONFIG;
