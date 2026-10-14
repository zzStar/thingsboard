# 项目记忆：本地开发、预览与服务器部署

记录日期：2026-10-08。后续启动、构建和部署工作先参考本文件，并核对当前源码；记录不是已验证的运行结果。

## 用户目标

- 在本地修改 ThingsBoard 源码、调试和预览，然后将包含修改的版本部署到服务器。
- 用户已请求实现一键本地开发启动：make dev，包含中间件、前后端。服务器部署尚未请求执行。
- 使用中文沟通。服务器操作系统、CPU 架构、域名、资源、镜像仓库和许可证尚未确定，不要假定已提供。

## 服务器只读检查（2026-10-08）

- 用户要求先生成部署方案，最终实现一条命令部署和更新。设计记录在 docs/DEPLOYMENT.md；目标入口 make deploy，使用独立正式镜像、Compose、Caddy、PostgreSQL 18，当前资源首版不加 Kafka。该文档是设计，尚未实现部署命令或执行部署。后续必须区分同 schema 镜像更新与数据库升级，更新前备份；迁移后不自动回滚数据库。

- 用户指定服务器 47.100.121.136、域名 things.zhigongshulian.com、镜像仓库 registry.cn-hangzhou.aliyuncs.com/zgsl/things，版本号待定。SSH 使用 root@47.100.121.136 免密成功；默认本机用户名 star 登录失败。
- Ubuntu 24.04.5 LTS，x86_64，2 CPU；内存约 3.5 GiB、可用约 3.0 GiB，无 swap；根盘 79G、可用 73G。
- Docker 29.8.2、Compose v5.6.0 已安装；无容器（含停止容器）和镜像，未找到 Nginx/Caddy 命令。监听公网端口仅 SSH 22，主机 UFW inactive；阿里云安全组尚未检查。
- 服务器解析域名返回 47.100.121.136 和 101.200.139.54，部署前核对 DNS 多 A 记录，避免部分请求落到另一服务器。
- 本轮只读检查，未修改服务器、未推送镜像、未部署。资源有限，正式应用+数据库+Kafka 同机部署需评估内存或扩容；目标镜像架构为 linux/amd64。
- 用户最新要求：本地和服务器移除 NON_PRODUCTION_USE=true，并且不需要外部许可证激活。本分支默认 LocalSubscriptionService 使用本地订阅策略，进入管理员设置，不启用非生产模式，也不检查订阅实体配额。BasicSubscriptionService 仅在 licensed profile 下启用；install/test 仍使用原实现。登录、权限与租户隔离不变。外部 AI 额度不由本地订阅策略提供。服务器仍未部署应用。

## 官方参考

- 源码构建：https://thingsboard.io/docs/pe/installation/building-from-source/
- Docker 部署：https://thingsboard.io/docs/pe/installation/docker/
- AI 功能：https://thingsboard.io/docs/pe/iot-solutions-with-ai/

源码构建文档负责构建流程，Docker 文档负责运行拓扑、数据库初始化与持久化，AI 文档用于后续功能开发。官方文档会更新，版本和参数应以当前分支为准。

## 当前源码与环境

- 根 pom.xml：4.4.0-SNAPSHOT，Java source/target 为 25；项目包含 PE 功能。
- CONTRIBUTING.md 推荐 JDK 25、近期 Maven 3.9.x。ui-ngx/pom.xml 自动提供 Node 22.22.2、Yarn 1.22.22；直接开发 UI 时另行准备一致版本。
- 用户于 2026-10-08 提供终端输出确认：macOS arm64；java 和 Maven 均使用 Temurin 25.0.4.1，Maven 3.9.16，JDK 路径 /Library/Java/JavaVirtualMachines/temurin-25.jdk/Contents/Home，满足项目 Java 25 要求。是否写入 ~/.zshrc 尚未确认。此前工具检查 Node 24、未安装全局 Yarn、有 Docker CLI；这些状态后续需重新核对。Docker CLI 存在不代表 daemon 已启动。
- 后端入口：application/src/main/java/org/thingsboard/server/ThingsboardServerApplication.java。
- 数据库初始化入口：ThingsboardInstallApplication.java；初始化和升级是不同操作。
- application/src/main/resources/thingsboard.yml 默认后端 8080、PostgreSQL localhost:5432/thingsboard、用户名/密码 postgres、SQL 时序存储、in-memory 队列、本地 JS evaluator。
- ui-ngx 的 yarn start 提供开发预览，默认 4200；proxy.conf.js 将 API 和 WebSocket 转发到 localhost:8080。
- 上游 licensed 订阅实现支持 NON_PRODUCTION_USE=true：有水印、无白标、累计运行 30 天后关闭。本分支默认已换为本地订阅策略，不依赖该参数或 TB_LICENSE_SECRET；显式 licensed profile 才恢复上游实现。

