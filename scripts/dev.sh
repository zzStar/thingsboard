#!/usr/bin/env bash
#
# SPDX-FileCopyrightText: Copyright ThingsBoard, Inc.
# SPDX-License-Identifier: BUSL-1.1
#

set -euo pipefail

ROOT=$(cd "$(dirname "$0")/.." && pwd)
cd "$ROOT"
STATE="$ROOT/.dev"
mkdir -p "$STATE"
COMPOSE=(docker compose -f "$ROOT/scripts/dev-compose.yml")
BACKEND_PID=
FRONTEND_PID=
LOCKED=false

fail() { echo "错误：$*" >&2; exit 1; }
cleanup() {
    local result=$?
    trap - EXIT INT TERM
    for pid in "$FRONTEND_PID" "$BACKEND_PID"; do
        if [[ -n "$pid" ]]; then kill "$pid" 2>/dev/null || true; fi
    done
    for pid in "$FRONTEND_PID" "$BACKEND_PID"; do
        if [[ -n "$pid" ]]; then wait "$pid" 2>/dev/null || true; fi
    done
    if [[ "$LOCKED" == true ]]; then rmdir "$STATE/lock"; fi
    exit "$result"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

if ! mkdir "$STATE/lock" 2>/dev/null; then
    fail '已有 make dev 在运行。如果上次进程被强制终止，请确认它已退出，再删除 .dev/lock。'
fi
LOCKED=true

# macOS 上优先选择已安装的 JDK 25，不修改用户的 shell 配置。
if [[ -x /usr/libexec/java_home ]]; then
    JAVA_HOME=$(/usr/libexec/java_home -v 25) || fail '请先安装 JDK 25。'
    export JAVA_HOME
    export PATH="$JAVA_HOME/bin:$PATH"
fi
for command in java mvn docker curl lsof; do
    command -v "$command" >/dev/null || fail "缺少命令：$command"
done
JAVA_VERSION=$(java -version 2>&1)
[[ "$JAVA_VERSION" == *'version "25.'* ]] || fail '需要 JDK 25；请检查 JAVA_HOME。'
MAVEN_VERSION=$(mvn -version)
[[ "$MAVEN_VERSION" == *'Java version: 25.'* ]] || fail 'Maven 未使用 JDK 25。'
docker info >/dev/null 2>&1 || fail 'Docker 未运行，请先启动 Docker Desktop / Docker daemon。'
docker compose version >/dev/null || fail '需要 Docker Compose v2。'

export TB_DEV_DB_PORT=${TB_DEV_DB_PORT:-15432}
export TB_DEV_HTTP_PORT=${TB_DEV_HTTP_PORT:-8080}
export HTTP_BIND_PORT=$TB_DEV_HTTP_PORT
UI_PORT=${TB_DEV_UI_PORT:-4200}
for port in "$TB_DEV_DB_PORT" "$HTTP_BIND_PORT" "$UI_PORT"; do
    [[ "$port" =~ ^[0-9]+$ ]] && ((port > 0 && port < 65536)) || fail "无效端口：$port"
done
for port in "$HTTP_BIND_PORT" "$UI_PORT"; do
    if lsof -nP -iTCP:"$port" -sTCP:LISTEN >/dev/null 2>&1; then
        fail "端口 $port 已被占用，请先停止对应服务。"
    fi
done

export SPRING_DATASOURCE_URL="jdbc:postgresql://localhost:$TB_DEV_DB_PORT/thingsboard_dev"
export SPRING_DATASOURCE_USERNAME=postgres
export SPRING_DATASOURCE_PASSWORD=postgres
export DATABASE_TS_TYPE=sql DATABASE_TS_LATEST_TYPE=sql
export TB_QUEUE_TYPE=in-memory JS_EVALUATOR=local
# 使用本分支的本地订阅策略，不继承终端残留的非生产模式设置。
unset NON_PRODUCTION_USE
export HTTP_BIND_ADDRESS=127.0.0.1 MQTT_BIND_ADDRESS=127.0.0.1
export COAP_BIND_ADDRESS=127.0.0.1 COAP_DTLS_BIND_ADDRESS=127.0.0.1
export LWM2M_BIND_ADDRESS=127.0.0.1 LWM2M_SECURITY_BIND_ADDRESS=127.0.0.1
export LWM2M_BS_BIND_ADDRESS=127.0.0.1 LWM2M_BS_SECURITY_BIND_ADDRESS=127.0.0.1
export SNMP_BIND_ADDRESS=127.0.0.1
export TB_LICENSE_INSTANCE_DATA_FILE="$STATE/instance-license.data"

echo '启动专用开发 PostgreSQL（保留已有数据）…'
"${COMPOSE[@]}" up -d --wait --wait-timeout 120 postgres

echo '增量构建后端和前端依赖；首次运行会下载 Maven、Node、Yarn 依赖…'
echo "Yarn 请求详情和构建日志：$STATE/build.log"
mvn install -pl application -am -DskipTests \
    -Dpkg.skip.deb=true -Dpkg.skip.rpm=true -Dpkg.skip.zip=true \
    -Dpkg.gradle.phase=none \
    -Dskip.ui.production.build=true \
    '-Dyarn.install.extra.args=--verbose --prefer-offline' \
    2>&1 | tee "$STATE/build.log"

JAR=$(find "$ROOT/application/target" -maxdepth 1 -name 'thingsboard-*-boot.jar' -print)
[[ -f "$JAR" ]] || fail '找不到唯一 boot jar，请检查 application/target（可手动 clean 后重试）。'
NODE="$ROOT/ui-ngx/target/node/node"
[[ -x "$NODE" ]] || fail 'Maven 未生成前端 Node runtime。'
export PATH="$(dirname "$NODE"):$PATH"

TABLE_COUNT=$("${COMPOSE[@]}" exec -T postgres psql -U postgres -d thingsboard_dev -Atc \
    "SELECT count(*) FROM information_schema.tables WHERE table_schema='public';")
if [[ "$TABLE_COUNT" == 0 ]]; then
    echo '首次初始化开发数据库…'
    [[ -f "$ROOT/application/target/data/sql/schema-entities.sql" ]] || fail '缺少打包后的数据库 SQL：application/target/data/sql/schema-entities.sql。请检查 Maven 构建结果。'
    java -Dlogging.config="$ROOT/application/src/main/resources/logback.xml" \
        -Dloader.main=org.thingsboard.server.ThingsboardInstallApplication \
        -Dinstall.data_dir="$ROOT/application/target/data" -Dinstall.upgrade=false \
        -cp "$JAR" org.springframework.boot.loader.launch.PropertiesLauncher \
        >"$STATE/install.log" 2>&1 || { tail -n 60 "$STATE/install.log"; fail '数据库初始化失败，见 .dev/install.log。'; }
else
    SCHEMA_READY=$("${COMPOSE[@]}" exec -T postgres psql -U postgres -d thingsboard_dev -Atc \
        "SELECT to_regclass('public.tb_schema_settings') IS NOT NULL;")
    [[ "$SCHEMA_READY" == t ]] || fail '数据库存在表但缺少 tb_schema_settings，可能初始化未完成。请检查 .dev/install.log；脚本不会覆盖已有数据。'
    echo '数据库已有 schema，跳过初始化；版本升级需按官方升级流程单独执行。'
fi

echo "启动后端，日志：$STATE/backend.log"
java -Dlogging.config="$ROOT/application/src/main/resources/logback.xml" \
    -Dinstall.data_dir="$ROOT/application/target/data" -jar "$JAR" \
    >"$STATE/backend.log" 2>&1 &
BACKEND_PID=$!
wait_http() {
    local pid=$1 url=$2 log=$3 timeout=$4 started=$SECONDS
    while ((SECONDS - started < timeout)); do
        if ! kill -0 "$pid" 2>/dev/null; then
            tail -n 60 "$log"
            fail "服务已退出，查看 $log"
        fi
        # 本机健康检查直连，避免用户代理将 localhost 请求转发到外部。
        if curl --noproxy '*' --fail --silent --output /dev/null --max-time 2 "$url"; then return; fi
        sleep 2
    done
    tail -n 60 "$log"
    fail "等待服务超时：${url}，查看 $log"
}
wait_http "$BACKEND_PID" "http://127.0.0.1:$HTTP_BIND_PORT/api/noauth/setup/state" "$STATE/backend.log" 300

echo "启动 Angular 热更新预览，日志：$STATE/frontend.log"
(
    cd "$ROOT/ui-ngx"
    exec "$NODE" --max_old_space_size=8192 ./node_modules/@angular/cli/bin/ng \
        serve --configuration development --host 127.0.0.1 --port "$UI_PORT"
) >"$STATE/frontend.log" 2>&1 &
FRONTEND_PID=$!
wait_http "$FRONTEND_PID" "http://127.0.0.1:$UI_PORT/" "$STATE/frontend.log" 600
echo "开发环境就绪：http://localhost:${UI_PORT}（后端 http://localhost:${HTTP_BIND_PORT}）"
echo 'Ctrl+C 停止前后端；make dev-logs 查看日志；make dev-down 停止数据库。'
while kill -0 "$BACKEND_PID" 2>/dev/null && kill -0 "$FRONTEND_PID" 2>/dev/null; do sleep 2; done
fail '前端或后端已退出，请检查 .dev 下日志。'
