# 菜瓜乐影视｜公开仓库上传前依赖审计（阶段性）

> 状态：**禁止直接上传 NAS 原始 client/**。此文件记录静态审计结果和 P0 切分方案，**不表示已解决许可证问题或完成构建**。

## 已确认的构建关系

当前待导入工程的 `app/build.gradle` 存在：

```groovy
implementation fileTree(dir: "libs", include: ["*.aar"])
```

这会无条件纳入 `app/libs/` 的以下五个未完成分发依据核实的 AAR：

| 文件 | 直接引用 | 作用和迁移策略 |
| --- | --- | --- |
| `forcetech-release.aar` | `player/extractor/Force.java` | Force/P2P 特殊协议，先隔离 |
| `jianpian-release.aar` | `player/extractor/JianPian.java` | JianPian 特殊协议，先隔离 |
| `thunder-release.aar` | `player/extractor/Thunder.java` | Thunder/magnet/ed2k 相关扩展，先隔离 |
| `tvbus-release.aar` | `player/extractor/TVBus.java` | TVBus 特殊协议，先隔离 |
| `hook-release.aar` | `App.java`、`bean/Core.java`，并由 TVBus 使用 | Hook 扩展，需连同调用路径一并隔离 |

**注意：** 仅删除 AAR 文件会导致 Java 编译失败。当前 `Source.java` 会创建四种协议提取器，并调用 `Thunder.Parser`，必须同步改动。特殊协议不应静默回落为普通 HTTP 链接，应向 UI 报出“不支持的协议”。

## 其他独立待审查项

- `app/src/main/jniLibs/`：内含 IJK native `.so`；先核实具体发布二进制的来源、许可证、FFmpeg 配置及对应源码。
- `app/src/mpvlibs-arm64/assets/`、`app/src/armeabi_v7a/assets/`：内含 MPV/native `.so`；核实版本、构建选项、依赖与源码供应方式。
- `gradle/libs.versions.toml` 引用了定制 Media3/nextlib；`third_party/media-lock.json` 给出了部分来源修订号，但需核实实际发布构建是否与记录匹配。

**上游项目选择 GPL，不自动证明这些具体预编译 SDK 或 .so 具有相同的再分发授权。**

## 敏感示例

在上传副本中移除/替换 `webhome-devkit/` 内的演示凭证和分享密码，并运行凭证扫描；不要修改 NAS 原始工程。若字段可能是真实密钥，应通知持有人撤销/轮换，而不仅仅是从 Git 中删除。不要将原始密钥写入报告、日志或提交历史。

## 推荐两条路线

1. **P0 基础 TV APK（优先）**：在 staging 副本中隔离五个 AAR 及引用代码；暂不上传未核实的 IJK/MPV 原生二进制，选用原生 Media3/ExoPlayer 作为首轮播放后端。对应编译产物标记 `base` 或 `exo-only`，**不能称为 Full 版**。维护明确的功能降级列表，实测搜索、详情、选集和合法授权播放。
2. **Full 版本后续恢复**：逐项确认每个分发二进制的作者/版本/来源/许可证/对应源码、NOTICE、再分发条件；必要时从可审计源码重建，或寻找合法授权替代。再启用功能与 Full ARM64 目标。

## 安全上传门禁

- Stage 在独立目录完成；不得直接推 `client/` 原目录。
- 检查 git tracked、untracked、二进制、历史、工作流/日志是否泄密。
- 新增被排除的路径不代表已有历史安全；推送前检查整个待提交内容。
- 仓库当前 Public；不要在公开仓库上传许可未确认的二进制和敏感值。
- 每次只运行一个 CI 构建；日志和 APK 必须有一致 SHA；APK 构建成功不等于电视播放验收通过。

## 下一步验收与阻塞

待 Agent 提供：五个依赖的引用清单与隔离 diff、清洁阶段源列表、二进制 provenance 清单、扫描结果（仅路径/类型、不含秘密值）、EXO-only 构建变体和静态检查；然后由 GitHub Actions 负责真正构建与产物检验。
