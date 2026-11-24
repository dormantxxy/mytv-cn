# 调研记录

## 2026-11-25 现场基线
- 设备已安装 `com.github.mytv.dv`，入口 `.MainActivity`，另有 `.PipActionReceiver`。
- 版本 `2.1.37`（versionCode 37），minSdk 23，targetSdk 37，仅一个 base APK，ABI 为 arm64-v8a。
- 权限：INTERNET、ACCESS_NETWORK_STATE、ACCESS_WIFI_STATE、REQUEST_INSTALL_PACKAGES、WAKE_LOCK。
- Provider：AndroidX Startup 与 FileProvider；存在画中画播放/暂停/下一条广播动作。
- 旧反编译目录显示业务模型覆盖短视频、直播、搜索、热榜、用户作品、合集、收藏、评论、弹幕、通知、清晰度等，说明功能面远超单页播放器。
- APK assets 至少包含 QuickJS/签名相关脚本、PublicSuffixDatabase 和 web 资源；需从当前 APK 重新提取并做完整清单，而不是依赖旧目录猜测。
- 既有 `mytv源码复刻` 仅为未完成骨架：包名不符、Release 未启用混淆，仅有部分模型；不得当作完成品。
- 既有 GitHub 仓库为 `dormantxxy/mytv-dv`，仓库已存在；本次需先核对远端状态，再决定覆盖式续作或保留历史重建。

## 待确认
- 上游公开仓库及 2.1.37 对应提交/标签。
- 原 APK 的完整页面、设置项、持久化键与网络端点。
- GitHub 仓库可见性、默认分支、现有 Release/Actions 状态。

## 2026-11-25 GitHub 定位
- 目标仓库 `dormantxxy/mytv-dv` 已确认存在，为私有仓库，默认分支 `main`；当前远端仅有早期骨架提交。
- GitHub 全站未发现包含 `com.github.mytv.dv` 构建配置的公开原始工程，原作者源码很可能未公开。
- 唯一资产名 `abogus_dy.js` 指向三个有价值的公开参考：`a71654911591/DouYin`（重建规格）、`zhougeihong/douyin`（重建交接资料）及 `Zxxy326/TVDesk`（AGPL-3.0 的抖音模块）。
- `BaimingX/mydv-speed-selector-build` 是 myDV APK 补丁/构建仓，可用于确认行为差异，不应直接当作可维护源码。
- 后续先审计这些参考仓的许可证与实现完整度；可复用代码必须满足许可证，未知许可内容只作事实证据。

## 2026-11-25 上游与协议结论
- 已定位官方公开仓库 `mytv-android/myDV`，许可证 GPL-3.0；公开仓不含业务源码，CI 从私有 `myDV-Internal` 构建。复刻仓发布源码时应沿用 GPL-3.0，并标明独立重写及资产来源。
- 设备 APK 与官方 Release `V2.1.37` 完全一致：大小 11,649,438 字节，SHA-256 `7af8303338d94d7314981e5b347e8a2251741732162e7a335be3065d03387d45`。
- 官方当前已有 `V2.4.50`，但本任务的可重复基线仍固定为设备上的 `V2.1.37`；后续版本变更日志只用于排查功能边界，不默默改变验收版本。
- 当前 APK 共 499 个 ZIP 条目、15 个 assets 文件；已逐文件提取和生成 SHA-256 清单。旧反编译 APK 哈希相同，可直接作为该版本静态证据。
- 外部重建资料已实测打通 a_bogus、关注直播列表与 HLS；关键修正包括直播接口使用独立公共参数、`scene=aweme_pc_follow_top`、列表响应自带 HLS、`stream_data` 需要二次 JSON 解析。
- 外部参考资料中夹带了第三方登录 Cookie。它只保留在被 `.gitignore` 排除的本地参考目录，禁止读取为应用默认值、禁止提交、禁止写入日志或发布产物。应用只提供用户自行配置/扫码登录。
- `zhougeihong/douyin` 是词典笔 miniapp，不是 Android 工程；其中的接口实验可交叉验证，但代码不能直接当 Android 实现。
