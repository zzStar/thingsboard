# 智工物联服务器部署方案

日期：2026-10-08。部署入口、镜像构建、独立空库 smoke test、备份和恢复脚本已实现。Bash 语法及 Compose 配置校验通过；尚未完成正式镜像构建、推送和服务器上线。此前 Docker Hub 拉取超时；最新用户构建已进入 Angular 生产构建，但因内存耗尽退出 137。Docker 构建现已限制 Node 堆 4 GiB、Angular workers=2、Maven 堆 1 GiB；完整重建尚待验证。

## 目标和已确认条件

- 目标服务器：root@47.100.121.136，SSH 免密已验证。
- 域名：things.zhigongshulian.com。
- 镜像仓库：registry.cn-hangzhou.aliyuncs.com/zgsl/things。
- 服务器：Ubuntu 24.04.5、x86_64、2 CPU、约 3.5 GiB 内存、73 GB 可用磁盘、无 swap。
- Docker 和 Compose 已安装，无现有业务容器，80/443 未占用。
- 本机为 arm64，运行镜像必须支持 linux/amd64。
- 本分支默认使用 LocalSubscriptionService，无须外部许可证激活，不设置 NON_PRODUCTION_USE；不提供外部 AI 服务额度。
- 默认创建新的服务器数据库，不自动上传本地数据库。数据迁移作为单独操作。

## 部署拓扑与资源

使用 Docker Compose 单机部署，项目名称固定为 zhigong-things，目录 /opt/zhigong-things。

```text
浏览器 → things.zhigongshulian.com:443 → Caddy → thingsboard:8080
设备   → 需要启用的设备协议端口           → ThingsBoard
                                            ↓
                                      PostgreSQL 18
```

基础服务为 Caddy、ThingsBoard、PostgreSQL。Caddy 自动申请/续期 HTTPS，转发 API 和 WebSocket。固定外部协议为 HTTPS，使生成的链接和重定向正确。

受当前内存限制，首版使用应用内置 in-memory 队列、本地 JS evaluator，不启动 Kafka、Trendz 和独立报表服务。内存队列在重启时可能丢失待处理消息；需要可靠积压和更多设备时，扩容后切换 Kafka。正式设备接入前评估该取舍。

初始资源预算待压测确认：应用 Java 堆上限约 1.5 GiB、容器内存上限约 2.25 GiB，PostgreSQL 约 512 MiB，Caddy 约 128 MiB，保留系统余量。容器上限不等于实际占用。2 GiB swap 可作为缓冲，需在明确执行服务器初始化时创建，不替代扩容。应用线程数量按 2 CPU 环境评估，避免大量线程原生内存使容器 OOM。

Web 只公开 80/443；8080 仅 Docker 内网或宿主机回环健康检查使用。数据库 5432 不公开。MQTT、CoAP、Edge、远程集成端口按功能启用，默认不全量开放。HTTP HTTPS 与 MQTT TLS 是独立配置，Caddy 的网站证书不会自动让 8883 生效。

## 正式镜像构建

使用项目自有多阶段 Dockerfile，独立于本地 make dev。构建阶段在 Linux 目标环境执行 Java 25/Maven 和生产 Angular 构建，运行阶段只保留 JRE 25、boot jar、完整安装数据、扩展和所需字体等运行依赖。

选择直接运行 boot jar，避免为了容器运行构建 deb/rpm、以及之前遇到的 packaging Gradle 依赖问题。复用现有 pkg.gradle.phase=none、pkg.skip.deb/rpm/zip，但不得设置 skip.ui.production.build=true 或跳过前端依赖安装。生产 UI 必须包含两处“智工物联”Logo和当前分支改动。

运行入口分为 server、install、upgrade 三种动作，分别运行 ThingsboardServerApplication 和 ThingsboardInstallApplication。安装目录包含 JSON 与 DAO SQL，使用外部 logback 配置，安装失败必须返回非零退出状态。容器运行使用专用非 root 用户，可写路径独立持久化。