## 工作流程

1. 本地优先采用 PostgreSQL 容器、Java 后端、Angular 开发服务，方便调试和热更新。开发服务仅限本地访问。
2. 首次准备数据库后执行安装入口；已有数据库不要重复初始化。更换版本应核对对应升级路径。
3. 构建候选命令（尚未运行验证）：mvn clean install -pl application -am -DskipTests -Dpkg.skip.deb=true -Dpkg.skip.rpm=true -Dpkg.skip.zip=true。此命令保留 UI 和 boot jar；跳过测试只用于快速构建，不代表部署前验证已完成。
4. 后端包预期为 application/target/thingsboard-4.4.0-SNAPSHOT-boot.jar。打包后安装指定 -Dinstall.data_dir=application/target/data，包含从 dao 复制的 SQL；源码 application/src/main/data 本身没有完整 SQL，不能直接作为完整安装目录。注意 jar 排除了 logback.xml，运行时提供外部日志配置。
5. 发布前构建生产前端并验证打包后的服务，不能将 yarn start 开发服务器作为正式部署方式。
6. 自定义源码必须构建自有镜像、用明确版本或提交标识，并替换 Compose 的 image；官方预构建镜像不含本地修改。
7. 仓库 msa/tb-node/docker/Dockerfile 依赖生成的 .deb 和 Maven 模板替换；构建 Docker 镜像时不能沿用跳过 deb 的本地构建参数。根 build.sh 当前使用 -Dpkg.skip=true，不能仅凭脚本提示认定镜像已经构建或推送。
8. 本地 arm64 与服务器可能是 amd64；镜像构建前确定目标架构。

## Docker 文档要点与部署边界

- 当前官方示例使用 thingsboard/tb-node:4.4.0 和 postgres:18，可选 in-memory 或 Kafka；报表服务按功能需要配置。
- 首次初始化命令：docker compose run --rm -e INSTALL_TB=true thingsboard；完成后 docker compose up -d，查看 docker compose logs -f thingsboard。
- PostgreSQL 18 示例挂载 /var/lib/postgresql；前一轮 PostgreSQL 16 示例挂载 /var/lib/postgresql/data。不得直接混用路径或把大版本更换当普通镜像更新。
- 持久化数据库和许可证实例数据；服务器配置使用环境变量/秘密文件，不提交真实密码、许可证或凭据。
- 按实际设备协议开放端口；生产配置 HTTPS、数据库备份、升级验证和可回退镜像。in-memory 队列需评估重启丢失待处理消息的影响。

当前只完成文档阅读与源码配置核对，未安装依赖、启动服务、构建镜像或部署服务器。后续执行后及时更新已验证事实。

## 补充学习：操作顺序与调试方式

以下命令是官方文档及 CONTRIBUTING.md 的参考流程，未经本机执行验证。

### 日常开发

- IDE 导入根 pom.xml，选择 JDK 25；编译依赖可用 `mvn clean install -DskipTests -Dpkg.skip=true`。此模式没有可执行 boot jar，适合 IDE 运行与测试。
- IDE 使用 application 模块 classpath，工作目录选择项目根目录，首次运行 ThingsboardInstallApplication 初始化数据库，然后运行 ThingsboardServerApplication 调试。初始化目录需具备 JSON 和 SQL（打包后的 application/target/data）；跳过 packaging 的 IDE 构建不会准备完整安装数据，需要另外准备。配置数据库环境变量，默认使用本地订阅策略；日志配置核对 `-Dlogging.config=application/src/main/resources/logback.xml`。
- 前端在 ui-ngx 执行 `yarn install`、`yarn start`；当前 start 脚本绑定 0.0.0.0，需限制网络访问或显式改为 127.0.0.1。前端热更新不代表 Java 后端自动重载，后端可用 IDE 断点调试、重启验证。
- 前端生产构建 `yarn build:prod`，静态检查 `yarn lint`。发布时重新走完整打包流程，确保新前端进入应用产物。
- 后端变更先针对所改模块编译和测试；编译参考 `mvn -pl <module> -am -DskipTests -Dpkg.skip=true test-compile`。部分测试依赖 Docker/Testcontainers。新增源码遵循现有许可证头，提交前按 CONTRIBUTING.md 处理。

### 镜像与服务器部署

