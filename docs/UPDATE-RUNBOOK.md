# 低中断更新操作说明

2026-10-15。适用于当前单机 PostgreSQL 18 + ThingsBoard + Caddy 部署。双节点、Kafka/共享缓存和设备固件缓存没有在本次启用；`MODE=rolling` 会被拒绝。

## 明天发布

在项目根目录执行：

```sh
make deploy
```

默认构建完整生产前端和 linux/amd64 镜像，独立空库 smoke test 后推送，再启动远端后台发布任务；本次开发没有推送或部署线上。

指定新版本仍然支持：

```sh
make deploy VERSION=20261015-01
```

不要把昨天构建的无 release-policy 镜像当成新版本直接 BUILD=0 发布。新脚本要求候选镜像内含经过校验的兼容策略。普通源码更新无需 FROM_VERSION；官方 schema 改变时仍须验证升级路径并传入 FROM_VERSION。

### 本次首次升级的差异

- 精确匹配已审阅的历史 digest `sha256:4386f4089bacc24960e358cf8b74f3345ae8e40d01318eba13476ebe1be45d4b`，或新旧兼容策略匹配时：在线备份完成后才停止应用；无需等待 pg_dump 的停机时间。
- 其他没有兼容元数据的旧镜像：自动进入 maintenance，停止后做最终备份。新版开始运行后不猜测可以回退。成功升级一次后，后续同兼容范围的新版本走在线备份和自动应用回退。
- Caddy 第一次从版本目录的单文件挂载迁移到稳定目录，可能有一次短暂重建；以后配置路径固定。PostgreSQL 在普通发版中不重建、不更新镜像。
- 单实例仍需重启 Java，不能承诺零停机。之前约81秒启动仅供参考，实际停止、启动和验证时间会记录阶段日志。

终端将显示版本、digest、mode、兼容性、备份路径、阶段和远端 job ID。远端 worker 即使 SSH 或本地终端断开也会继续执行。不要因终端断开立即启动第二次发布。

## 查看结果与验证

```sh
make deploy-status
make deploy-logs
make deploy-verify
```

服务器文件：

| 路径 | 内容 |
| --- | --- |
| /opt/zhigong-things/deployment.state | 阶段、目标/原版本、是否启动新版/迁移、回退条件、备份与job ID |
| /opt/zhigong-things/jobs/<job>/output.log | 完整远端发布日志 |
| /opt/zhigong-things/jobs/<job>/result | 发布退出码，0为完成 |
| /opt/zhigong-things/current、previous | 已提交的当前/上一发布记录 |
| /opt/zhigong-things/releases/<version>/verified-policy.json | 与镜像label哈希核对的策略 |
| /opt/zhigong-things/proxy/Caddyfile | Caddy当前正常/维护配置，目录挂载支持原子替换 |
| /opt/zhigong-things/backups | 数据库归档、SHA-256和对应旧release记录 |

部署成功需内部 READY、数据库可读、根页面、公网可信 HTTPS、MQTT CONNECT协议探测及30秒观察期均通过。只对首次空库安装允许 ACCOUNT_REQUIRED；已上线系统不将缺少管理员当成健康。

MQTT匿名探测被拒绝是正常的协议验证，只证明1883链路可达，**不证明设备遥测已落库**。发布完成后仍需在真实设备上核对重连、最近遥测时间、产量与大屏。`make deploy-verify` 不创建生产账号、设备或写入生产遥测。

`make deploy-plan VERSION=...` 检查已在服务器暂存且已拉取的候选镜像；不构建、不拉取、不停止服务，也不替未完成任务做恢复。首次安装新脚本之前不能使用新增远端入口。

## 发布过程和失败

正常路径：PREPARING → BACKED_UP → DRAINING → STOPPING → STOPPED → STARTING → VERIFYING → OBSERVING → COMMITTING → COMMITTED。

维护路径在 STOPPED 之后备份；官方迁移前写入 MIGRATION_STARTED。维护期间网站返回503提示，API返回JSON 503、Retry-After=30、no-store，不将错误伪装成200。设备HTTP需自行缓存/重试；MQTT连接会断开并需重连。

- FAILED：在停止应用前失败，旧服务未被替换，例如镜像/空间/在线备份失败。
- RESTORED：旧应用和代理已恢复，数据库未覆盖；本次发布仍以非零退出，检查原因再重试。
- MANUAL_RECOVERY：迁移已经开始、兼容性未知且新版已启动，或自动恢复失败。不得盲目重新install、删除卷或恢复数据库覆盖新数据。
- 已提交版本同digest再次发布：验证现状后直接结束，不再次备份/替换容器。
- 进程被kill或主机重启留下未完成阶段：下一次deploy先核对日志并按记录恢复兼容旧应用；未知迁移阶段会拒绝自动处理。
- 检测到运行容器与current不一致、版本同名但digest改变、PG配置或Caddy基础参数改变：停止在切换前，要求先核对独立基础设施变更。

