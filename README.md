# UU Remote Dark

面向网易 UU 远程 Android 客户端的应用内深色模式 LSPosed 模块。

当前目标是 `com.netease.uuremote` 4.35.0：

- 仅作用于 UU 远程，不作用于 System Framework 或其它应用。
- 主题选项为“跟随系统”和“深色模式”。默认跟随系统。
- 只处理 UU 自身的 Android View、Material 和 Compose 界面。
- 不处理内嵌网页内容、远程桌面画面、串流 Surface/Texture 或视频帧。
- 主题切换通过进程内状态和 Compose configuration dispatch 原位刷新，目标是不重启 Activity、不中断远控会话。

## 状态

项目处于早期开发阶段。当前提交包含：

- 现代 libxposed API 101 兼容的静态作用域模块骨架；
- Remote Preferences 配置 Activity；
- 主题状态、调色板、颜色对比度和原位 View/Compose 刷新基础设施；
- UU 4.35.0 的兼容门控与保守资源映射起点。

UU 的混淆类、Compose 调色板和“我的 → 设置”页面仍需在真机上逐项验证。未知 UU 版本会明确拒绝加载，不会套用未经验证的 Hook。

## 构建

环境要求：JDK 17、Android SDK 37（本机编译；目标测试基线仍为 API 36）、可访问 Maven Central 的 Gradle 环境。

```sh
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest
# 也可运行完整的本地 APK 门禁（单测、lint、打包、Xposed 元数据、签名）
./scripts/verify-artifact.sh
```

`app-debug.apk` 是可直接安装到测试设备的开发候选；公开仓库不包含 UU APK、反编译源码、账号截图或签名密钥。

## 安装和作用域

安装 APK 后，在 Vector/LSPosed 中只启用 `com.netease.uuremote` 作用域。不要勾选 System Framework、System UI 或其它应用。模块会对 UU 4.35.0 的版本、Base APK SHA-256 和签名证书 SHA-256 做精确门禁，未知构建会保持原界面不变。

## 许可

本项目使用 Apache-2.0。参考项目、依赖和许可证记录见 `docs/research.md`。
