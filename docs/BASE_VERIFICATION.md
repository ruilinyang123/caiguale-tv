# 基础版本地静态验证记录（未构建）

本文件只记录**在 NAS 上实际执行过**的检查与结果。所有检查均不调用 Gradle、不产出 APK。

## 已执行

| 检查 | 命令 | 结果 |
| --- | --- | --- |
| 源码策略门禁 | `python3 ci/check_exo_base_source.py` | 通过（无 AAR/so/dex、无排除 SDK import、无未脱敏演示凭证、无定制 media3/nextlib、变体白名单与包名正确） |
| 验证器单元测试 | `python3 ci/test_base_policy.py` | 8 项通过（用合成 ZIP 覆盖：正常包、MPV/IJK so、双 ABI、迅雷类字符串、缺 ExoPlayer、APK 数量、AAR 检测、仓库树自检） |
| 前端脚本语法 | `node --check app/src/main/assets/caiguale/app.js` | 通过 |
| 演示页脚本语法 | 三个 webhome-devkit 演示页的每个内联 `<script>` 逐个 `node --check` | 全部通过（脱敏后结构未被破坏） |
| 脱敏有效性 | 从原工程提取 4 个演示凭证值并全树搜索 | 0 命中 |
| 原工程未被修改 | 与原工程 10,256 个文件的全量 SHA-256 快照比对 | 完全一致 |
| 远端保护文件 | `caiguale-debug.yml`、`ci/verify_caiguale_apk.py`、`docs/DEPENDENCY_AUDIT.md`、`.gitignore`、`README.md` | 哈希与远端 main 一致 |
| Java 语法回归 | 用 `javalang` 解析全部 `.java`，与未改动的原工程同文件对比 | 无回归（原工程同样有 185 个文件因 Java 21 语法超出该解析器能力） |
| 官方 Media3 引用 | 逐 import 比对官方 `1.11.1-sources.jar` 与官方 AAR 的 `javap` 输出 | 无残留的定制 API 引用 |

## 未执行（因此不能声称）

- 未运行 Gradle，未编译、未生成 APK、未安装、未播放。
- `.github/workflows/caiguale-base.yml` 从未在云端运行过；`assembleLeanbackLiteArm64_v8aDebug` 与 `beforeVariants` 开关的最终有效性要由首次手动触发确认。
- 官方 Media3 与剩余代码之间仍可能存在未覆盖的细粒度签名差异（例如某些 Builder 重载），只能由真实编译暴露。
- 电视端遥控焦点、真实片单播放、配置导入的端到端行为未验证。
