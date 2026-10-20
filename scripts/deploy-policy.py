#!/usr/bin/env python3
#
# SPDX-FileCopyrightText: Copyright ThingsBoard, Inc.
# SPDX-License-Identifier: BUSL-1.1
#

"""Validate release compatibility and compare infrastructure without printing secrets."""
import hashlib
import json
import re
import sys
from pathlib import Path


def policy(path):
    data = json.loads(Path(path).read_text())
    if data.get("format") != 1 or not re.fullmatch(r"[a-zA-Z0-9_.-]+", data.get("compatibility", "")):
        raise ValueError("Invalid release compatibility policy")
    for digest in [data["production_schema_sha256"], *data["readable_production_schemas"]]:
        if not re.fullmatch(r"[a-f0-9]{64}", digest):
            raise ValueError("Invalid production schema fingerprint")
    if not isinstance(data.get("production_schema_version"), int) or data["production_schema_version"] < 1:
        raise ValueError("Invalid production schema version")
    return data


def main():
    action = sys.argv[1]
    if action == "validate":
        data = policy(sys.argv[2])
        actual = hashlib.sha256(Path(sys.argv[3]).read_bytes()).hexdigest()
        if actual != data["production_schema_sha256"]:
            raise ValueError("production-schema.sql changed: review compatibility and update release-policy.json")
        print(data["compatibility"])
    elif action == "compatible":
        data = policy(sys.argv[2])
        old = json.load(sys.stdin)[0]
        labels = old["Config"].get("Labels") or {}
        known = sys.argv[3].split("@")[-1] in data.get("compatible_legacy_digests", [])
        known = known or (labels.get("com.zhigong.compatibility") in data["readable_compatibility"]
                          and labels.get("com.zhigong.production-schema") in data["readable_production_schemas"])
        print("compatible" if known else "unknown")
    elif action == "infrastructure":
        old, new = [json.loads(Path(path).read_text())["services"] for path in sys.argv[2:4]]
        # Caddy's stable directory mount is an intentional one-time proxy transition.
        # PostgreSQL must be exactly unchanged during an application release.
        if old["postgres"] != new["postgres"]:
            raise ValueError("PostgreSQL configuration differs; use a separate infrastructure maintenance procedure")
        for key in ("image", "ports", "mem_limit", "environment", "command"):
            if old["caddy"].get(key) != new["caddy"].get(key):
                raise ValueError("Caddy " + key + " differs; use a separate infrastructure maintenance procedure")
        for key in ("SPRING_DATASOURCE_URL", "SPRING_DATASOURCE_USERNAME", "SPRING_DATASOURCE_PASSWORD"):
            if old["thingsboard"]["environment"].get(key) != new["thingsboard"]["environment"].get(key):
                raise ValueError("Application datasource differs; use a separate database migration procedure")
        for key in ("volumes", "ports", "platform"):
            if old["thingsboard"].get(key) != new["thingsboard"].get(key):
                raise ValueError("Application " + key + " differs; use a separate infrastructure maintenance procedure")
        print("Infrastructure verified")
    elif action == "database":
        data = policy(sys.argv[2])
        version = int(sys.argv[3])
        if version not in data["readable_production_versions"]:
            raise ValueError("Production database schema is incompatible with the target image")
        print("Production schema verified: " + str(version))
    elif action == "state":
        data = json.load(sys.stdin)
        print(data if isinstance(data, str) else data.get("status", data.get("state", "")))
    else:
        raise ValueError("Unknown policy action")


if __name__ == "__main__":
    try:
        main()
    except (ValueError, KeyError, IndexError, OSError) as error:
        sys.exit(str(error))
