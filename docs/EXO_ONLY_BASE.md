# EXO-only 基础版（base / 非 Full）

本文件描述 `dev/exo-only-base` 分支的**源码范围**。它不是 Full 版说明，也不代表任何 APK 已经过构建或电视实测。

## 1. 结论先行

- 播放后端**只有**官方 AndroidX Media3 ExoPlayer（`androidx.media3:*:1.11.1`，来自 Google Maven）。
- 五个专有 AAR、IJK/MPV 原生库、定制 Media3/nextlib、Python 运行时全部**未随源码上传**，并已在源码层面移除或隔离。
- 特殊协议（Force/P2P、JianPian、Thunder/magnet/ed2k、TVBus）**显式返回不支持错误**，不做假实现，也不静默回落成普通 HTTP。
- 本分支**尚未在本机执行 Gradle 构建**（按任务约束，NAS 上不跑 Gradle）；构建与产物验证由新增的手动工作流在云端完成。

## 2. 相对原工程（NAS `caigualetv/client`）的移除清单

| 类别 | 内容 | 处理 |
| --- | --- | --- |
| 专有 AAR | `forcetech-release.aar`、`jianpian-release.aar`、`thunder-release.aar`、`tvbus-release.aar`、`hook-release.aar` | 不上传；`implementation fileTree(dir: "libs")` 删除，`flatDir` 删除 |
| 提取器 | `player/extractor/{Force,JianPian,Thunder,TVBus}.java` | 删除 |
| Hook | `App.setHook/getPackageManager/getPackageName` 覆盖、`Core.getHook()`、TVBus 调用链 | 删除 |
| 解析链 | `Source` 中的四提取器注册与 `Thunder.Parser` | 删除，改由 `BaseProtocolPolicy` 判不支持 |
| IJK | `app/src/main/jniLibs/**`、`tv/danmaku/ijk/**`、`player/ijk/**`、`IjkPlayerEngine` 等 | 不上传 + 删除 |
| MPV | `app/src/mpvlibs-arm64/**`、`app/src/armeabi_v7a/assets/**`、`is/xyz/mpv/**`、`androidx/media3/mpvplayer/**`、`MpvPlayerEngine`、`player/mpv` 相关内核调用 | 同上；仅保留纯偏好存储类（不加载任何内核） |
| 定制 Media3 | 仓库内 `third_party/maven`（`1.11.0-alpha01-fongmi`）、`libs.versions.toml` 的 `-fongmi` 版本 | 不上传，版本改为官方 `1.11.1` |
| nextlib | `io.github.anilbeesetti:nextlib-media3ext` 依赖与 `ExoUtil` 中的 FFmpeg 渲染器 | 删除；渲染器扩展模式改为 `EXTENSION_RENDERER_MODE_OFF` |
| Python | `chaquo` 模块、`com.chaquo.python` 插件 | 模块不上传，`settings.gradle` 与依赖移除；lite 自带 `com.fongmi.chaquo` 编译桩 |
| LUT 预设 | `app/src/main/assets/lut_presets/**.cube`（14 个，由 Resolve 导出后再重采样，原始素材权利不明） | 不上传；LUT 功能代码保留，预设列表为空 |
| 其他 | `.github/workflows/caiguale-debug.yml`（远端保护）、`ci/verify_caiguale_apk.py`（远端保护）、`.cnb.yml`、`build_min.sh`、`local.properties`、日志、签名材料、`build/`、`.gradle/` | 不上传；远端保护文件原样保留 |

被删除的 Java 文件哈希与原因记录在交接审计目录（不随仓库上传），原工程 `/home/stanyang123/caigualetv/client` 未做任何修改。

## 3. 官方 Media3 迁移的实证依据

迁移不是猜测，依据是官方 `1.11.1` 的 `-sources.jar` 与定制 AAR 的 `javap` 公共 API 差分：

