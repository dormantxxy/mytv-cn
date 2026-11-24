# MYTV CN

面向手机、平板和电视的沉浸式抖音视频/直播客户端。项目以 `myDV 2.1.37` 的公开资料和 APK 静态行为证据为基线，采用独立重写源码的方式实现，运行包名为 `com.mytv.cn`。

> 当前状态：持续开发中。源码已接通推荐、精选、直播、搜索、个人页、播放器、扫码登录、局域网推送、画中画和基础 TV 遥控操作，构建与真机回归仍以 GitHub Actions 结果为准。

## 特点

- 抖音式全屏沉浸视频界面
- 推荐、关注、精选频道与直播列表
- 搜索、评论、收藏、点赞、历史和个人主页
- Media3 视频/HLS 播放与画中画
- 手机触屏、平板横竖屏及电视遥控焦点支持
- 扫码登录、手动 Cookie 和局域网凭据推送
- Release 启用 R8 与资源压缩

## 构建

需要 JDK 17 与 Android SDK：

```bash
gradle assembleDebug
gradle assembleRelease
```

Release 固定签名通过以下 Gradle 属性或同名环境变量提供，keystore 不应提交到仓库：

- `MYTV_KEYSTORE`
- `MYTV_STORE_PASSWORD`
- `MYTV_KEY_ALIAS`
- `MYTV_KEY_PASSWORD`

## 安全说明

仓库不包含任何用户 Cookie、GitHub Token、签名私钥或密码。登录信息只能由使用者在本机扫码、粘贴或通过同一局域网推送。

## 来源与许可

- 行为基线与部分资源来源：[mytv-android/myDV](https://github.com/mytv-android/myDV)
- 本项目为独立重写，不隶属于抖音或字节跳动。
- 项目按 [GPL-3.0](LICENSE) 发布，详细归属见 [NOTICE.md](NOTICE.md)。
