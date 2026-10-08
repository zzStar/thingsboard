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
