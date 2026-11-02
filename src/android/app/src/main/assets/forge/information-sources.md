# 信息统计源码依据

核对日期：2026-10-27。第三方许可见同目录 THIRD-PARTY-NOTICES.txt。

| 上游 | 读取的提交 | 使用范围 |
| --- | --- | --- |
| https://github.com/zoefix/agent-speed | a94a67fcc78e5d8eea5f82d8d5e737bccf994e2c | spec/METRICS.md、windows/src/AgentSpeed.Core/Parsing/CodexParser.cs：现代/旧版 usage、去重、复制历史、响应计时和归一化 |
| https://github.com/xqy2006/ModelTrace | 6cd4a8ff0f3f4e49d6d6969dd2fbadfa1569fde2 | static/fingerprint-core.js、static/data/unified_bank.json：全局稳健数字指纹、家族概率与校准 |
| https://github.com/kiyoakii/is-gpt-nerfed | ff0d7c0c8fdc8713273b6570b1ada1838eaad84c | plugin/skills/is-gpt-nerfed/scripts/nerfed：被动模型/思考强度/窗口检查、已应用设置区分、三份数字样本检测及本地结果导入 |

ModelTrace 指纹库 SHA-256：`eeab6ff54d6ffb2b294ebfd6cce25bce387f7a268b66759144f64c16f6c66b9f`，与上述上游提交逐字节一致。

Forge 侧栏只统计当前 Codex 对话，不宣称覆盖 Agent Speed 的全部 CLI/压缩日志格式。被动检查不消耗模型额度，不按名称推断升级/降级；未见设置记录的变更只提示复核。主动数字检测由用户点击启动，在空工作目录下顺序创建三个独立临时会话，沿用当前模型、提供商与思考强度，不复制主任务历史和项目指令；拒绝实际工具调用，不自动暂停用户主任务。

本库未收录 `gpt-6.1-sol`。对它显示“参考库未收录”，最近候选不构成模型替换证据。来源算法的一、二、三组样本分析已与上游 JavaScript 输出完整比对。

## OpenMinis Android 移植

移植日期：2026-11-02。来源目录 `D:/Code/codex-forge` 在本轮仅作只读参考。
原项目 Forge 0.3.2（MIT，HOMEFTW）许可保存为 `information-forge-license.txt`，上述第三方许可原文保存为 `information-notices.txt`。

读取了 runtime/information-stats.cjs、information-observation.cjs、information-modeltrace.cjs、
information-bank.json、information-probe.cjs、ui/information-stats.jsx/css 和本来源说明。
参考库字节不变；算法移植为 ForgeInformationFingerprint.kt，三视图 React UI 保留原 props。

Android 数据改用 OpenMinis 原有 Room/StoredUsage 与实际请求 collector，不读取电脑日志、
不采用 Codex 桌面窗口常量、不通过文本长度估算 token 或响应耗时。
主动检测由 Android ProviderFactory 在手机本机独立完成，不需要电脑或桌面 Codex 服务。
详细统计口径、接入 hook、隔离规则和验证限制见 `information-integration.md`。