- 官方完整安装包构建参考 `mvn clean install -DskipTests`，本地镜像构建参考 `mvn clean install -DskipTests -Ddockerfile.skip=false`。先评估 macOS 上包构建工具和目标架构；必要时在目标 Linux 架构的构建环境执行。
- msa/tb-node/pom.xml 使用 `docker build`，构建上下文为生成的 target 目录，并标记 `${docker.repo}/${docker.name}:latest` 和 `${project.version}`。先检查实际产物，再打自定义发布标签。不要默认启用 push-docker-image profile，它会推送镜像。
- 流程为：记录提交 → 构建并验证自有镜像 → 传输至服务器或镜像仓库 → Compose 引用固定镜像 → 注入服务器配置 → 首次初始化或对应版本升级 → 启动 → 验证日志、页面和设备数据链路。
- Compose 中 JDBC 数据库主机使用服务名 postgres；本地 IDE 连接宿主机映射端口时使用 localhost，两者不可混淆。
- 数据库需 healthcheck；应用等待数据库就绪。报表导出需要匹配的 tb-web-report 服务和 REPORTS_SERVER_ENDPOINT_URL；Trendz 为可选附加服务。
- 当前官方首次启动为许可证激活后创建系统管理员，可选择生成演示租户；不要沿用旧版默认系统管理员账号的假设。演示账号在上线前更换密码。
- 容器管理参考 `docker compose logs -f thingsboard`、`docker compose down`、`docker compose up -d`；日志出现 Started ThingsboardServerApplication 后仍需验证实际业务。日常停机不使用 down -v，以免删除数据卷。
- 镜像回退不能代替数据库回退；涉及数据库迁移时准备版本匹配的备份和恢复方案。

### 故障定位顺序

先核对 Java/Maven 的 JDK、数据库连接及就绪状态、初始化/升级结果、许可证状态，再查看后端日志和前端代理。镜像执行失败核对 CPU 架构、入口脚本和 LF 换行。遇到依赖问题先定位具体依赖，不默认清空整个 Maven/Gradle 缓存。

## 一键开发入口

- 用户明确 Logo 要保留左侧原图标，只将 ThingsBoard 文字改为“智工物联”，登录页也修改。主界面使用 assets/zhigong-logo.svg，登录页使用 assets/zhigong-logo-white.svg；两者复用 small_logo_title_black.svg 的原图标路径，并在 white-labeling.service.ts 兼容已存储的旧默认地址。折叠小图标保持原样。

- 用户要求去掉本地预览页面水印；DevelopmentService.checkIsDevelopment 在 Angular isDevMode() 时跳过页面覆盖层。只影响开发预览，生产构建和导出水印、后端许可证状态保持原行为。

- 用户要求国内 Maven 下载加速；已在本机 /Users/star/.m2/settings.xml 配置 aliyun-central，地址 https://maven.aliyun.com/repository/public，mirrorOf=central。这是用户级持久配置，make dev 和普通 mvn 自动使用；保留项目专用仓库的原地址。已经运行的 Maven 进程需重启才读取新配置；此配置不加速 Yarn、Node 或 Docker 下载。

