# 使用本机 Windows 环境打包

开发时的 Windows 工作副本已准备好原生依赖。新克隆的仓库必须先按 `BUILDING.md` 准备被 Git 忽略的依赖；以下为已验证的本机环境：

- Windows Zulu JDK 21、已有 Gradle 8.13、Android SDK 36。
- PRoot 使用已有 Windows NDK 27.0.12077973、MinGW GNU Make 和 Git Bash 从源码交叉编译为 ARM64。
- 原有 Termux 64/32 位加载器保持不变，已通过项目内置 SHA-256 校验。
- Alpine 3.21.3 aarch64 minirootfs 已下载并通过官方 SHA-256 校验。
- `rclone.aar` 使用工作目录内的便携 Go 1.27.1 和 gomobile 从 `deps/rclone-mobile` 编译，含真实的 ARM64 `libgojni.so`，没有用空壳实现代替备份功能。
- Gradle 自动补装了项目声明的 CMake 3.22.1。

## 首次模型接入构建记录（历史版本）

已通过 `:app:assembleDebug` 及完整 Android 工程内的 AstraImage25Test（7 项，0 失败、0 错误、0 跳过）。

- APK 大小：64,446,184 字节，约 61.5 MiB。
- 包名：`com.openminis.app`；版本：1.13（versionCode 25）；minSdk 26；ARM64。
- APK v2 签名校验通过。
- ZIP 完整性、原生依赖的 ELF 架构、DEX 中三个新增模型 ID、Termux 加载器原始字节均已检查。
- 打包器将 Alpine gzip 文件展开为 `assets/alpine-minirootfs.tar`；RootfsManager 已有对应读取逻辑，包内 tar 与官方归档解压结果逐字节一致。
- SHA-256：`468824159895789416305ea1e48eeb3840e98a913ed4fb1184ca11ff5389c933`。

尚未进行真机安装、沙箱运行或真实模型账号调用测试。

最新增强版的 1301 项完整单测和 APK 验证结果见 [项目首页](../README.md#验证情况)，下列步骤适用于依赖已就绪的环境。

## 后续修改 Kotlin 后重新打包

在仓库根目录打开 PowerShell：

```powershell
.\scripts\build_android_windows.ps1
```

需要运行 Astra/Image 2.5 回归测试时：

```powershell
.\scripts\build_android_windows.ps1 -RunModelTests
```

脚本优先使用 PATH 中的 Gradle，其次使用用户缓存中的 Gradle 8.13；也可用 `-GradlePath '完整的gradle.bat路径'` 指定。没有可用版本时，使用仓库的 Gradle Wrapper 下载它声明的版本。

预期产物：`src/android/app/build/outputs/apk/debug/app-debug.apk`。它使用调试签名，支持 Android 8.0+ ARM64 设备。构建成功不代表已完成真机运行或真实模型账号验证。

## 本次 Windows 兼容处理

- Gradle 的 debug skill 生成步骤可通过 `minisBashPath` 选择 Git Bash，避免误调用 Windows 的 WSL Bash 启动器。
- PowerShell 入口自动寻找 Git Bash，检查原生依赖，执行 Gradle，并输出 APK 的 SHA-256。
- debug skill 的 shell 脚本使用 LF 换行，避免 Windows 检出 CRLF 导致 Bash 报错。

本机开发使用的临时编译目录没有上传到仓库。上述脚本只负责重新打包，不自动重建这些原生依赖。`rclone.aar`、PRoot 和 Alpine 都是被 Git 忽略的本地构建产物；复制工作副本时应保留它们。重新克隆仓库后仍需按 `BUILDING.md` 准备依赖，或重新执行对应的原生构建流程。
