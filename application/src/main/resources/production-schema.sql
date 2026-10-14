--
-- SPDX-FileCopyrightText: Copyright The ThingsBoard Authors
-- SPDX-License-Identifier: Apache-2.0
--

CREATE TABLE IF NOT EXISTS production_device (
    device_id uuid PRIMARY KEY REFERENCES device(id) ON DELETE CASCADE,
    tenant_id uuid NOT NULL,
    revision bigint NOT NULL DEFAULT 0
);
CREATE TABLE IF NOT EXISTS production_screen_link (
    device_id uuid PRIMARY KEY REFERENCES device(id) ON DELETE CASCADE,
    tenant_id uuid NOT NULL,
    access_key varchar(43) NOT NULL,
    created_ts bigint NOT NULL
);
CREATE TABLE IF NOT EXISTS production_config (
    device_id uuid NOT NULL REFERENCES production_device(device_id) ON DELETE CASCADE,
    effective_ts bigint NOT NULL,
    config jsonb NOT NULL,
    PRIMARY KEY (device_id, effective_ts)
);
CREATE TABLE IF NOT EXISTS production_sample (
    device_id uuid NOT NULL REFERENCES production_device(device_id) ON DELETE CASCADE,
    ts bigint NOT NULL,
    counter bigint NOT NULL CHECK (counter >= 0),
    previous_ts bigint,
    delta bigint NOT NULL DEFAULT 0 CHECK (delta >= 0),
    reset boolean NOT NULL DEFAULT false,
    gap boolean NOT NULL DEFAULT false,
    PRIMARY KEY (device_id, ts)
);
CREATE INDEX IF NOT EXISTS production_sample_previous_idx ON production_sample(device_id, previous_ts);
CREATE TABLE IF NOT EXISTS production_minute (
    device_id uuid NOT NULL REFERENCES production_device(device_id) ON DELETE CASCADE,
    minute_ts bigint NOT NULL,
    quantity bigint NOT NULL DEFAULT 0,
    observed_ms bigint NOT NULL DEFAULT 0,
    estimated_ms bigint NOT NULL DEFAULT 0,
    resets bigint NOT NULL DEFAULT 0,
    PRIMARY KEY (device_id, minute_ts)
);
CREATE TABLE IF NOT EXISTS production_summary (
    device_id uuid NOT NULL REFERENCES production_device(device_id) ON DELETE CASCADE,
    config_ts bigint NOT NULL,
    production_date date NOT NULL,
    shift_index integer NOT NULL,
    start_ts bigint NOT NULL,
    end_ts bigint NOT NULL,
    quantity bigint NOT NULL DEFAULT 0,
    observed_ms bigint NOT NULL DEFAULT 0,
    estimated_ms bigint NOT NULL DEFAULT 0,
    resets bigint NOT NULL DEFAULT 0,
    PRIMARY KEY (device_id, config_ts, production_date, shift_index)
);
CREATE INDEX IF NOT EXISTS production_summary_range_idx ON production_summary(device_id, start_ts);
CREATE TABLE IF NOT EXISTS production_backfill (
    device_id uuid PRIMARY KEY REFERENCES production_device(device_id) ON DELETE CASCADE,
    job_id uuid NOT NULL,
    start_ts bigint NOT NULL,
    end_ts bigint NOT NULL,
    cursor_ts bigint NOT NULL,
    processed bigint NOT NULL DEFAULT 0,
    status varchar(16) NOT NULL,
    updated_ts bigint NOT NULL,
    error varchar(256)
);