- 定制包新增、官方包不存在的 API 被逐项列出（`MediaChapter`、`MediaEdition`、`androidx.media3.ui.danmaku.*`、`androidx.media3.mpvplayer.*`、`androidx.media3.extractor.iso.*`、`androidx.media3.exoplayer.hls.playlist.HlsAdsParser`、`CacheDataReader`、`PlayerView#getDanmakuController/setDanmakuConfig/setRender`、`SubtitleView#reset/getPosition/setBottomPosition`、`MediaItem.Builder#setAdblock/setDecode`、Player 的音视频偏移与 edition 接口等）。
- 依赖这些 API 的界面与工具类已随之移除或改写：章节/多版本（Chapter/Title Dialog+Adapter）、弹幕面板与弹幕渲染控制器、ISO/BDMV 元数据解析与 `IsoSessionManager`、MPV HLS 代理、MPV 网络恢复策略的分类器标记。
- 字幕位置/字号改用官方公开 API：`setBottomPaddingFraction` / `setFixedTextSize`。
- 官方 `DefaultRenderersFactory.buildAudioSink(context, enableFloatOutput, enableAudioOutputPlaybackParameters)` 签名与 `DefaultAudioSink.Builder#setEnableAudioOutputPlaybackParameters` 已核对存在。

## 4. 特殊协议的显式不支持

`app/src/main/java/com/fongmi/android/tv/player/BaseProtocolPolicy.java`：

- scheme 命中 `mitv/tvbus/jianpian/tvbox-xg/xg/xgplay/ftp/magnet/ed2k/thunder/bdmv/bluray/dvd/edl` 或 `p2p…p9p` 时返回中文错误「基础版不支持此协议（scheme）」。
- URL 路径以 `.torrent` / `.iso` 结尾时同样拒绝。
- 拒绝点有两处：`Source.fetch()`（提取前）与 `PlayerManager.setMediaItem()`（起播前），因此不会出现“看起来能播”的空实现。

## 5. 保留的能力

- 完整 UI/遥控/Leanback 界面骨架、设置、历史、收藏。
- 配置导入（URL/文件/assets/局域网）与其预检、回滚逻辑。
- 搜索、详情、线路（flag）、集数（episode）数据结构与交互。
- 本地 WebHome 首页 `app/src/main/assets/caiguale/` 与 `CaigualeVodBridge`（`vod.sites/home/category/detail/search/play`）。
- 独立包名 `com.caiguale.tv`、namespace 仍为 `com.fongmi.android.tv`。
- 播放器选择器只显示 EXO；`PlayerSetting.sanitizePlayer()` 把任何历史 IJK/MPV 偏好收敛为 EXO，`PlayerManager.buildEngine()` 只构造 `ExoPlayerEngine`。

## 6. 变体与任务名

- `flavorDimensions = ["mode","edition","abi"]`，变体名由 AGP 组合产生：`leanback` + `lite` + `arm64_v8a` + `debug` → **`:app:assembleLeanbackLiteArm64_v8aDebug`**。
- `androidComponents.beforeVariants` 只放行这一个变体，其余（Full/mobile/其它 ABI/Release）一律关闭；AGP 8/9 属性名差异用 `enable`/`enabled` 双写兜底。
- MPV 资产注入块（`onVariants(...withFlavor('edition','full')...)`）已删除，仓库内也不存在对应资产目录。
- **未执行 Gradle**：上述变体名与开关按源码与既有 CI 目标推导，未经本机构建验证。

## 7. CI 策略

