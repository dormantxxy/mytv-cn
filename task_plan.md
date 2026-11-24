# MYTV 抖音版完整移植计划

## 目标
以设备中 `com.github.mytv.dv` 2.1.37 为行为基线，交付仅面向 Android 手机、可维护源码的 App：包名 `com.mytv.cn`，原 assets 完整保留，功能不漏项，界面改为克制的抖音式沉浸体验；Release 启用 R8、使用固定签名，并由 GitHub Actions 构建和发布 GitHub Release。

## 验收标准
- 能从全新检出构建 Debug 与签名 Release APK。
- `applicationId` 与运行时包名均为 `com.mytv.cn`。
- 建立逐项功能矩阵，并对 2.1.37 可观察功能完成实现与回归。
- 原 APK 的 assets 有来源清单和哈希核对，不遗漏非生成资源。
- 主要页面具有抖音式全屏内容、低干扰导航、触屏与返回键可用性。
- Release `minifyEnabled=true`、`shrinkResources=true`，R8 构建通过，必要反射/序列化规则完整。
- 固定 keystore 不进仓库；CI 使用固定签名 Secret，产物验签指纹稳定。
- 推送源码，CI 成功，并发布带签名 APK 与校验值的 Release。

## 范围约束
- 不复制未知许可的反编译源码；优先寻找上游源码，缺失部分按行为和公开接口独立重写。
- assets 按用户要求沿用；发布前保留来源/许可风险说明。
- 目标平台仅 Android 手机；TV/Leanback、遥控焦点、平板专项布局与局域网电视推送不再纳入范围。
- 不改动设备上原 App 的数据，不卸载任何应用。

## Phases

### Phase 1: 现场取证与上游源码定位
**Status:** complete
- 确认安装包版本、组件、资源和依赖。
- 检索上游仓库/版本标签并核对是否与 APK 同源。
- 在本项目中保存原 APK 与资源清单。

### Phase 2: 功能矩阵与架构基线
**Status:** complete
- 从源码、Manifest、界面和网络调用提取完整功能清单。
- 定义模块边界、数据流和逐项验收步骤。

### Phase 3: 完整功能移植
**Status:** in_progress
- 改为 `com.mytv.cn`，恢复所有页面、服务、播放器、网络和本地状态。
- 接入完整 assets，处理包名相关 Provider/PiP/Intent。

### Phase 4: 抖音式 UI 改造
**Status:** pending
- 全屏沉浸视频/直播、顶部频道、底部导航、右侧操作列和抽屉层。
- 保证手机触屏、纵向沉浸体验、返回行为与无障碍。

### Phase 5: R8、固定签名与供应链配置
**Status:** pending
- Release 开启 R8/资源压缩并做混淆回归。
- 生成一次性固定签名，配置 GitHub Secrets/环境保护与产物验签。

### Phase 6: 构建、真机与功能回归
**Status:** pending
- 单元/集成检查、Debug/Release 构建。
- 安装到 Android 12 手机，按功能矩阵回归并记录证据。

### Phase 7: 推送、CI 与 Release 发布
**Status:** pending
- 清理敏感信息，提交并推送。
- 触发 CI，确认成功，创建版本标签和 GitHub Release。

## 当前决策
- 新项目目录独立于既有 `mytv逆向`、`mytv源码复刻`，旧目录只作只读参考。
- 采用“上游源码优先、反编译仅作行为证据”的实现路径。

## Errors Encountered
| Error | Attempt | Resolution |
|---|---:|---|
| `git fetch '../mytv源码复刻' main` 在该 Git 环境把本地 Unicode 路径误交给 `git-upload-pack`，无法执行 | 1 | 改用本地 bundle 传递提交，不重复该命令 |
| Git HTTPS 在 `ls-remote` 首个仓库时连接 GitHub 443 超时 | 1 | 不原样重试 Git；改走已验证可用的 codeload HTTPS 压缩包，并先复测 DNS/TCP |
| `run_shell` 的 root 子环境无法执行扩展目录中的 `python3`，但 APK 已复制成功 | 1 | 文件分析改用可访问工具链扩展的 `run_cli`，系统包读取仍用 `run_shell` |
| GitHub 匿名 Contents API 下载 TVDesk 参考文件时达到速率上限 | 1 | 已取得核心文件；后续若仍需下载则使用授权 API，不重复匿名请求 |
