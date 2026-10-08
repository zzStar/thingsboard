#!/usr/bin/env bash
#
# SPDX-FileCopyrightText: Copyright ThingsBoard, Inc.
# SPDX-License-Identifier: BUSL-1.1
#

set -euo pipefail
unset NON_PRODUCTION_USE
COMMON=(-Dlogging.config=/app/logback.xml -Dinstall.data_dir=/app/data -Dloader.path=/app/extensions)
case "${1:-server}" in
  server) MAIN=org.thingsboard.server.ThingsboardServerApplication; EXTRA=() ;;
  install) MAIN=org.thingsboard.server.ThingsboardInstallApplication; EXTRA=(-Dinstall.upgrade=false) ;;
  upgrade)
    : "${FROM_VERSION:?upgrade requires FROM_VERSION}"
    MAIN=org.thingsboard.server.ThingsboardInstallApplication
    EXTRA=(-Dinstall.upgrade=true "-Dinstall.upgrade.from_version=$FROM_VERSION") ;;
  *) echo 'Expected server, install, or upgrade' >&2; exit 2 ;;
esac
exec java "${COMMON[@]}" "${EXTRA[@]}" "-Dloader.main=$MAIN" \
  -cp /app/thingsboard.jar org.springframework.boot.loader.launch.PropertiesLauncher
