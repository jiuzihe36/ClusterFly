# 飞屏选择器（ClusterFly）

> 车机仪表投屏工具：**在列表里点哪个 App，就把哪个 App 飞到仪表屏**。
> 双通道自动选择，纯 Android 标准 API，免 root。

## 下载

| 版本 | 直链 |
|---|---|
| **v2.0**（当前） | https://github.com/jiuzihe36/ClusterFly/releases/download/v2.0/ClusterFly-v2.0.apk |

> APK 亦可从仓库 Actions artifact `clustermirror-debug` 获取（每次 push main 自动出新包）。
> 第三方改版高德等大体积安装包（>100MB）GitHub 无法托管，随本地资料包分发。

## 功能

- **已安装 App 列表**：图标 + 名称，支持搜索过滤
- **点选即飞**，两条通道自动回退：
  - **通道① 直投**：`ActivityOptions.setLaunchDisplayId()` 把目标 App 直接启动到仪表屏
  - **通道② 镜像**：目标 App 在中控前台运行，`MediaProjection` 虚拟屏**零编解码**直连
    仪表 `Presentation` 的 Surface（保持宽高比 letterbox，不拉伸变形）
  - 逻辑：先试①，异常自动切②；若①"无异常但仪表无画面"，勾选**「强制镜像」**再点一次
- **录屏授权**：系统弹窗一次，之后常驻
- **停止飞屏**：一键释放虚拟屏 / 授权 / Presentation
- **诊断区**：①列屏幕 ②试画仪表 ③全部释放 —— 上车先跑①，输出可直接贴回反馈

## 安装（U 盘工程模式）

1. U 盘格式化 **FAT32**，建目录 `T1EUpdateNavi/APP`（大小写不能错）
2. 把 `app-debug.apk` 放进 APP 目录（可改名，后缀必须 `.apk`）
3. 车机：设置 → 系统设置 → 存储空间 → **连点右下角约 6 次** → 输入工程密码
   → 升级 → app 升级 → 选中 → 安装
4. 覆盖安装注意：CI 每次构建使用全新 debug 签名，**旧版必须先卸载**

## 使用

1. 打开「飞屏选择器」→ 确认顶部显示 `✅ 仪表屏 displayId=…`
2. 点列表里的目标 App
3. 首次镜像会弹系统录屏授权 → 点「开始」
4. 看仪表屏；直投没画面就勾「强制镜像」重试
5. 「停止飞屏」收场

## 构建（GitHub Actions）

推送 `main` 分支即自动构建（`.github/workflows/build.yml`）：

- JDK 17 + 手动 cmdline-tools（`setup-android` action 已坏）
- compileSdk 28 / minSdk 28 / targetSdk 28（车机是 Android 9 = API 28，勿调高）
- Java 8 源码级别（AGP 在 compileSdk 28 下拒绝 Java 9+ 语法）
- 纯 framework API，**零第三方依赖**
- 产物：artifact `clustermirror-debug`

本地无 Android SDK，不要在本机跑 gradle。

## 已知边界（如实）

- 直投通道依赖仪表屏允许起 Activity（`activities_on_secondary_displays`），仅实机能定论
- 镜像为**整屏镜像**（Android 9 无法单应用窗口捕获）
- 仪表屏若被系统标 restricted，Presentation 会被拒 → 诊断②可复现，日志贴 issue
- 录屏授权被系统回收时自动停止（`MediaProjection.Callback.onStop`）

## 目录

```
app/src/main/java/com/hermes/clustermirror/
├── MainActivity.java          # 列表 + 双通道飞屏 + 诊断（本体）
└── ClusterMirrorService.java  # 仪表屏发现 / Presentation / H.264 编码（探测版保留）
```

## 声明

个人逆向研究配套工具，仅供学习研究；不含任何原厂二进制与私有资源。
