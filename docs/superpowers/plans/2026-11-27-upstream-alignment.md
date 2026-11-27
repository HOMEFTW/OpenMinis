# 2026-11-27 上游对齐计划

## 基线与范围

- 官方最新稳定 Release：Android 1.15 / build37，tag `1.15`，源码提交 `6eda5523a9c14fa8edc4fd9fb72e643fe47f23be`。
- 官方最新预发布：Android 1.16-beta.9 / build47，tag `0-beta-build47`，源码提交 `1a71df0f381cefde80bf912b723de6f9831bc177`。
- build47 标签的公开 Android 源码仍声明 1.15 / 37，相对稳定标签仅多 3 个 Tailscale 维护提交、9 个文件。Beta 发布说明中的其他功能不能据此认定已开源或已经合并。
- 先补齐公开的 1.15 网络维护修复。此前“暂不同步 Beta”的要求继续有效；Beta 新功能范围待用户选择。
- 保留 fork 的 1.17.0 / 32、品牌/背景、Forge、原生 DeepSeek、GPT 档位与 Fast、会话通信，以及现有未提交修复。不包含 Git 提交、推送或 Release 发布。

## 实施顺序

1. 保存官方 Release/提交元数据及同步前工作树备份（已完成，位于 `D:/Code/.tools/openminis-upstream-20261127/`）。
2. 按上游差异移植 8 个网络源码、测试及路由规范文件，保留 `tsnet.go` 的 fork 适配，不同步上游版本号。
3. 执行 Go 网络回归，覆盖出口节点域名 IPv4 优先、IPv6 回退、本机回环直连、离线节点拒绝连接、Shell AAAA 过滤。
4. 使用 `scripts/build_rclone_windows.ps1` 重建 ARM64 `rclone.aar`，再用 `scripts/build_android_windows.ps1` 打包并执行相关 Android 回归。
5. 核验 APK 版本、签名、ZIP/对齐、原生库与 Forge 资源。记录已验证范围及仍需真机的 Tailscale 互通测试。

## 验收

- 公开上游网络补丁无遗漏，本地兼容修改及同步前用户改动得到保留。
- Go 定向网络测试通过；本地 APK 内确实包含新编译原生库。
- 发送消息/Forge/Fast 相关验证按改动影响复用或重跑，明确真实设备及真实模型接口的测试边界。
- Beta 功能仅在用户明确选择范围后增加到本计划；不以旧源码标签宣称完成 Beta 全功能对齐。

## 执行状态

公开 1.15 网络补丁的步骤 1–5 已完成。Go 83 项、Android JVM 116 项定向测试全部通过；原生库和 APK 已重建并校验。实际结果、产物、Beta 更新摘要及已定位的 Markdown 缺陷见 `docs/fixes/2026-11-27-upstream-alignment.md`。Beta 扩展范围仍待用户选择。