- 已实现 Makefile、scripts/dev.sh、scripts/dev-compose.yml：make dev 在前台监督前后端，Ctrl+C 清理它启动的进程；make dev-down 停止 PostgreSQL 并保留数据，make dev-logs 查看日志。
- 专用 PostgreSQL 18 默认映射 127.0.0.1:15432，数据库 thingsboard_dev，数据由 Compose 独立卷持久化。应用仅对空库自动初始化；已有库不自动升级。
- 后端默认 8080、前端 4200；可通过 TB_DEV_HTTP_PORT、TB_DEV_UI_PORT、TB_DEV_DB_PORT 改端口。ui-ngx/proxy.conf.js 支持 TB_DEV_HTTP_PORT，默认仍为 8080。
- 脚本每次增量 Maven 构建（保留 UI/boot jar），使用 Maven 下载的 Node 启动 Angular，无需全局 Yarn。前端热更新，后端修改重启 make dev；日志、锁和许可证实例数据存放忽略的 .dev/。
- 已验证 Bash 语法、Compose 配置和端口冲突退出路径。本机 8080 被 eim-api 占用，因此默认启动在预检阶段退出，尚未验证完整构建和启动。
- 用户首次运行在根 Maven license:check 失败：新增脚本缺少符合模板的许可证头。已按 license:format 修正 scripts/dev.sh、补充 Compose 和 Makefile 文件头；脚本文件头后须保留空行。后续新增文件遵循 tools/src/main/python/license-headers/templates 中的模板，并运行针对性检查。
- make dev 构建日志保存 .dev/build.log，传入 yarn.install.extra.args=--verbose --prefer-offline，显示请求与完成时间并优先使用缓存。此参数只影响开发入口，普通 Maven 构建不默认开启详细日志。当前 Yarn 1 锁文件包含 registry.yarnpkg.com 完整 tarball URL，单独改 registry 未必改变这些下载地址；若后续切换镜像，保留版本和 integrity 并验证实际请求地址。测量时 moment 下载原源约 2.16 秒、npmmirror 约 0.35 秒，只是单次样本。
- 用户构建已通过 UI 和 application 编译，但 Gradle 配置失败，缺少未纳入 application Maven 依赖图的本地 packaging 模块。跳过 deb/rpm 仍会配置 Gradle，不能解决该问题。根 pom 新增 pkg.gradle.phase，默认保持原 pkg.package.phase；make dev 设置为 none，只禁用 OS 打包 Gradle invoke，保留 boot jar 与资源准备。自有服务器 Dockerfile 也使用 pkg.gradle.phase=none，直接运行 boot jar，无需 OS 打包；保留生产 Angular 构建。
- 2026-10-08 已确认后端实际启动：Tomcat 8080，启动日志正常，本地直连根页面和 setup API 返回 200。健康探测 curl 需 --noproxy '*'，避免本机代理影响就绪检测；不修改依赖下载的代理。gRPC 7070/9090 不用于浏览器访问，HTTP/1 GET 打到这些端口会产生协议错误。
- 启动慢已定位：用户运行进程的代理环境下本地 curl 超时（exit 28、2 秒），同环境 --noproxy '*' 约 0.02 秒成功，导致前端未启动。最近构建共 1:41，UI 模块 45.5 秒，后端启动约 16.5 秒。make dev 新增 skip.ui.production.build=true，只跳过 yarn build:prod，保留依赖安装；后端就绪检查使用 /api/noauth/setup/state。正常发布默认仍构建生产 UI。
- Makefile 使用 bash -c 读取完整脚本文本后运行，传入绝对脚本路径作为 $0 保持根目录定位。
- `UI_PORT�: unbound variable` 已用最小命令实际复现：macOS Bash 将未加花括号的变量后紧邻的中文括号字节误识别为变量名的一部分。就绪提示中 `$UI_PORT（` 和 `$HTTP_BIND_PORT）` 已改成 `${UI_PORT}（` 和 `${HTTP_BIND_PORT}）`。此前归因于编辑运行中的脚本不完整，不应继续按该假设排查。此错误发生在前端就绪后输出提示时，触发清理使前后端退出。

- 已实现一键部署：make deploy（可 VERSION、BUILD=0、FROM_VERSION），make deploy-status/deploy-logs/backup/rollback，具体见 docs/DEPLOYMENT.md。目标 root@47.100.121.136，域名 things.zhigongshulian.com，仓库 registry.cn-hangzhou.aliyuncs.com/zgsl/things，linux/amd64。服务器目录 /opt/zhigong-things。独立 Compose：PG18、ThingsBoard、Caddy；数据库密码自动生成，凭据不得输出。
- 发布先构建并运行独立临时空库 smoke test，再推送；远端按 digest 运行，只初始化空库，更新停止应用后 pg_dump 校验，schema 升级需 FROM_VERSION。无迁移的更新失败自动恢复旧应用，迁移失败禁止自动覆盖数据库。成功需 setup API 与公网 HTTPS 验证。
- 2026-10-08 make deploy-check、Bash/Python 语法及 diff 检查已通过，Docker Hub 基础镜像拉取超时，尚未构建正式镜像或在服务器上线；后续不能把脚本实现当成端到端验证完成。

- 2026-10-08 用户正式镜像构建在 Angular production 阶段退出137，BuildKit明确 cannot allocate memory。Mac物理内存24GiB，Docker VM约7.75GiB，原 build:prod允许Node堆8GiB。Docker专用 build:prod:docker改为4GiB，NG_BUILD_MAX_WORKERS=2，MAVEN_OPTS堆1GiB/2CPU；Maven属性 yarn.build.script仅Docker覆盖，普通生产命令保持原默认。发布预检Docker内存至少7GiB（设置建议10–12GiB），不跳过生产UI或改变目标linux/amd64。参数检查通过，完整重建尚待验证。

- Docker Yarn Fetching packages阶段默认无逐包日志；已在Docker构建加 --verbose，并显式YARN_CACHE_FOLDER=/root/.cache/yarn，与BuildKit cache mount一致。Yarn1源码Linux默认同一路径，未发现此前缓存路径错误。运行中的构建不受文件修改影响。锁文件tarball指向registry.yarnpkg.com，Maven阿里云镜像不加速它，单改Yarn registry不一定改锁文件下载URL。

