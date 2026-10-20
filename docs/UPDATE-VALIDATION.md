# 单机低中断更新验证记录

日期：2026-10-15。验证仅在本地和独立临时容器执行；没有SSH连接生产服务器、推送镜像或执行线上部署。明天由用户运行make deploy，操作见[UPDATE-RUNBOOK.md](UPDATE-RUNBOOK.md)。

## 通过的检查

| 检查 | 结果 | 日志 |
| --- | --- | --- |
| make deploy-check、git diff --check | 脚本/Python、SQL策略指纹、Compose和差异检查通过 | 终端检查 |
| make deploy-test | 20项故障注入通过，执行真实worker/job脚本，使用隔离的命令替身 | .dev/update-script-final-tests.log |
| make deploy-test-services | 真实Caddy维护/恢复、JSON503/Retry-After/no-store、目录原子替换及重启；PG18完整归档恢复通过 | .dev/update-services-final-tests.log |
| 后端回归 | 40项通过，无失败/跳过，含真实PG和Spring权限接口 | .dev/update-production-final-tests.log |
| 最新修复专项 | 10项通过，含补充的无totalCount设备不创建产量元数据检查 | .dev/update-repair-final-tests.log |
| 正式deploy/Dockerfile | linux/amd64完整构建成功，Maven 6分07秒；生产Angular、测试源编译、boot jar和运行镜像完成 | .dev/update-image-final-build.log |
| 新镜像smoke | 独立空库安装、setup状态、生产根页面、产量schema版本2、MQTT CONNECT通过 | .dev/update-image-final-smoke.log |

本地验证镜像为thingsboard-update-validation:20261015-final，Docker报告的镜像ID为sha256:d78ccf6f7b74477e4f6c3d41f00c1c26e510bc2a7cb4bc59119d10b5fdac8581；此镜像未推送，不能将该ID当成生产仓库发布digest。明天make deploy会按实际发布标签构建/推送并记录仓库digest。

后端回归和最新修复专项有重叠，不将两者相加当成不重复测试数量。Docker构建保留测试源编译但不执行测试，测试执行由上述Maven检查承担。

## 故障与一致性覆盖

- 兼容更新在线备份先于停止；在线备份失败旧服务保持运行。
- 新版启动/公网验证失败恢复旧应用和代理，不覆盖数据库。
- 停止/启动中被kill后，下一次发布核对状态并恢复后重试。
- 迁移失败及未知旧镜像启动后失败进入人工恢复，不自动回退数据库。
- 同版本同digest幂等重试；同名不同digest拒绝；策略哈希不符、未来扩展schema、PG/应用数据库变更在停止前拒绝。
- 已审阅legacy digest回退使用当前策略及维护模板；未审阅镜像不放行。
- 后台job退出启动SSH会话后继续运行，记录输出和退出码。
- 产量中间缺口、首次计算失败、分页/重复修复、重启检查点、源失败退避、计算异常原子回滚、回算续跑、设备删除清理。
- 已落库的显式计数覆盖计算失败可修复；设备revision校验阻止过期快照覆盖已完成的新计算。
- 原有单位、班次、速度、永久大屏链接和租户/设备权限接口回归。

## 验证边界

worker/job的故障注入使用命令替身，不能当成真实生产一整次更新。Caddy、PG恢复与最终应用镜像分别做了真实运行验证，现场仍需核对实际旧digest/配置、发布耗时、设备重连和遥测落库。

MQTT探测验证协议接入，未使用生产设备令牌写入生产数据。没有新增外部队列、双节点、设备固件缓存、定时备份或采购资源。in-memory尚未落库消息、设备未上传记录及已过期原始数据不能由产量修复恢复；单机仍有重启窗口。