- 远端既有 `.github/workflows/caiguale-debug.yml` **未修改**，它只由 `workflow_dispatch` 触发（push 不会触发它），目标是 Full ARM64 + IJK/MPV 资产，不适用于本分支。
- 新增 `.github/workflows/caiguale-base.yml`，同样只有 `workflow_dispatch`：
  1. `base-policy` 作业先跑 `ci/check_exo_base_source.py`（禁止 AAR/so/dex、禁止排除 SDK 的 import、禁止非空演示凭证、禁止定制 media3/nextlib、校验变体白名单与包名）和 `ci/test_base_policy.py`（用合成 ZIP 测试 APK 校验器，不跑 Gradle）。
  2. `build-base` 只构建 `:app:assembleLeanbackLiteArm64_v8aDebug`，随后 `ci/verify_caiguale_base_apk.py` 校验：唯一 APK、`com.caiguale.tv`、仅 `arm64-v8a`、无 `libijk*/libmpv*/libtvbus*/libforcetech*/libp2p*/libxl*`、无 `assets/mpv-libs`、dex 中无排除 SDK 与 `androidx/media3/mpvplayer|ui.danmaku`，且必须存在 `Landroidx/media3/exoplayer/ExoPlayer;`。
- 该工作流**没有沿用 Full 的 IJK/MPV 必需规则**（不检查 `libmpv.so` 是否存在，不要求 Chaquopy/Python）。

## 8. 二进制与许可

- 上传的二进制仅 104 个图片资源（webp 68 / png 32 / jpg 3 / ico 1）与 1 个 Gradle Wrapper jar，清单含 SHA-256：`docs/base-binary-inventory.json`。
- `gradle/wrapper/gradle-wrapper.jar` 来自原上游仓库（Apache-2.0，Gradle Wrapper）；其字节与 `services.gradle.org` 单独发布的 wrapper 校验值不一致，且该发布地址对 9.6.1 返回 `NoSuchKey`，因此**不宣称**二者同一构件；真正的 Gradle 分发由 `gradle-wrapper.properties` 的 `distributionUrl` 指向 `gradle-9.6.1-bin.zip`（官方校验值 `9c0f7faeeb306cb14e4279a3e084ca6b596894089a0638e68a07c945a32c9e14`）。
- `third_party/media-lock.json` 保留为 Full 版本历史记录（指向 `webhtv` 的 media 分支和 `FongMi/nextlib`），**本分支不引用**，不构成再分发授权。
- 上游 GPL-3.0（`LICENSE.md`、`NOTICE`）继续适用；`NOTICE` 已把 Media3 一行改为官方构件说明。

## 9. 脱敏

- `webhome-devkit` 内的演示凭证字段被置空（只替换值，保留字段名），涉及：
  - `webhome-devkit/examples/homepages/nostr.html`（`apiKey`）
  - `webhome-devkit/skills/webhome-homepage-builder/assets/demo/nostr.html`（`apiKey`）
  - `webhome-devkit/templates/homepages/app-capabilities-showcase.html`（`password` ×3、`pwd` ×1）
- 报告不含任何原始值；原工程内的同一批值仍然存在（本次未改原工程），若它们是真实密钥，应由持有人自行撤销/轮换。
- 提交历史由全新 `git init` 产生（从远端 main 克隆后新增提交），不携带原 client 的任何历史。

## 10. 明确未验证 / 已知降级

- 调色 LUT 预设未随源码上传（原始素材权利不明），LUT 面板会显示空预设列表。
- 渲染模式（Surface/Texture）切换、广告过滤开关在官方 Media3 下不再生效（偏好仍可保存）。
- 音视频与字幕时间轴偏移（原定制 API）固定返回 0，设置项不修改播放器。
- 未在本机运行 Gradle，未产出 APK，未在电视上安装或播放。
- 云端首次 `workflow_dispatch` 尚未运行，编译错误（例如官方 Media3 与剩余代码的细粒度 API 差异）只能由云端构建暴露。
- 弹幕渲染、章节/多版本选择、ISO/BDMV、MPV 内核热切换、IJK 软解、Python 源、Force/JianPian/Thunder/TVBus 协议在本分支不可用（部分入口改为明确提示）。
- 设置页仍保留 MPV 配置编辑等纯偏好项（不加载内核），属于已知 UI 冗余。