- Yarn verbose逐文件Copying日志触发BuildKit output clipped, log limit 2MiB reached。Dockerfile在构建RUN内过滤verbose文件复制/目录/链接/删除行，保留请求、重试及错误。SHELL使用bash pipefail，验证过滤管道仍传播Maven非零退出状态；不能把日志裁剪当成构建失败。

- 简体中文已对齐英文locale全量键：菜单先补25项；其后补1647个缺失键（智能体、IoT Hub、初始化、订阅、AI方案、组件等），修正88项已有英文标签。英语源13499个键均有简体中文对应，保留协议/品牌/代码名称。校验新增/修改1735项的Angular插值、HTML标签、URL、代码块、美元变量与ICU参数/分支，实际MessageFormat编译通过；JSON无重复键、类型差异或空值。只改zh_CN，不改zh_TW/英文及数据库；服务器需要下一次make deploy才应用。

- 推送大层出现到Docker Desktop代理192.168.65.1:3128的broken pipe，是上传网络连接中断；不是构建失败。push-image.sh自动重试三次，make deploy VERSION=原版本 BUILD=0 RESUME_PUSH=1复用本地amd64镜像推送并继续部署，禁止覆盖已存在远端标签。已完成镜像层复用，部分未上传层不保证字节续传。

- 2026-10-08 已重试成功推送并部署版本20261008-205557-e8dc5bdc3d，digest sha256:4386f4089bacc24960e358cf8b74f3345ae8e40d01318eba13476ebe1be45d4b。备份/opt/zhigong-things/backups/20261008T132903Z-20261008-192911-e8dc5bdc3d.dump。服务器应用约81秒启动，无OOM/重启，公网HTTPS setup状态READY，部署记录已更新；生产中文静态资源已核对新增模块存在。

- 已修复生产MQTT1883不可达：容器MQTT原已启动，但Compose未publish。deploy/compose.yml新增1883:1883，当前服务器release已锁定备份配置并重建应用，容器映射0.0.0.0/[::]:1883。Mac分别通过IP和域名发送MQTT3.1.1 CONNECT，均收到CONNACK（匿名探测被拒绝，证明公网协议链路通）；HTTPS setup状态READY。设备正式连接需使用对应设备访问令牌；未开放8883等其他协议。

- 生产端口核查：1883/TCP已公开，Web/HTTP设备API走Caddy80/443，8080映射回环18080。容器内还监听UDP5683、5685–5688以及TCP7070（Edge）、9090（远程集成），仅对应功能使用时发布。9090是INTEGRATIONS_RPC_PORT，不可误写成纯内部端口。8883/MQTT TLS与5684/CoAP DTLS未启用，需要证书配置；SNMP随机UDP绑定，TRAP需固定端口。此次仅核查，不额外开放这些端口。

- 2026-10-14 用户要求两个发布基础镜像走国内源。deploy/Dockerfile 默认使用 docker.m.daocloud.io/library/maven:3.9.11-eclipse-temurin-25 与 docker.m.daocloud.io/library/eclipse-temurin:25-jre；make deploy 支持 BUILD_BASE_IMAGE / RUNTIME_BASE_IMAGE 覆盖完整地址并显示实际来源，不修改宿主机 Docker 配置。已验证两个 linux/amd64 manifest、当前 eim-builder 国内源元数据获取和现有镜像层中的 Java/Maven 运行，正式 Dockerfile --call=check 无警告，make deploy-check/diff 检查通过。本轮未执行完整应用构建、推送或部署。

- 2026-10-14 发布构建失败于 TbResourceApiClientTest：资源类型筛选传 String，但当前 thingsboard-pe-client:4.4.0 的 builder 要求 Set<ResourceType>。已改为 Set.of(ResourceType.JS_MODULE)，保留 -DskipTests 对测试代码的编译。本机同版本旧缓存 SHA-1 85b91d4403dc445bb5388adc055449a756efa527；Docker 包及官方仓库当前 SHA-1 均为 3048cd74e713f9fabc923a651b92e0dab6cf05a0。只更新了本机该 jar/sha1，旧包备份 .dev/resource-client-cache-check/host-backup/。JDK25 下 application 663个测试源文件 test-compile 已通过，日志 .dev/resource-api-current-client-test-compile.log。

- 同次修复后完整 linux/amd64 发布 Dockerfile 构建已通过，Maven 6分36秒：生产前端、application全部663个测试源文件、boot jar 与运行镜像步骤成功；日志 .dev/resource-api-docker-build.log。结果仅保留 BuildKit 缓存，本轮没有导出发布标签、推送、smoke test 或服务器部署。