Docker buildx 明确指定 linux/amd64。Apple Silicon 上仿真构建可能较慢，首版优先保证产物一致性；后续可使用独立 amd64 构建机或 CI，避免在当前低内存业务服务器上编译整个项目。Maven、Yarn 和构建层缓存持久化，阿里云 Maven 镜像用于构建容器时需显式配置，宿主机 ~/.m2/settings.xml 不会自动进入容器。

版本默认形如 20261008-165000-abcdef1；可显式指定。发布标签不覆盖，实际部署记录镜像 digest、源码提交、未提交变更标识、时间和数据库 schema 版本。依赖镜像验证后固定版本或 digest，不使用 latest。包含源码变更的镜像由完整构建产生，不直接使用官方应用镜像。

## 一键命令设计

在项目根目录执行。首次与更新均使用 `make deploy`；Docker Desktop 需运行，本机先用 `docker login registry.cn-hangzhou.aliyuncs.com` 登录有推送权限的账号。脚本读取本机 Docker credential helper，使用密码标准输入给服务器登录，不输出凭据。

| 命令 | 用途 |
| --- | --- |
| make deploy | 自动生成版本，检查、构建、推送并部署；首次与后续更新使用同一入口 |
| make deploy VERSION=20261008-01 | 指定新的发布版本完成同样流程 |
| make deploy VERSION=已有版本 BUILD=0 | 校验并部署仓库中已有不可变镜像，不重新构建 |
| make deploy-status | 查看服务器容器、健康状态与当前发布记录 |
| make deploy-logs | 查看服务器应用日志 |
| make rollback VERSION=上一版本 | 仅在数据库 schema 兼容时回退应用版本 |
| make deploy-check | 本地检查脚本语法及 Compose 配置 |
| make backup | 备份服务器数据库并生成校验信息 |

命令持有服务器部署锁，避免两个发布同时初始化、备份或更新。状态检查等只读命令不占部署锁。所有失败返回非零并打印阶段、日志位置及恢复指引；不把容器已创建当作部署成功。

### 首次部署流程

1. 本地预检 SSH、目标架构、仓库认证、Docker/buildx 和域名解析。服务器资源与 80/443 占用已人工检查；云安全组需放行 80/443。
2. 执行必要的代码检查，生产构建与镜像 smoke test，再推送版本标签，获取 digest。
3. 安全复制 Compose、Caddy 配置及远端入口脚本，建立独立目录；不覆盖服务器已有秘密文件。
4. 首次自动生成数据库强密码，服务器 .env 权限 600。仓库登录使用各机器现有 Docker 凭据或 --password-stdin，不输出密码、不将凭据写入镜像或提交到 Git。
5. 拉取镜像，启动 PostgreSQL 并等待 healthcheck。确认数据库为空后启动一次性安装容器；失败即停止，不自动删除或覆盖残留数据。
6. 启动应用，检查直连 /api/noauth/setup/state；未创建管理员时 ACCOUNT_REQUIRED 是预期状态，LICENSE_REQUIRED 是当前分支部署失败信号。
7. 启动 Caddy，验证可信 HTTPS、页面、API 及代理链路，输出域名与版本。首次管理员账号由用户在网页创建，脚本不设置通用默认管理员密码。

### 更新流程

1. 预检、构建/推送新版本，拉取镜像；这些步骤成功前不停止旧应用。
2. 记录上一镜像 digest、Compose 配置和 schema，确认数据库与新镜像兼容。
3. 短暂停止应用写入，使用 pg_dump 自定义格式备份，校验备份可读并保留配置；备份失败不发布。首版更新会有短暂停机，不承诺零停机。
4. 同一 schema 的源码更新直接替换应用容器，数据库和数据卷保持不变。不得重复执行 install，也不得自动删除数据库。
5. schema 改变时需要显式 FROM_VERSION、经过验证的升级路径，运行一次性升级容器；未知路径立即拒绝。仍保持“一条命令”入口，但不猜测升级参数。
6. 等待服务就绪、HTTPS 与关键 API 检查通过，写入 release 记录，再标记更新成功。
7. 同 schema 更新失败时恢复上一镜像并重新验证；保留失败日志。发生数据库迁移后不自动切旧镜像，也不自动恢复备份覆盖新数据，需要按对应版本恢复方案处理。

