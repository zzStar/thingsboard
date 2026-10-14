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

## 推送中断后继续

镜像已构建且 smoke test 通过、仅推送失败时，执行 `make deploy VERSION=原版本 BUILD=0 RESUME_PUSH=1`，复用本地 linux/amd64 镜像重新推送后继续部署。完整上传的层会复用，未完成的大层可能重新上传，不保证按字节续传。推送自动最多重试三次。远端标签已存在时拒绝覆盖，可移除 RESUME_PUSH 使用该远端镜像部署。

## 测试代码编译失败

`-DskipTests` 跳过测试执行，但保留测试代码编译。2026-10-14 发布失败于 `TbResourceApiClientTest` 的资源筛选参数：当前客户端要求 `Set<ResourceType>`，测试仍传字符串。已改为 `Set.of(ResourceType.JS_MODULE)`，保留发布构建的测试编译检查。

本机与 Docker 缓存中的 `thingsboard-pe-client:4.4.0` 内容不同：旧本机包 SHA-1 为 `85b91d4403dc445bb5388adc055449a756efa527`，Docker 包及官方仓库当前校验值均为 `3048cd74e713f9fabc923a651b92e0dab6cf05a0`。已备份并同步本机这一依赖包，application 的663个测试源文件编译通过。遇到本机与容器结果不一致时，核对具体依赖的签名与校验值，不清空整个 Maven 缓存。

修复后完整 `linux/amd64` 发布 Dockerfile 构建通过：生产前端、663个 application 测试源文件、boot jar 及运行镜像步骤均成功，Maven 总耗时6分36秒，日志 `.dev/resource-api-docker-build.log`。测试按发布参数跳过执行，结果保留于 BuildKit 缓存；本次未导出发布标签、推送、运行 smoke test 或服务器部署。

## Docker 基础镜像国内镜像