## 设备产量（2026-10-14）

- 用户补充要求设备详情入口、每设备永久免登录大屏链接。已将“查看产量”放到实际使用的 DeviceComponent 顶部，并在设备列表加入图表入口；PE详情用通用GroupEntityTabsComponent，原专用DeviceTabs入口不会显示。租户管理员在产量看板生成/复制、重置、停用链接，管理API同时检查READ_TELEMETRY和WRITE_ATTRIBUTES。
- 独立路由 /production-screen/<deviceId>#key=<32字节随机密钥>，不依赖JWT、登录或刷新令牌，密钥保存在production_screen_link，无到期时间，普通发版/服务重启不变。公开GET /api/noauth/production/<deviceId>/screen只返回绑定设备的看板数据，用X-Production-Screen-Key请求头，无公开写入/设置/历史任意范围接口。重置/停用/设备删除失效，SQL校验实际device.tenant_id与链接所属租户一致。成功快照no-store，密钥不随页面URL请求发送给服务器。
- 看板共用ProductionBoardComponent，每5秒轮询，首次失败也重试，断线保留上次产量、隐藏实时速度并显示重连与更新时间；独立路由跳过登录初始化但加载完整静态语言，防止已有JWT过期导致跳转登录。大屏撑满根flex容器、支持大字号、全屏与iframe。当前默认X-Frame-Options关闭，部署若另外加frame-ancestors/X-Frame-Options需允许大屏平台。
- 已验证21项计算/PostgreSQL/Spring接口测试，包含永久/服务重建/幂等建表/并发生成/匿名只读/不同设备/重置停用删除与管理权限；加强权限检查后再次通过全部5项Spring接口测试。Angular类型、模板、ESLint、生产构建及许可证头检查通过。浏览器使用生产前端+本地模拟接口验证中文、过期登录状态不参与请求、5秒更新、断线/初次失败恢复与跨域iframe；测试截图.dev/production-screen-preview.jpg。测试模拟服务仅监听回环，未生成生产设备链接、推送或部署服务器。

- 已实现独立“监控 → 设备产量”页面，设备详情提供入口；今日/本周/本月、各班产量、速度趋势、历史范围查询及Excel/CSV导出。默认单位“贴”、速度“贴/小时”，默认白班07:00–19:00、夜班19:00–次日07:00；支持配置单位、两班/三班、班次名称和起点。按生产日统计，跨日班次归开始日。
- 用户确认停机、清零、重开工单耗时远大于2秒上报间隔；按totalCount下降识别清零，正常差分、下降后计入当前值，首次读数只建立基线。已实现防重、显式覆盖、迟到数据对后继增量的修正。边界按整数累计比例分配并标记估算；断报与持续上报的零产量区分。
- application正常启动时以幂等SQL建立production_*扩展表：2秒/source计数与增量、分钟、班次/生产日汇总、配置版本、回算进度；外键随设备删除清理。安装模式不加载产量服务。配置从未来生产日生效，历史配置保留。遥测历史保存成功后自动参与计算，设备协议和规则链无须修改。
- 历史页面支持原有totalCount遥测回算，后台分页、每次最多31天、重复执行防重；已过期/未采集的原始数据无法恢复。普通查询最多366天，导出包含全部查询结果而非当前分页。当前不按时间自动删除产量明细。
- 已验证14项计算/独立PostgreSQL服务测试、3项完整Spring接口测试，包含多次清零、迟到修正、并发防重、重启续算、历史分页回算和未分配设备权限。Angular应用类型/模板、定向ESLint、开发和生产构建、application/ui-ngx许可证头检查通过。测试容器因Ryuk镜像下载超时使用TESTCONTAINERS_RYUK_DISABLED=true，并确认测试结束清理。本轮未构建/发布正式镜像，未部署服务器。使用说明和验证命令见docs/PRODUCTION.md。

- 2026-10-14 大屏外观按用户工业看板参考图美化：深蓝大标题/品牌栏、浅灰背景、折角双线框、黄色点缀、蓝色图表与日期星期时钟，样式限独立大屏模式。生产Angular构建、定向ESLint通过，浏览器核对1920×1080、1366×768完整显示及390×844无横向溢出，控制台无错误。截图 `.dev/production-screen-tech-preview.jpg` 使用模拟测试数据，本次未部署服务器。