## 持久化与恢复

- PostgreSQL 18 卷挂载 /var/lib/postgresql，固定 Compose 项目与卷名称，不随镜像版本更换。
- 持久化应用实例数据、实际文件存储路径、Caddy 证书数据和必要日志；实现时核对应用配置中的全部可写路径，避免容器重建丢失上传文件。
- 更新前备份存放 /opt/zhigong-things/backups，保留对应 release 元数据。定时备份与服务器外副本另行配置；只在同机留备份不能覆盖服务器磁盘故障。
- 日常操作不执行 docker compose down -v，不清空数据卷，不全局 prune 其他业务资源。
- 维护 current/previous release 记录和部署锁，失败时保持可追溯状态。

## 上线前待解决事项

1. 最近域名查询仅返回 47.100.121.136；部署脚本每次重新验证，拒绝含其他 IP 的解析。
2. 阿里云安全组需允许网站 80/443，并按设备需要放行协议端口。尚未验证云安全组。
3. 确认本机和服务器有指定镜像仓库的推送/拉取权限，私有仓库分别认证。尚未检查登录凭据或执行推送。
4. 检查域名在当前大陆服务器上对外访问所需的备案与接入条件，此项不由 SSH 部署脚本自动办理。
5. 正式镜像需验证本地订阅策略的完整启动、首次设置、账号登录与权限隔离；目前已通过模块编译与 3 项单元测试，尚未在新生产镜像上端到端验证。

## 实现文件清单与验收

已增加 deploy/Dockerfile、deploy/entrypoint.sh、deploy/compose.yml、deploy/Caddyfile、scripts/deploy.sh、scripts/deploy-remote.sh、scripts/smoke-image.sh、scripts/registry-login.py，并扩展 Makefile。服务器 .env 自动生成，不需要手填数据库密码。更新前备份位于 /opt/zhigong-things/backups，日志由 Docker 轮转；本地构建与 smoke test 日志在 .dev/。

验收包括：linux/amd64 镜像实际启动、空库只初始化一次、同版本重试安全、更新保留管理员/设备/仪表盘/文件、HTTPS 与 WebSocket 可用、失败更新可回退、数据库备份可恢复、日志不泄露凭据。

部署成功只在应用就绪和公网可信 HTTPS API 均通过后记录。`make rollback VERSION=旧版本` 只接受已成功发布的版本，并使用原 digest；数据库 schema 不一致时拒绝回退。数据库迁移用 `make deploy VERSION=新版本 FROM_VERSION=当前数据库版本`，需要事先确认对应官方升级路径。

当前验证：make deploy-check、Python 语法及 git diff --check 通过；镜像运行、HTTPS、数据库备份恢复和真实更新/回退尚待联网构建后验证。没有创建服务器业务容器或修改服务器数据库。

## 官方参考

- ThingsBoard Docker：https://thingsboard.io/docs/pe/installation/docker/
- 源码构建：https://thingsboard.io/docs/pe/installation/building-from-source/
- Docker 多平台构建：https://docs.docker.com/build/building/multi-platform/
- Caddy 自动 HTTPS：https://caddyserver.com/docs/automatic-https
- Caddy 反向代理：https://caddyserver.com/docs/caddyfile/directives/reverse_proxy

## Docker 构建内存

在 Mac Docker Desktop 的 Settings → Resources → Memory 分配建议 10–12 GiB 内存（当前 Mac 物理内存 24 GiB），Apply & Restart 后执行 make deploy。停止不需要的开发容器和本地前后端可减少资源竞争。脚本检查 Docker VM 总内存，不代表剩余可用内存。

Docker 专用生产命令 build:prod:docker 使用 Node 4 GiB 堆和 Angular 两个 workers，Maven 1 GiB 堆；普通 build:prod 保持原值。目标镜像依然是 linux/amd64，保留全部生产优化。退出137/Killed且 cannot allocate memory 是内存耗尽，不是前端 peer dependency warning。Maven/Yarn下载缓存可复用，但失败的 RUN 层没有保留已编译模块，重试仍会重新编译。