SSH断开后先通过deploy-status查看job；不使用第二个发布覆盖正在运行的任务。可只读查看完整日志：

```sh
ssh root@47.100.121.136 'tail -n 100 /opt/zhigong-things/jobs/<实际job编号>/output.log'
```

显式维护与备份/回退仍为：

```sh
make deploy VERSION=新版本 MODE=maintenance
make deploy VERSION=新版本 MODE=maintenance FROM_VERSION=已验证的旧数据库版本
make backup
make rollback VERSION=已验证兼容的旧版本
```

回退不会恢复数据库。无元数据的旧镜像只有精确命中已审阅digest且当前策略允许时才可作为回退目标。未知旧镜像不自动放行。普通运行和回退不升级PG大版本、不执行down -v。

## 产量后台修复和回算续跑

本次增加版本化的产量扩展schema（版本2），仅新增表，不清空已有统计、配置和永久大屏密钥。更高未知版本会在发布预检和应用启动时被拒绝，避免自动降级。

每次应用正常启动后，后台逐设备扫描最近24小时原始totalCount，包含已有样本中间的缺口及没有production_device记录的设备；不只根据最新样本时间查尾部。每批100条、批间至少1秒，最多一个本地修复worker。数据库里保存每设备范围和cursor，重启后续算；源查询5秒超时后取消并等待30秒重试，SQL/计算异常回滚本批进度。

实时产量计算失败时，会记录原始遥测对应的精确修复范围；与用户手动回算使用不同表。`production_repair`行删除表示该设备范围已完成，不增加重复产量。它按原始遥测修复缺失样本及落库后计算失败的显式覆盖；读取源数据前记录设备revision，应用时持有设备锁核对revision，若有更新已先完成则保留cursor重新读取，避免旧快照覆盖新计算。原始数据已经过期、设备没有上报、或中断前的迟到写入超出24小时扫描范围时，不能凭空恢复；可针对仍保留的更早原始数据执行手动回算。

用户历史回算在停止时保留cursor/job ID，标记INTERRUPTED；单节点重启后最多两个任务自动续跑。该实现明确以单节点为前提，不允许直接启动第二个完整应用形成集群。

可检查进度：

```sh
ssh root@47.100.121.136 'cd /opt/zhigong-things; set -a; . ./current; set +a; docker compose --env-file .env -f releases/$VERSION/compose.yml exec -T postgres psql -U postgres -d thingsboard -c "SELECT status,count(*),max(attempts) AS max_attempts FROM production_repair GROUP BY status;"'
```

后台修复不阻塞页面就绪；首次扫描数据较多时可能运行较久。队列为in-memory时，重启丢失的未落库消息无法由此修复。设备/网关持久缓存、原始时间补报和端到端确认仍是后续现场工作；本轮未修改设备固件、未加入外部Kafka或采购资源。

## 本地验证入口

```sh
make deploy-check
make deploy-test
make deploy-test-services
TESTCONTAINERS_RYUK_DISABLED=true mvn -o -pl application \
  -Dtest=ProductionRepairServiceTest,ProductionCalculatorTest,DeviceProductionServiceTest,ProductionSpeedQueryTest,DeviceProductionControllerTest \
  -Dpkg.skip=true -Dpkg.gradle.phase=none -Dskip.ui.build=true test
```

- deploy-check：脚本/Python语法、兼容策略与SQL指纹、Compose配置。
- deploy-test：隔离临时目录、假Docker/HTTP故障注入，执行真实worker和后台job脚本；不SSH。
- deploy-test-services：独立临时Docker网络/容器，真实Caddy切换、JSON维护响应、PG18归档完整恢复，结束清理资源；不SSH。默认需本地postgres:18.1和DaoCloud的caddy:2.10.2，可用TEST_POSTGRES_IMAGE/TEST_CADDY_IMAGE覆盖。
- Maven：独立PG测试，修复分页/防重/失败/重启、回算续跑、未来schema拒绝，以及原有业务和权限接口。

设计与后续集群路线见 [UPDATE-AVAILABILITY.md](UPDATE-AVAILABILITY.md)。当前“已实现”和“本地验证”不代表已验证真实生产升级；第一次生产更新由用户执行并核对现场采集链路。