- 2026-10-14 用户要求大屏撑满高度：独立大屏改为视口高度及弹性布局，图表填满剩余空间，窄屏保留纵向滚动。生产构建通过，浏览器验证1366×768、1920×1080、2560×1440及高屏缩回矮屏正常，390×844无横向溢出。最新模拟数据截图 `.dev/production-screen-height-preview.jpg`，未部署服务器。

- 2026-10-14 用户将速度遥测键由 `speed-p` 改为 `speed`：产量概览实时速度、登录看板速度趋势及匿名大屏速度趋势均读取 `speed`；单位配置保持原样。同步测试数据/模拟及使用说明，5项Spring接口测试通过，覆盖实时速度及两种趋势接口读取新键。本次未部署。

- 2026-10-14 用户再次要求各设备独立设置单位：核对现有production_config已按设备保存；管理看板顶部新增“设置单位”入口，统计设置标签改为“单位与班次”，增加单位示例与设备作用范围/未来生产日生效提示。默认单位仍为贴，速度使用对应单位/小时，报表及导出使用各记录所属配置的单位。生产Angular构建、定向HTML ESLint及双语键检查通过，未部署。

- 2026-10-14 产量页日期/时间改为应用内MatDatetimepicker，覆盖历史起止、生效日期及班次开始时间，点击框/图标可选，保留手输；局部ProductionDatetimeAdapter支持HH:mm并拒绝24:00等无效时间，不影响其他页面。Date桥接保留设备时区查询语义，缓存Date引用避免NgModel反复触发变更检测。生产构建、定向ESLint、ui-ngx许可证检查、60项转换/解析检查通过；浏览器模拟接口验证日历/时分/班次联动和手动输入，请求时区正确。截图 `.dev/production-datepicker-preview.jpg`，未部署。

- 2026-10-14 修复单位保存后仍显示贴：原单位跟班次延期生效，现单位名称保存立即生效，班次/时区/上报间隔仍未来生效。单位为设备范围显示名称，历史报表和导出同步改名，数字不换算；读取配置统一使用最新单位以兼容旧待生效修改，保存更新该设备各配置版本单位，不新增边界/重算汇总，保留待生效班次。前端保存后重读概览/图表/报表并阻止旧轮询覆盖，匿名大屏下一次5秒轮询更新，原链接保持有效。24项计算/PostgreSQL/Spring测试、生产前端构建及ESLint通过，浏览器模拟保存张后直接显示张/小时，截图 `.dev/production-unit-immediate-preview.jpg`。这条取代此前单位随未来生产日生效的口径；未部署服务器。

## npm国内镜像（2026-10-14）



- 用户要求npm registry使用https://registry.npmmirror.com。已在ui-ngx/.npmrc、.yarnrc配置，发布Docker构建设置NPM_CONFIG_REGISTRY；本机全局配置不修改。本地npm/Yarn、Maven前端插件及make dev从项目配置读取。
- Yarn1锁文件1578个registry.yarnpkg.com下载地址已替换为npmmirror。经锁文件解析和结构比较验证，版本、依赖、integrity、校验片段保持原样；11个GitHub地址保留。GitHub tarball/Git依赖以及Node/Yarn自身分发下载不由npm registry加速。
- 已验证npm/Yarn config get registry，空缓存Yarn实际请求npmmirror下载锁定的moment 2.31.0，安装成功（单次样本1.47秒）。根pom添加npmrc许可证注释映射。只验证配置与代表性依赖下载，未重新构建完整正式镜像、未推送或部署；运行中的旧构建不会读取本次修改。

## GitHub依赖构建缓存（2026-10-14）

