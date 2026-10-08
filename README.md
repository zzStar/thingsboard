![banner](https://github.com/user-attachments/assets/526ba5f6-0944-4613-aac4-19a76158aa48)


<div align="center">

# All-in-one IoT platform for data collection, processing, visualization, and device management.

</div>
<br>
<div align="center">
 
💡 [Get started](https://thingsboard.io/docs/pe/getting-started/)&ensp;•&ensp;🌐 [Website](https://thingsboard.io/)&ensp;•&ensp;📚 [Documentation](https://thingsboard.io/docs/pe/)&ensp;•&ensp;📔 [Blog](https://thingsboard.io/blog/)&ensp;•&ensp;🔗 [LinkedIn](https://www.linkedin.com/company/thingsboard/posts/?feedView=all)

</div>

## 🚀 Installation options

Install ThingsBoard [on-premises](https://thingsboard.io/docs/pe/installation/) or use [ThingsBoard Cloud](https://thingsboard.io/installations/).

### 本地源码开发

准备 JDK 25、Maven 3.9.x、Docker Desktop（已启动且支持 Compose v2），在项目根目录执行：

```bash
make dev
```

脚本启动独立 PostgreSQL 18 开发数据库（宿主机 15432、库名 thingsboard_dev），增量构建应用，自动安装 Maven 管理的 Node/Yarn 及前端依赖，仅对空数据库初始化，再启动 Java 后端和 Angular 热更新服务。首次构建耗时较长，后续每次启动重新增量构建以包含源码修改。默认预览 http://localhost:4200，后端 http://localhost:8080。

端口冲突时可指定 `TB_DEV_HTTP_PORT=18080 TB_DEV_UI_PORT=14200 TB_DEV_DB_PORT=15433 make dev`，前端代理自动使用指定的后端端口。数据库端口变更不改变数据卷。数据库用户名/密码 postgres 仅用于本机专用开发环境。

Ctrl+C 停止脚本启动的前后端，保留数据库运行；`make dev-down` 停止数据库并保留数据卷。另一终端执行 `make dev-logs` 查看前后端日志，初始化日志在 `.dev/install.log`。如果某个服务启动失败或超时，脚本停止前后端并提示日志位置。

构建过程同时输出到终端和 `.dev/build.log`。Yarn 开启 `--verbose`，显示带时间的请求地址、完成状态和重试；并通过 `--prefer-offline` 优先复用本地依赖缓存。另一终端可执行 `tail -f .dev/build.log`，重点查看 `Performing`、`finished`、`retry`、`network` 行。并发下载日志会交错，最近的请求不一定是最慢请求；链接阶段的文件复制日志也会较多。

开发入口跳过 Angular 生产构建，保留 Node/Yarn 安装和前端依赖，只由 Angular dev server 编译预览。后端就绪检查直连 setup API，不依赖后端静态首页，不受下载代理影响；发布时使用正常生产构建流程。

前端修改自动刷新；Java 修改需 Ctrl+C 后重新 `make dev`，或自行使用 IDE 调试。脚本清除终端继承的 NON_PRODUCTION_USE；本分支默认使用 LocalSubscriptionService，本地订阅策略不需要外部许可证激活，首次访问直接创建系统管理员。显式启用 Spring licensed profile 时恢复原订阅策略。此改动不提供外部 AI 服务额度、报表服务或 Trendz 服务。已有数据库不自动升级，也不清空；升级必须按对应版本流程执行。

### 服务器部署与更新

本机 Docker Desktop 运行并完成阿里云仓库登录后，在项目根目录执行：

```bash
make deploy
```

脚本构建 linux/amd64 生产镜像，在独立临时数据库上检查首次初始化和应用启动，通过后推送至阿里云仓库，部署到 root@47.100.121.136，并验证 https://things.zhigongshulian.com。首次创建数据库；更新先备份，保留数据。同 schema 更新失败自动恢复旧应用。

`make deploy VERSION=版本号` 指定新版本；`make deploy VERSION=已有版本 BUILD=0` 复用仓库镜像；`make deploy-status` 查看状态；`make deploy-logs` 跟踪日志；`make backup` 备份；`make rollback VERSION=旧版本` 回退已发布且 schema 兼容的镜像。详细说明见 [部署文档](docs/DEPLOYMENT.md)。

## 💡 Getting started with ThingsBoard

Check out our [Getting Started guide](https://thingsboard.io/docs/pe/getting-started/) to learn the basics of ThingsBoard and create your first dashboard! You will learn to:

* Connect devices to ThingsBoard
* Push data from devices to ThingsBoard
* Build real-time dashboards
* Create a Customer and assign the dashboard with them.
* Define thresholds and trigger alarms
* Set up notifications via email, SMS, mobile apps, or integrate with third-party services.

## ✨ Features

<table>
  <tr>
    <td width="50%" valign="top">
      <br>
      <div align="center">
        <img src="https://github.com/user-attachments/assets/255cca4f-b111-44e8-99ea-0af55f8e3681" alt="Provision and manage devices and assets" width="378" />
        <h3>Provision and manage <br> devices and assets</h3>
      </div>
      <div align="center">
        <p>Provision, monitor and control your IoT entities in secure way using rich server-side APIs. Define relations between your devices, assets, customers or any other entities.</p>
      </div>
      <br>
      <div align="center">
        <a href="https://thingsboard.io/docs/pe/user-guide/digital-twins/entities/">Read more ➜</a>
      </div>
      <br>
    </td>
    <td width="50%" valign="top">
      <br>
      <div align="center">
        <img src="https://github.com/user-attachments/assets/24b41d10-150a-42dd-ab1a-32ac9b5978c1" alt="Collect and visualize your data" width="378" />
        <h3>Collect and visualize <br> your data</h3>
      </div>
      <div align="center">
        <p>Collect and store telemetry data in scalable and fault-tolerant way. Visualize your data with built-in or custom widgets and flexible dashboards. Share dashboards with your customers.</p>
      </div>
      <br>
      <div align="center">
        <a href="https://thingsboard.io/iot-data-visualization/">Read more ➜</a>
      </div>
      <br>
    </td>
  </tr>
  <tr>
    <td width="50%" valign="top">
      <br>
      <div align="center">
        <img src="https://github.com/user-attachments/assets/6f2a6dd2-7b33-4d17-8b92-d1f995adda2c" alt="SCADA Dashboards" width="378" />
        <h3>SCADA Dashboards</h3>
      </div>
      <div align="center">
        <p>Monitor and control your industrial processes in real time with SCADA. Use SCADA symbols on dashboards to create and manage any workflow, offering full flexibility to design and oversee operations according to your requirements.</p>
      </div>
      <br>
      <div align="center">
        <a href="https://thingsboard.io/use-cases/scada/">Read more ➜</a>
      </div>
      <br>
    </td>
    <td width="50%" valign="top">
      <br>
      <div align="center">
        <img src="https://github.com/user-attachments/assets/c23dcc9b-aeba-40ef-9973-49b953fc1257" alt="Process and React" width="378" />
        <h3>Process and React</h3>
      </div>
      <div align="center">
        <p>Turn raw telemetry into meaningful metrics with calculated fields: compute values, aggregates, and KPIs from device and asset data in real time. Combine them with rule chains to transform your data and raise alarms on telemetry events, attribute updates, device inactivity, and user actions.<br></p>
      </div>
      <br>
      <br>
      <div align="center">
        <a href="https://thingsboard.io/docs/pe/user-guide/calculated-fields/">Read more ➜</a>
      </div>
      <br>
    </td>
  </tr>
</table>

## ⚙️ Powerful IoT Rule Engine

ThingsBoard allows you to create complex [Rule Chains](https://thingsboard.io/docs/pe/user-guide/rule-engine/) to process data from your devices and match your application-specific use cases.

[![IoT Rule Engine](https://github.com/user-attachments/assets/43d21dc9-0e18-4f1b-8f9a-b72004e12f07 "IoT Rule Engine")](https://thingsboard.io/docs/pe/user-guide/rule-engine/)

<div align="center">

[**Read more about Rule Engine ➜**](https://thingsboard.io/docs/pe/user-guide/rule-engine/)

</div>

## 📦 Real-Time IoT Dashboards

ThingsBoard is a scalable, user-friendly, and device-agnostic IoT platform that speeds up time-to-market with powerful built-in solution templates. It enables data collection and analysis from any devices, saving resources on routine tasks and letting you focus on your solution’s unique aspects. See more of our use cases [here](https://thingsboard.io/iot-use-cases/).

[**Smart energy**](https://thingsboard.io/use-cases/smart-energy/)

[![Smart energy](https://github.com/user-attachments/assets/2a0abf13-6dc5-4f5e-9c30-1aea1d39af1e "Smart energy")](https://thingsboard.io/use-cases/smart-energy/)

[**SCADA swimming pool**](https://thingsboard.io/use-cases/scada/)

[![SCADA Swimming pool](https://github.com/user-attachments/assets/68fd9e29-99f1-4c16-8c4c-476f4ccb20c0 "SCADA Swimming pool")](https://thingsboard.io/use-cases/scada/)

[**Site fleet tracking**](https://thingsboard.io/use-cases/site-fleet-tracking/)

[![Site fleet tracking](https://github.com/user-attachments/assets/d6ce0766-b138-4a42-86aa-7112a543026c "Site fleet tracking")](https://thingsboard.io/use-cases/site-fleet-tracking/)

[**Smart farming**](https://thingsboard.io/use-cases/smart-farming/)

[![Smart farming](https://github.com/user-attachments/assets/56b84c99-ef24-44e5-a903-b925b7f9d142 "Smart farming")](https://thingsboard.io/use-cases/smart-farming/)

[**Smart metering**](https://thingsboard.io/smart-metering/)

[![Smart metering](https://github.com/user-attachments/assets/adc05e3d-397c-48ef-bed6-535bbd698455 "Smart metering")](https://thingsboard.io/smart-metering/)

<div align="center">

[**Check more of our use cases ➜**](https://thingsboard.io/iot-use-cases/)

</div>

## 🫶 Support

To get support, please visit our [GitHub issues page](https://github.com/thingsboard/thingsboard/issues)

## 📄 License

Starting with version 4.4, ThingsBoard is licensed under the [Business Source License 1.1](https://github.com/thingsboard/thingsboard/blob/master/LICENSE) (BUSL), a source-available license. You can read, build, modify, fork, and redistribute the code, and use it freely for development and testing. Production use is free within the limits of the Additional Use Grant; beyond them, or to remove ThingsBoard branding, a commercial license is required. Each release converts to Apache 2.0 four years after it ships.

Versions released before 4.4 remain under the Apache 2.0 License.
