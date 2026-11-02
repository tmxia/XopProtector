# 墨枢加固台 · 构建与使用

基于 [xopJack/XopProtector](https://github.com/xopJack/XopProtector) 移植的 Android 端 APK 加固软件。

- **compileSdk / targetSdk = 37（Android 17）**
- 水墨风界面，内置文件选择器
- 双壳架构：完整壳（强防护，arm）/ 精简壳（含 x86，模拟器可用）

## 模块

| 模块 | 说明 |
|------|------|
| `:app` | 水墨风 UI + 加固调度 |
| `:packer-core` | 加固引擎（XopProtector `:packer` 全量源码） |
| `:shell` | 运行时壳 Java 层 → D8 编译为 shell.dex |
| `native/` | 壳 C++ 源码（**不参与构建**，so 已预编译进 assets） |

## 双壳架构

| 壳 | 目录 | ABI | 能力 |
|----|------|-----|------|
| 完整壳 | `assets/xop-shell-full/` | arm64-v8a, armeabi-v7a | DEX 加密 + 方法抽取 + **SO .text 加密** + **PVM2** + **RASP** |
| 精简壳 | `assets/xop-shell-lite/` | arm64-v8a, armeabi-v7a, **x86, x86_64** | DEX 加密 + 方法抽取 |

界面「含 x86 库的 APK（精简壳）」开关决定用哪套：
- 关（默认）：完整壳，含 x86 库的目标 APK 会被拒绝
- 开：精简壳，四 ABI 齐全，自动关闭 SO 保护与 PVM2（精简壳无对应运行时）

完整壳依赖 `libshadowhook.so` + `libc++_shared.so`，三者必须同 ABI 一起注入（APP 已带依赖自检）。

## 高级选项（界面「细节」面板）

| 开关 | 默认 | 说明 |
|------|------|------|
| SO 代码加密 | 开 | 加密业务 so 的 `.text` 段，几乎无副作用 |
| 资源路径混淆 | 关 | 重写 resources.arsc 的 res/ 路径，多数应用安全 |
| 网络防护 | 关 | 代理/VPN 检测（误报只降级不崩溃） |
| SO 加载诊断 | 关 | 排障日志 |
| assets 加密 | 关 | ⚠️ 目标应用必须改用 `ProtectorAssets.open()`，否则读不到资源 |
| 运行时自检 RASP | Block | 点按切换 仅告警 → 降级 → Block。root/Frida 设备上 Block 会终止目标应用 |
| 含 x86 库的 APK | 关 | 见上表 |

## 构建

```bash
cd /sdcard/AndroidCSProjects/XopProtector
export ANDROID_HOME=/opt/android-sdk
/opt/gradle-9.0.0/bin/gradle :app:assembleRelease --no-daemon
```

- 需要 **Android SDK Platform 37**（Android 17）。
- **不需要 NDK / CMake**：壳 so 已预编译，构建约 2 分钟。
- 不要用 `./gradlew`（wrapper 下载在本机会因缺 CA 证书失败）。
- 发布签名 `/root/.android/xop-release.jks` 不存在时自动回退 debug 签名。

产物：`app/build/outputs/apk/release/app-release.apk`

## 加固流程

1. 「浏览 APK」→ 内置选卷器（需「所有文件」权限，或切系统选择器）
2. 选笔法：**衡**（加密+支付 VMP）/ **深**（抽取+SO 保护）/ **极**（近全量抽取）
3. 展开「细节」按需开高级选项
4. 落墨加固 → 完成可「安装」/「分享」

## 安全护栏

- **禁止加固墨枢自身**（会覆盖自毁，已拦截）
- **产物自检**：校验每个 ABI 的 `libprotector.so` 依赖是否齐全，缺依赖直接报错不产出坏包

## 重新编译壳（可选）

完整壳源码 `native/src/main/cpp`（Dobby / bytehook / shadowhook）：
需要 compileSdk 37、可执行挂载点（不能在 `/sdcard` 编，prefab 脚本无法执行）。
精简壳源码参考 git 历史，无第三方依赖、四 ABI 均可编。
编好后 `llvm-strip --strip-unneeded` 再替换对应 assets 目录。