- 用户要求缓存当前锁定GitHub依赖。scripts/cache-github-deps.py从yarn.lock读取5个包，按来源/版本/SHA-1或commit优先复用本机Yarn tarball；独立归档存入忽略的.dev/github-deps/<依赖指纹>，约28 MiB。缺失包按锁定SHA/commit下载，Git包走codeload固定commit，不查询master/分支；不修改package.json或锁文件版本。make cache-github可仅准备缓存。
- make deploy自动准备并通过命名BuildKit上下文github-deps传给Docker；Docker校验SHA-256、导入持久化thingsboard-github-deps缓存，并配置Yarn offline mirror。正式Yarn安装加--frozen-lockfile。源码变化、失败重试、builder更换均可从本机归档恢复；最终运行镜像不含构建缓存。直接docker build须传同一--build-context，见docs/DEPLOYMENT.md。
- 已验证5包全部缓存命中、空Yarn缓存/禁用Git的离线安装、Linux容器断网安装、BuildKit上下文COPY及第二次CACHED、损坏缓存恢复、cold Git commit下载与原Yarn归档逐文件一致。make deploy-check及根license:check通过；根pom排除生成的.dev/**，避免检查缓存/第三方验证文件。完整发布Dockerfile检查受Docker Hub auth.docker.io超时阻断，未完整重建镜像、未推送或部署。

## 开发退出清理（2026-10-14）

- 用户连续 Ctrl+C 后出现 Error 129 与残留 .dev/lock。原 cleanup 恢复 INT/TERM 默认处理且未处理 HUP，退出清理可能被后续信号打断。scripts/dev.sh 现处理 HUP，清理期间忽略 INT/TERM/HUP，最多等待前后端退出20秒后强制结束，最后删除锁。
- 已验证 Bash 语法、diff检查和独立临时环境 SIGINT/SIGTERM/SIGHUP 退出及再次获取锁；未重新执行完整 make dev 构建启动。本次确认无开发前后端进程、8080/4200无监听后，已用 rmdir 删除残留空锁。make dev-down 仍只停止数据库、保留数据卷。

## 构建文件头与基础镜像缓存（2026-10-14）

- 用户要求去掉阻断构建的License check。报错为mock-device.sh缺文件头，不是外部激活检查。根pom移除license-maven-plugin的自动激活及check执行绑定，保留手动工具和原有版权内容。52模块的application依赖图validate通过，effective POM确认无自动license插件，make dev/make deploy无需额外跳过参数。未完整运行make dev。
- make deploy读取DaoCloud基础镜像tag的HEAD请求曾返回502；当前已恢复，两个DaoCloud地址manifest digest一致。默认改为官方推荐m.daocloud.io/docker.io前缀，首次下载失败在该地址与docker.m.daocloud.io之间最多尝试3次，不自动回退Docker Hub或更改宿主机配置。
- 新增scripts/cache-base-images.py与make cache-base；两个linux/amd64基础镜像完整OCI内容存.dev/base-images（本机约287 MiB），按镜像来源/架构分组，digest不可变目录及原子选择记录，fcntl串行准备，使用前校验manifest/config/层SHA256与架构。make deploy通过两个OCI命名上下文提供FROM，命中无远端tag解析；刷新用python3 scripts/cache-base-images.py --refresh，保留旧目录。apt与其他依赖仍可能需要联网。
- 已验证实际Java25/Maven3.9.11禁网RUN探针（开启--pull、--no-cache）、正式Dockerfile双OCI上下文检查、两镜像重复HIT、模拟首次失败重试/地址切换、命中不调用Docker、别名复用、刷新失败保护、损坏恢复和带空格路径。make deploy-check/diff通过，未完整重建应用镜像、推送或部署。
- 用户截图速度为空：当前新建mock-device.sh仍发speed-p，已仅将两处速度字段同步为speed，未修改连接凭据，Bash语法通过；运行中的脚本需要重启，本轮未发送模拟遥测。

## 速度趋势查询区间数（2026-10-14）

- 用户报/api/production/.../speed 400：原按1440点构造间隔，与database.ts_max_intervals默认700不符，长时间范围（796区间）被BaseTimeseriesService拒绝。新增ProductionSpeedQuery统一按min(1440,实际平台上限)向上取整间隔，至少1分钟；普通速度接口与匿名大屏注入同一database.ts_max_intervals配置，不修改全局限制。界面仍5秒刷新。
- 已通过4项查询边界/自定义上限/大范围测试与7项Spring真实接口测试（平台限制350），覆盖用户具体时间范围、24小时和匿名大屏，11项全通过、diff检查通过。仅后端变更，未重建前端、发布镜像或部署服务器；日志.dev/production-speed-interval-tests.log。

- 2026-10-14 修复匿名产量大屏断连空白：原快照速度趋势失败会使全接口失败，首次加载又仅在有完整view时渲染。现速度趋势失败/5秒超时返回speedUnavailable标记与已落库概览/历史；保留同一生产日已有曲线。前端设备断报显示等待上报提示，过期速度显示不可用，历史保留；每次成功保存浏览器最近快照（按设备与密钥SHA-256隔离、不存原密钥），503/超时及刷新恢复快照，显示完整上次更新时间，5秒自动重试。无缓存且API不可达时展示框架与“—”，不虚构0；404停用/重置清除视图和缓存并停止轮询。语言加载期间提示使用翻译管道，避免首次失败显示翻译键。24项生产/PostgreSQL/Spring测试、缓存异常/隔离验证、最终生产Angular构建及定向ESLint通过；浏览器本地模拟验证断报、503刷新历史、首次失败框架、自动恢复、速度部分失败、404清除，1920×1080无溢出，截图.dev/production-screen-offline-preview.jpg。未发布或部署。