发布构建首次准备基础镜像时，通过 [DaoCloud 官方推荐的前缀地址](https://github.com/DaoCloud/public-image-mirror#使用方法) 拉取：

- `m.daocloud.io/docker.io/library/maven:3.9.11-eclipse-temurin-25`
- `m.daocloud.io/docker.io/library/eclipse-temurin:25-jre`

`make deploy` 首次将两个镜像完整导出为本地 OCI 缓存，存于忽略的 `.dev/base-images/`。每次使用前校验镜像 manifest、配置和全部层的 SHA-256，以及 `linux/amd64` 架构，再通过 [Docker 本地 OCI 上下文](https://docs.docker.com/reference/cli/docker/buildx/build/#use-an-oci-layout-directory-as-build-context) 提供给两个 FROM。缓存命中时不再查询镜像源标签，避免已缓存镜像层却因远端 HEAD 502 失败；更换 builder 也可从本机完整缓存恢复。

无有效缓存时最多尝试3次，并在两个 DaoCloud 地址间切换；它们属于同一服务，不代表独立镜像源。显式指定的其他仓库只重试原地址，不擅自切换来源。失败或中断不更新缓存选择记录；刷新时保留旧的不可变镜像目录。缓存路径按镜像地址和目标架构区分，镜像内容用 digest 锁定；普通发版不自动更新同一标签的上游内容。

可单独准备或主动刷新基础镜像，不构建应用、不推送、不部署：

```sh
make cache-base
python3 scripts/cache-base-images.py --refresh
```

直接使用 `deploy/Dockerfile` 且未传本地 OCI 上下文时，仍从上述默认地址读取镜像。无需修改宿主机 Docker 配置或重建 builder。基础镜像缓存独立于 npm、Maven、GitHub 缓存，不表示整个构建离线；未缓存的依赖和 apt 包仍需下载。

切换到自己的镜像仓库时，可以覆盖完整镜像地址，例如：

```sh
make deploy BUILD_BASE_IMAGE=你的仓库/maven:3.9.11-eclipse-temurin-25 \
  RUNTIME_BASE_IMAGE=你的仓库/eclipse-temurin:25-jre
```

直接使用 Docker CLI 时对应参数为 `--build-arg BUILD_BASE_IMAGE=...` 和 `--build-arg RUNTIME_BASE_IMAGE=...`。发布脚本会输出本次使用的两个镜像地址；不会在国内镜像失败后自动切回 Docker Hub。

2026-10-14 已验证两个地址提供 `linux/amd64` manifest，当前 `eim-builder` 成功通过国内源获取元数据并复用镜像层，执行 Maven 3.9.11 / Java 25 及运行时 Java 25 检查；正式 Dockerfile 的 `--call=check` 无警告，`make deploy-check` 与 `git diff --check` 通过。本次没有执行完整应用构建、推送或服务器部署。

同日502修复验证：本机两个完整基础镜像缓存约287 MiB，重复准备均命中；实际以本地 OCI 上下文执行 Java/Maven 检查（RUN禁网，--pull与--no-cache开启）通过，正式Dockerfile配两个OCI上下文的检查通过。模拟验证重试/地址切换、命中不调用Docker、别名复用、刷新失败保留缓存、损坏识别与恢复、带空格路径；本次未重新构建应用镜像、推送或部署。

## npm / Yarn 国内镜像

`ui-ngx/.npmrc` 和 `.yarnrc` 将项目 registry 设置为 `https://registry.npmmirror.com`，本地 npm/Yarn、Maven 前端插件和 `make dev` 使用同一配置。发布 Docker 构建另设 `NPM_CONFIG_REGISTRY`，不修改宿主机全局 npm 设置。

Yarn 1 会使用锁文件中的完整下载地址，所以 `ui-ngx/yarn.lock` 的1578个 npm registry 地址也同步改为国内镜像。包版本、依赖关系、integrity 和地址中的校验片段均保留。GitHub tarball/Git 依赖继续使用原地址；例如 flot 的 GitHub TLS 失败需要独立排查，不能靠 npm registry 修复。

2026-10-14已验证项目 npm/Yarn 的 registry 配置读取、空缓存 Yarn 安装的实际镜像请求及锁定版本、锁文件结构一致性。新的配置从下一次构建开始生效；已经运行的构建不会读取后续文件修改。该变更不代表完整镜像构建或部署已完成。

## 锁定的 GitHub 依赖缓存

`make deploy` 在构建前自动执行 `scripts/cache-github-deps.py`。也可用 `make cache-github` 单独准备缓存，不触发构建、推送或部署。

脚本读取当前 `ui-ngx/yarn.lock` 的5个GitHub依赖：echarts、zrender、flot、flot.curvedlines、ngx-flowchart。优先复用本机Yarn下载缓存，按锁文件的来源、版本和SHA-1/commit匹配；已有独立缓存命中后不下载。确实缺失时，tarball校验锁定SHA-1，Git依赖按锁定commit从GitHub codeload下载，转换为Yarn git-archive布局，避免查询远程分支。当前Git依赖已经包含可安装文件；带prepare脚本的未构建源码包会被拒绝，不能冒充已准备好的依赖。

缓存位于忽略的 `.dev/github-deps/<锁定依赖指纹>/`，包含约28 MiB归档和校验清单。成功下载的单个包立即记录，后续包下载失败也可重试复用。锁定依赖变化后使用新的指纹目录；缓存损坏时重新从可信Yarn缓存或锁定来源恢复，不静默使用旧版本。

发布脚本通过 `--build-context github-deps=...` 将归档传给Docker。Docker将归档作为独立源码层复制，校验SHA-256后导入名为 `thingsboard-github-deps` 的BuildKit缓存，并配置Yarn offline mirror。每次实际执行构建步骤都重新导入这5个归档，因此即使BuildKit缓存清理或更换builder，也能从本机独立缓存恢复。Maven/Yarn安装失败不会删除本机归档；正式运行镜像仅复制应用产物，不包含这些构建缓存。生产安装增加 `--frozen-lockfile` 保持版本锁定。

如直接使用Docker CLI，须同样传入构建上下文，例如：

```sh
GITHUB_DEPS_CACHE=$(python3 scripts/cache-github-deps.py)
docker buildx build --platform linux/amd64 -f deploy/Dockerfile \
  --build-context "github-deps=$GITHUB_DEPS_CACHE" \
  --tag thingsboard-cache-check --load .
```

2026-10-14已验证：重复准备全部命中；Mac和Linux容器在断网/空Yarn缓存条件下安装全部5个锁定依赖；独立BuildKit缓存上下文COPY校验成功、第二次显示CACHED；缓存损坏恢复、冷下载转换后的逐文件内容一致性、部署脚本语法/Compose及根许可证头检查通过。完整发布Dockerfile检查遇到Docker Hub基础镜像认证超时，尚未重新构建正式镜像或推送部署。

## MQTT 接入

Compose 发布 TCP 1883 到 ThingsBoard 容器1883，设备连接 things.zhigongshulian.com:1883。需在阿里云安全组允许 TCP 1883 入站。网站 HTTPS 的 Caddy 不代理此 MQTT 端口；1883为普通MQTT，8883需单独配置MQTT TLS。Dockerfile EXPOSE只是元数据，不能代替Compose ports。

## 其他端口核查（当前部署）

网站及HTTP设备API通过443/TCP访问，80/TCP供Caddy重定向/证书验证，MQTT1883/TCP已发布；8080仅回环18080与Docker内网。其他接入按需求发布：CoAP5683/UDP；LwM2M5685–5688/UDP（服务5685/5686、Bootstrap5687/5688）；Edge同步7070/TCP；远程集成9090/TCP。已核实这些接入端口在容器内监听但未对外映射。

MQTTS8883/TCP和CoAPS5684/UDP当前未启用，须配置证书及TLS/DTLS开关后才可发布。SNMP为随机UDP监听，常规轮询无须映射入站；需要接收TRAP时先设置固定SNMP_BIND_PORT（例如1620）及UDP映射。PostgreSQL5432不发布公网。上述端口不是每个部署都必须开放，当前仅MQTT及HTTP设备接入已经满足。
