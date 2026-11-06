# 更新日志

## v1.21 — 2026-11-06

### 主界面采集日志改为「固定高度 + 可折叠」
- 原 `tvDetectBoot` 是 `wrap_content` 卡片，每轮 dmesg / logcat 都往里堆，页面被撑得极长，要下拉很久才能看到下面的功能。
- 改为 `logWindow`：标题栏（`tvLogTitle` + `tvLogToggle`「展开 ▾ / 收起 ▴」）+ `svLog`（ScrollView，固定 200dp，默认 `GONE`）。日志再长只在窗口内部滚动。
- `MainActivity`：新增 `svLog / tvLogTitle / tvLogToggle / logExpanded`、`setLogExpanded()`、`scrollLogToBottom()`；开始采集自动展开并标「运行中」，`finally` 里自动收起并标「已停止」，`renderBoot()` 在未采集时保持收起。
- 修复：`ScrollView.fullScroll()` 会走 `requestChildFocus`，把外层页面一起滚走（展开后「收起」被顶出屏幕）。改为 `scrollTo(0, child.height - view.height)`；`scrollParentToBottom()` 同步改 `scrollTo`，实时采集时整页不再乱跳。

### 风控环境检测三个按钮重排
- 原「两个文字链接挤一行 + 第三个链接单独一行」→ 主按钮「运行风控环境检测」整行（`Widget.App.Button`），下方「完整方案 135 项」「导出风险名单」并排等分。
- 新增样式 `Widget.App.Button.Outlined.Compact`（44dp / 12sp / 10dp 圆角）。注：`insetTop/insetBottom` 在本工程不可解析（`attr not found`），未使用。

### 采集悬浮窗自动折叠
- `RebootLogService`：展开态闲置 8 秒（`AUTO_COLLAPSE_MS`）自动折叠成细窄状态条；轻点标题条（位移 < touchSlop 且 < 400ms）切换折叠/展开，拖动则移动窗口。
- 折叠时状态条仍刷「第 N 轮 · 已写 N 项 · root/shell」，`updateLog` 增加折叠分支只刷状态条、不滚 ScrollView。

### 版本
- v1.21（versionCode 22）。

## v1.20 — 2026-11-06

### 「风险应用」合并 + 导出名单
- `EnvDetector` 的「风险应用」检查改为**合并检出**：静态黑名单（Luna/Ruru/春秋 口径）∪ LSP 模块仓库名单（`XposedCatalog`）∪ `xposedmodule` 元数据 ∪ ROOT 管理器，去重排序后一条列出全部命中包名。原独立的「已安装 Xposed 模块」项并入此项（不再单列）。
- 新增 `EnvDetector.lastRiskApps`（最近一次合并命中的包名）。
- `MainActivity` 新增 `exportRiskList()`：点「导出风险应用名单」→ 复制包名（换行分隔）到剪贴板 + 弹窗展示 + 可「分享」，直接粘贴到 HMA / HMA-OSS / FuseFixer 黑名单。
- `activity_main.xml` 新增 `btnExportRisk` 链接。
- 版本 v1.20（versionCode 21）。

## v1.19 — 2026-11-06

### 新增「已安装 Xposed 模块」+「刷机 / Root 文件痕迹」
- **Xposed 模块（实时名单比对）**：新增 `XposedCatalog.kt`。运行时从 GitHub `Xposed-Modules-Repo`（org id 78217009，即 LSP Store 的数据源）分页拉取全部模块仓库名（= 模块包名，约 1450 个），与 `pm list packages` 的已安装应用比对。缓存 6 小时；失败回落 `assets/detector/xposed_modules.txt` 内置快照（1452 条）；失败后 30 分钟冷却避免反复超时。并叠加 `xposedmodule` meta-data 枚举（`PackageManager.getInstalledPackages(GET_META_DATA)`），覆盖名单外模块。
- **刷机 / Root 文件痕迹**：检查 `/system/xbin/busybox`、`/sbin/busybox`、`/data/adb/magisk/{busybox,magisk,magisk64}`、`/data/adb/ksu/bin/busybox`、`/sdcard/TWRP` 等。
- `EnvDetector.init(context)` + `MainActivity.onCreate` 注入 Context；`AndroidManifest` 已有 INTERNET 权限。
- 版本 v1.19（versionCode 20）。

## v1.18 — 2026-11-06

### 风险应用名单与 Luna 1.4.4 对齐
- 用 Luna 1.4.4 在本机列出的 9 个「风险应用」（locusmimic、aweme.yyds、sukisu.ultra、tiandi.moshi、tsng.hidemyapplist、odomu.honorduo、voreulch.honoradvanced、lsposed.manager、hookvip.pro）逐项核对：我们原有覆盖 5 个（其中 locusmimic/aweme.yyds/sukisu 判不通过，hidemyapplist/lsposed.manager 由「HMA」「LSPosed」项提示/警告），**缺 4 个**。
- `EnvDetector` 风险应用名单补入：`com.tiandi.moshi`、`io.github.odomu.honorduo`、`io.github.voreulch.honoradvanced`、`top.hookvip.pro`（本机实测均为 `/data/app` 第三方侧载）。
- 版本 v1.18（versionCode 19）。

## v1.17 — 2026-11-06

### 结果展示与 TEE 判定
- **列表只显示待处理项**：`MainActivity.buildEnvRows` 过滤掉 `PASS`，只渲染「不通过 / 警告 / 提示」；并按 不通过 → 警告 → 提示 排序，未通过置顶。全部通过时显示「✓ 未发现异常项」。
- **TEE 判定对齐春秋**：`EnvDetector` 的「TEE 环境可信度」改为——环境里存在 root 管理器（`com.sukisu.ultra` 等）即判 `FAIL`「TEE 不可信（环境已被修改）」；其次 `attestation=Software` 判不可信；读不到级别才降级为提示。本机（装有 SukiSU）已判「不通过」，与春秋一致。
- 版本 v1.17（versionCode 18）。

## v1.16 — 2026-11-06

### 风控检测与「春秋 Native check-Eros」口径对齐
逐项比对本机春秋检测结果后修正：
- **USB 调试误判（真 bug）**：旧逻辑读 `persist.adb.enabled` / `adb_enabled` —— 二者都不是 system property（Android 把 ADB 开关放在 `Settings.Global.ADB_ENABLED`），永远读到空 → 误判「未开启」。改为读 `init.svc.adbd==running` / `sys.usb.config` 含 `adb` / tcp 调试端口，命中即判异常。本机实测 `adbd=running`、`sys.usb.config` 含 adb → 已正确判「不通过」，与春秋一致。
- **风险应用漏报**：Android 11+ 包可见性导致 `pm list packages` 只返回 `<queries>` 里的包。新增 `QUERY_ALL_PACKAGES` 权限，并补 `com.locusmimic.app`、`com.ss.android.ugc.aweme.yyds` 入库；实测已命中 `bin.mt.plus / com.locusmimic.app / com.ss.android.ugc.aweme.yyds`，与春秋一致。
- **新增 TEE 环境可信度（attestation）**：无需 root 生成带 challenge 的 EC 密钥，解析 KeyDescription 的 `attestationSecurityLevel`（0=Software / 1=TEE / 2=StrongBox）。本机（无 root、bootloader 锁定 green）实测返回 `attestation=TEE`；春秋截图里的「TEE 不可信」应是在 root 生效时测得（SukiSU 钩 keymint 会破坏 attestation），属状态差异。

### 比对结论（本机）
春秋 4 项异常中，USB 调试 / Found property / 风险应用 三项已与我们一致；TEE 一项因两次测量时 root 状态不同而结论不同。

## v1.15 — 2026-11-06

### 修复「一键自动修复框架」无进度、无输出、被反复点击
- **根因**：旧流程只在脚本**全部结束**后一次性把输出写回 `tvOutput`；但恢复脚本第 ⑤ 步会 `setprop ctl.restart zygote`，zygote 重启会杀掉包括本 App 在内的所有进程，`runOnUiThread` 的收尾回调永远执行不到 → 用户既看不到进度也看不到输出，误以为没点上而反复点击。
- **改法**：
  - `SuExec.kt` 新增 `runStreaming(script, timeout, onLine)` / `execStreaming(...)`：合并 stdout/stderr，**逐行**回调，边执行边读取避免管道缓冲死锁；因脚本含重启 zygote，`-c` 无输出时**不再**盲目走 stdin 兜底（防二次执行），仅进程起不来时才兜底。
  - `RecoveryActivity.kt`：点击后立刻 `进度圈 + 「正在执行修复…」+ 禁用按钮`；输出逐行追加并 `fullScroll` 自动滚到最新；状态行跟随脚本阶段（到「重启 zygote」时提示「App 将退出，属正常，请勿再次点击」）；结束时恢复按钮与文案。
  - `activity_recovery.xml`：新增 `progressRow`（`ProgressBar` + `tvProgress`）、给 `ScrollView` 加 `recoveryScroll`；新增顶部 root 状态条 `tvRootStatus`。
- **无 root 场景**（当前设备即此状态）：进入恢复页 `onResume` 探测 root，顶部显示「⚠ 当前无 root：请先到 MagicRoot 一键提权并在 SukiSU/管理器授权」；点修复若 su 不可用，输出区显示「未取得 root 授权（su 不可用）…」而不再甩英文 `Cannot run program "su"` 或空白。
- `strings.xml` 新增 `recovery_root_checking/ok/missing`、`recovery_progress_running/zygote`、`recovery_no_su`、`recovery_no_output`。

### 真机验证（设备 AMRF025926016252，当前无 root）
- 恢复页顶部正确显示无 root 警告条；点「一键自动修复框架」→ 确认 → 输出区显示中文「未取得 root 授权…」，按钮恢复可用，无崩溃。

## v1.14 — 2026-11-06

### 风控环境检测：需原生引擎项全部实跑化（修订 2）
- `EnvDetector.kt` 把原来 4 个 `Severity.SKIP`（需原生引擎）占位项全部替换为真实可跑的检测：
  - Zygisk 注入痕迹：扫本进程 `/proc/self/maps` 是否出现 `zygisk`/`libzygisk`（root 下再扫 `/data/adb/modules*` 目录）。
  - 可疑库注入：扫 maps 是否挂载 `frida`/`sandhook`/`whale`/`substrate`/`xhook`/`libinline`/`lsplant`/`dreamland`/`pine`/`yxposed`/`nativehook`/`libmagisk`。
  - Seccomp 过滤状态：读 `/proc/self/status` 的 `Seccomp` 字段（0=未过滤→警告，2=严格已生效→通过）。
  - 侧信道测量（启发式）：真实忙等 50ms 统计上下文切换，永远只给 INFO、绝不误报，标为启发式。
- 新增补充检测项（覆盖春秋检测出过的常见异常）：Zygote GID 3009、内核版本三路一致性（uname/-r / /proc/version / 属性）、SU 二进制特征（常见路径 + root 下 `which su`）、Evil Service/tmp 残留（root 下扫 `/data/local/tmp`）、KernelSU/SukiSU 特征（内核串/属性/目录）、GMS 被屏蔽。
- `summarize()` 去掉「需原生引擎」计数（已无 SKIP 项），改为「提示」计数。

### 状态检测按是否需 root 拆两大块（修订 3）
- `activity_main.xml` 在 Tab2 顶部加「需 root 才能检测（授权后读取）」分组标题，包住概览 probe + ①②③④ + 启动原因；用 `Divider` 分隔后加「无需 root 即可检测（普通 App / 风控视角）」分组标题，包住 ⑤ 风控环境检测。
- `styles.xml` 新增 `GroupTitle`、`Divider` 两个样式。
- ⑤ 板块说明文案同步：除「侧信道」一项为真实测量启发式外，其余全部真实可跑。

## v1.13 — 2026-11-05

### ① root 前必看
- 新增《环境隐藏 · 一图速览》（`assets/refs/env_hide_overview.svg`，原桌面的 `环境隐藏速览图.svg`），讲三级判定 + 洋葱七层 + 三条铁律 + 高频翻车，依据《安卓 Root 使用者指南》v2.0 第 6 章。
- 原第三项改名「magicroot决策速览」（指向 `refs/magicroot_guide.svg`）。
- `Links.REFS` 扩到 4 张速查图，Tab1 增加 `cardRef4` 并绑定。

### ② 状态检测新增「风控环境检测」（对接《春秋检测项解决方案》answer_zh.md 4.6.2）
- 新增 `EnvDetector.kt`：实跑本机可复现的泄漏检测项（属性 / 包名 / 文件痕迹 / 挂载 / 内核 uname / tmp 元数据 / 模拟器 / 多开 / TEE 等），命中直接给出 answer_zh.md 对应解决方案。需原生引擎的项（内存 CRC、时序侧信道、seccomp）标记为「需原生引擎」，仍在内置完整文档里可查。
- 完整 135 项答案随 App 内置（`assets/detector/answer_zh.md`），状态检测页「查看完整检测解决方案（135 项）」可读。
- `RefViewActivity` 增加 `.md` 渲染（深底浅字 `<pre>`）。
- 状态检测页新增 ⑤ 风控环境检测板块（`activity_main.xml` + `MainActivity` 的 `runEnvDetection` / `buildEnvRows`）。

## v1.12 — 2026-11-05

修复状态检测在「管理器里已授权」的设备上误报「本 App 未获 root 授权」。

### 根因

真机（Magic8 Pro / SukiSU-Ultra / UID 10287，管理器「超级用户」已勾选）的诊断输出是：

`su 调用：exit=-999，stderr=Cannot run program "su": error=2, No such file or directory`

KernelSU / SukiSU 的 `sucompat` 并不在文件系统里放真实的 su 二进制，而是内核把对 `/system/bin/su` 的 `execve` / `faccessat` / `stat` 重定向到 `/data/adb/ksud`，且只对已授权 UID 呈现该路径。`SuExec` 原来只调裸 `su`（交给 ProcessBuilder 走 PATH 搜索），不会稳定命中这个被 hook 的精确路径，于是报 ENOENT；`RootProbe` 又把「采集脚本没输出 `RG|fam`」一律判成 `SuState.DENIED`，显示「未获 root 授权」，与实际授权状态矛盾。

另有一处误导：`RootProbe.PKG_NAME` 写死成旧包名 `com.sean.rootguide`（10-29 遗留、已停用），诊断会引导用户去给一个根本没在运行的包授权。

### 改动

- `SuExec.kt`：新增 11 个 su 候选（`/system/bin/su` 优先，其次裸 `su` 及各管理器绝对路径）；首次调用逐个执行 `-c "id -u; echo RG_SU_OK"`，要求单独一行 `0` 才算拿到 root，缓存可用命令；提供 `reset()` / `activeSuPath()` / `lastProbeLog`。保留原 `-c` + stdin 双路兜底，避免只看 echo 标记把「跑起来了但没提权」误判成 root。
- `RootProbe.kt`：删掉写死的 `PKG_NAME`，改由 `MainActivity.onCreate` 用运行时 `packageName` 注入；每次 `probe()` 重置 su 探测；`notGranted()` 区分「调不起 su / 超时 / 采集无输出」与「未授权」，summary 不再一律写「未获 root 授权」；诊断区输出本 App 真实包名、su 候选逐项结果、App PATH。
- `MainActivity.kt`：`RootProbe.appPackage = packageName`。
- `Changelog.kt` / `build.gradle.kts`：v1.11(versionCode 12) → v1.12(versionCode 13)。

### 真机复核与最终结论

装 v1.12 后逐项探测日志显示：`/system/bin/su` 等系统路径全部 `ENOENT`，`/data/adb/*` 全部 `error=13 Permission denied`，App PATH 里无 su。进一步检查 SukiSU-Ultra：

- 主页「工作中」（LKM / 41100-6 / Tracepoint Syscall Redirect / NeoZygisk v2.4），内核 root 正常；
- 「超级用户」列表：已授权 4 / 502，MagicRoot Guide（`com.sean.magicrootguide`，UID 10287）标记为 ROOT；
- **设置 → 传统 SU 命令支持 = 始终禁用，副标题「此功能已被启动配置强制指定」，该项点不开、UI 无法修改**。

所以真正的根因是启动配置把传统 su 命令全局关掉了（SukiSU 另有 `CONFIG_KSU_MANUAL_SU` 的 prctl 手动提权模式，纯 Java/Kotlin App 没有该通道），与 App 是否被授权无关，也不是 App 能自行绕过或修复的。v1.12 因此把状态文案改为「调不起 su」而非「未获授权」，并在检测详情 / 引导里直指「设置 → 传统 SU 命令支持 → 始终禁用」这条，避免用户反复去授权白忙。

> 要让状态检测四项真正读到数据，需在内核 / LKM 启动配置里启用传统 SU 命令支持（会削弱 root 隐藏），重启后回 App 点「重新检测」；`SuExec` 的逐路径探测会自动找到可用 su 并缓存。若保持当前隐藏配置，App 只能停留在「需要 root」，这是设计使然而非故障。

---

## v1.11 — 2026-11-05

深色适配 + 手册同步 + 文案一致性，不改变修复逻辑。

### hnubl 手册深色适配（`assets/refs/hnubl_guide.html`）

- `:root` 色值对齐 App 深色色板（`--soft #0F1420` / `--bg #161D2C` / `--ink #E8EDF5` / `--muted #97A3B8` / `--accent #7C9CFF` / `--line #2A3348`），表头、斑马纹、代码块、折叠目录、分隔符等 7 处硬编码浅色一并改深色，6 处改走 CSS 变量。
- 新增 `color-scheme:dark`，告知 WebView 页面已适配深色，避免自动反色；`theme-color` 改 `#0F1420`。
- 22 张内嵌图保持原样不反色：16 张浅色截图用 `filter:brightness(.9) saturate(.96)` 轻压亮；实测 5 张本就深色的终端/命令截图（图 5/13/18/20/21，平均亮度 18–68）打 `figure.dark` 类并 `filter:none`，否则会被压到更难读。
- `@media print` 补齐浅色回退（还原 7 个变量 + 3 处硬编码深色 + 关闭图片滤镜），打印/PDF 导出仍是白底黑字。
- 响应式 `clamp()`、viewport、两个断点全部保留；桌面另存 `hnubl_操作手册_单文件版_深色.html` 副本，**App 内 assets 版为准，禁止用桌面浅色原件反向覆盖**。

### 框架修复结论同步到手册与脚本

- 工作区手册（`LSPosed_Vector_NeoZygisk最终版手册.md` / `_手机版.html`、`Vector操作手册.md`、`临时Root与框架问题说明及操作手册.md`）、`softreboot-hardening/` 下全部相关 MD/HTML/SVG/sh、根目录 8 个 `.sh`、App 内 `guide.html` 与 `magicroot_guide.svg`，统一同步 6 条 2026-11-05 真机结论：管理器本体双包、dexoptanalyzer SIGSEGV 与 `app_process -Xnoimage-dex2oat` 直拉、`/data/adb/lspd/lock` 共享锁 fd 释放、两框架共用清残留、`zygiskd64-zygisk_lsposed` 注入判读、「选中谁就修谁」。
- 全面弃用 `./daemon --force` 与 `service.sh` 起 daemon 的错误指令；故障速查表首查 `manager.apk` 本体。
- `guide.html` 5.4 节「Vector 打不开」把「双 daemon」降为次要原因，主因改列管理器本体缺失 + 共享锁 + 统一清残留。
- 修正本文件 v1.10 第 11 行自相矛盾处（曾同时写「Vector 走 service.sh」与「不再走 service.sh」）。

### UI 文案一致性

- 恢复页副标题 / 框架选择提示 / Tab3 恢复卡片说明改为「选中谁就修谁」，修掉「不开关 SUSFS」「照着做约 90 秒」等过时表述与错别字。
- ④ 检测标题与结论句补上「管理器本体」；修正 MainActivity 占位串误用（`detect_integrity_need_root` → 新增 `detect_framework_need_root`）。
- 两张 HTML 手册卡右侧「查看大图 ›」改为「打开手册 ›」，3 张 SVG 图保留原措辞；确认弹窗正按钮改「开始修复」。
- 删除未引用的 `sec_ref`；`Links.kt` 统一 `SuSFS` → `SUSFS`，hnubl 描述补「8E5（SM8850）· 单文件离线可看」。
- 真机核验发现首页副标题缺字（「认管理器分支」），改为「先确认管理器分支，再按正确顺序装模块；模块可一键下载安装。」；`RecoveryActivity` 类注释同步「选中谁就修谁」，并去掉「输出实时显示」的不实描述（`SuExec.run` 为一次性返回）。

### 收尾核验（2026-11-05 晚）

- v1.11（`versionCode=12` / `versionName=1.11`）重新构建并 `adb install -r` 到真机；冷启动无崩溃，首屏标题、Tab2「④ 模块 / 管理器本体 / 反向异常」、Tab3 恢复卡片与「打开手册 ›」、hnubl 深色手册均实测正常。
- hnubl 深色页在 WebView 内为深蓝底浅色字，目录 / 参数表格 / 代码块清晰、无白块；打印回浅色规则保留。
- PDF `LSPosed_Vector_NeoZygisk最终版手册_手机版.pdf` 重导出并校验：`%PDF-1.4`、11 页、`%%EOF` 完整，1,243,918 字节（Edge 退出 `RC=2` 仅 teardown watchdog，文件无损）。
- `softreboot-hardening/` 清理重复清残留注释与冗余锁-fd 命令；统一「开机 10 分钟等待窗口」自相矛盾的旧结论（MD/HTML 各 4 处），明确「闸门全关的正常 `ctl.restart` 换代可不等；zygote 被 monitor 杀掉的非正常死亡仍需等窗口」。
- 全局核验：29 个 `.sh` `bash -n` 0 失败、2 个 SVG XML 解析通过、主手册 7 条关键 token 的 MD/HTML 覆盖一致、无实际可执行的旧 `./daemon --force` / `service.sh` 起 daemon 指令、无缺 `-Xnoimage-dex2oat` 的 `app_process`。

---

## v1.10 — 2026-11-05

框架功能恢复：从「一刀切全修」改为「选中谁就修复谁」，并固化绕过 Android 17 dexopt 崩溃的修复逻辑。

### 变更（Recovery.kt / RecoveryActivity / activity_recovery.xml / strings.xml）

- **新增框架单选**：恢复页增加 RadioGroup（LSPosed / Vector，默认 LSPosed）。选哪个就只修哪个，另一个框架的 daemon 与残留注入进程会被结束以释放共享锁，不会误伤。
- **固化 LSPosed 修复**：直接调用模块自带 `zygisk_lsposed/lspd` 脚本（`app_process -Djava.class.path=.../daemon.apk -Xnoimage-dex2oat ... org.lsposed.lspd.Main`），**旁路掉会触发 dexoptanalyzer 段错误的 `daemon` 包装脚本**——这是 Android 17 ART 上 LSPosed 起不来的根因。
- **固化 Vector 修复**：与 LSPosed 统一走 `app_process -Djava.class.path=<模块>/daemon.apk -Xnoimage-dex2oat /system/bin --nice-name=vectord org.matrix.vector.daemon.VectorDaemon` 直拉；不再走 `service.sh`（避免 dexoptanalyzer 触发路径），两框架共用同一套清残留。
- **共享锁处理**：LSPosed 与 Vector 共用 `/data/adb/lspd/lock` 单例锁。修复 LSPosed 前先 `kill vectord`；修复 Vector 前先 `kill lspd`；并清理两侧残留的 `zygiskd64-<module>` 注入进程。
- **NeoZygisk 后端保留**：两种修复都先重启 `zygisk-ptrace64` monitor（两个框架都依赖它），再拉起目标 daemon，最后 `setprop ctl.restart zygote` 把框架注入 system_server 与各 App。
- **注入成功判读**：`lspd` 存活 + `zygiskd64-zygisk_lsposed` 出现（实测 pid 32182）才是注入成功标志；`lspctl status` 显示 System server: not connected / Modules: 0 installed 只说明还没重启 zygote，不代表失败。
- 风险提示文案随所选框架动态生成（切换单选即时刷新）；检测页认出哪个框架启用就预选哪个（传入 `EXTRA_FRAMEWORK`）。
- **两个框架共用同一套清残留**：结束两侧 daemon、扫描并杀掉仍持有 `/data/adb/lspd/lock` fd 的僵留进程（flock 文件在 KSU 上下文删不掉，只能杀持有者）、清两侧 `zygiskd64-<module>` 注入进程与 `zygisk-ptrace64` monitor 残留、`rm -rf /data/adb/neozygisk`，再只用 `app_process -Xnoimage-dex2oat` 直拉目标 daemon。不再为 Vector 单独走 service.sh。（C3 一句话：`for fd in /proc/[0-9]*/fd/*; do [ "$(readlink "$fd")" = "/data/adb/lspd/lock" ] && kill -9 "$(echo "$fd" | cut -d/ -f3)"; done`）

### 新增（模块完整性 / 反向异常）

- 第 4 项新增「框架管理器本体（App）」检测：采集 `pm list packages -d` 的停用包，对每个已启用框架核对其管理器 APK 是否安装/被停用。
- **真机坑固化**：LSPosed 2.2.1 拆成 `manager.apk`（`org.lsposed.manager`，带 launcher 界面）与 `daemon.apk`（`org.lsposed.daemon`，零 Activity 后台连接器）。只装 daemon 或把管理器 `pm disable`，表现是「框架已注入但点图标进不去界面」，与框架本身无关。Vector 对应 `org.matrix.vector.manager`。
- 缺失/被停用会生成反向异常并给出可执行修复（提取 manager.apk 安装 / `pm enable` 对应包 `org.lsposed.daemon` / `org.lsposed.manager` / `org.matrix.vector.manager`）。

### 参考图 / 手册替换

- 「MagicRoot 使用手册」卡换成《三条线，选一条》速览图（`选择速览图.svg`）：01 稳定用 / 02 一劳永逸（仅 8E5）/ 03 别指望 SUSFS。
- 「hnubl 操作手册」换成单文件版 HTML（内嵌 22 张 base64 图，2.7 MB，自带响应式）。查看页对 >512 KB 的内置 HTML 改走 `file:///android_asset/` 直接加载，避免整篇字符串经 JNI 喂给 WebView 卡顿/OOM。
- 手册同步：LSPosed_Vector_NeoZygisk最终版手册.md / _手机版.html 已按 v1.1 同步 6 条新结论，PDF 另行重新生成。

### 数据来源

2026-11-04~05 在 Magic8 Pro（MagicOS 11 / 内核 6.12.58 / SDK 37）实机排障：LSPosed 2.2.1(7912) `lspd` 经 `daemon` 包装脚本触发 `dexoptanalyzer` SIGSEGV；vectord(PID 7365) 占用 `/data/adb/lspd/lock` 导致 lspd 起不来；用 `app_process -Xnoimage-dex2oat` 直拉 lspd 可绕过并稳定存活。

## v1.9 — 2026-11-05

改名 + 新增第三张参考图：《MagicRoot 使用手册》（本机实测决策图）。

### 变更

- **软件改名**：`root使用指南` → **MagicRoot Guide**；包名 `com.sean.rootguide` → `com.sean.magicrootguide`（可与旧版并存，旧版可自行卸载）。工程名 `RootGuide` → `MagicRootGuide`，产物 `magicrootguide-v1.9-debug.apk`。
- 引导文案里的应用名同步改成「MagicRoot Guide」。

### 新增

- 第 4 步「参考」新增第三张卡：**MagicRoot 使用手册** —— 解锁 / 软重启 / 注入六条分支的决策图（矢量 SVG，随包内置，可「放大」后左右滑动看细节）。
- 纯离线参考图不再显示「在线打开」（`Ref.url` 留空即自动隐藏），避免点了白屏。

### 数据来源

图来自 2026-11-05 在 Magic8 Pro（BKQ-AN10 / MagicOS 11.0.0.110 / 内核 6.12.58）上的三轮实机 A/B，含：late-load 成因、三通道复位链、SuSFS 开启/关闭对照、`zygisksu/sepolicy.rule` 与 `zygisk/arm64-v8a.so` 两处缺件、monitor 单实例约束、闸门分场景（纯 `ctl.restart zygote` 不需要关闸门）。


## v1.8 — 2026-10-29

修掉一条会误导人的判定：系统已经 root，只要本 App 没被授权，以前会被报成「无 su、无管理器」。

### 修复

- 检测改成三态：已授权 / 有 root 但本 App 未授权 / 真的没有 root。未授权不再说成「没 root」。
- 认不出 su 时先看文件：`/system/bin/su`、`/product/bin/su` 等 8 个位置逐个查，找得到就说明系统有 root。
- 补上 FinaSU 的管理器包名 `me.yuki.fina`（真机核实，label = `FinaSU`，v1.3.2）。之前漏了它，包名级预检测等于白跑 —— 这是误报的直接原因。
- `su -c` 拿不到输出时自动改用 stdin 再跑一遍，兼容各家自定义 su 对参数的差异。

### 新增

- 未授权时状态行直接写「检测到 FinaSU，但本 App 未获 root 授权」，并给「去管理器授权 ›」直达入口。
- 检测详情末尾加「诊断」：su 路径、已装管理器、exit code、stderr 原文。
- 引导区标题跟着状态走：未授权是「先给本 App 授权」，真没 root 才是「拿到 root 之前要做的事」。

### 需要留意

- FinaSU / YukiSU 是白名单模型，不会弹窗问要不要授权；不加进白名单，su 永远返回空。

## v1.7 — 2026-10-29

指南与两张参考图整体升到最新版，分支模型统一；修掉决策图的一处重叠。

### 改进

- 指南开篇讲清「怎么拿到 root」和「root 是哪一家」是两件事：临时 root 是**手段**，不是分支。
- 分支按内核里那份实现认。8E5 的漏洞链提权后就是 FinaSU，装别家管理器只会显示「未安装」。
- 第 6 章补上假回锁实测配置：BL 外观仍 locked、`/data/adb` 持久、FinaSU 管 8 个 overlay 挂载点。
- FinaSU 卡片去掉「8E5 上不可用」，改成实测可用（假回锁 + 内置 FolkZygisk）。
- 决策图新增「不确定自己在用哪家？30 秒测出来」，四条命令看文件指纹，不再靠包名猜。

### 修复

- 选型决策图里红色「临时 root」框与左侧 Q2 选项框重叠，左边被盖掉一截。
- 注意事项图 NO.05 措辞改为「临时 root 不是一种分支」，与指南口径一致。

### 需要留意

- 参考图是矢量 SVG，放大不糊；密排文字看不清就点图页右上角「放大」。

## v1.6 — 2026-10-29

两张参考图改成按屏幕比例自适应。

- 不受图片原始尺寸（1760px 宽）影响：竖屏铺满宽度，横屏和平板整张放进一屏。
- 旋转屏幕后立即重排，不用退出重进。
- 图页右上角新增「放大」：约 2 倍，可左右滑动看细节，再点「适应屏幕」回到整幅。

## v1.5 — 2026-10-29

按钮布局统一，不再一大一小。

- 首页「开始」板块：生成清单与手动装 zip 两个按钮等高同款，主次靠填充/描边区分。
- 安装页「软重启 / 重启」文案改短、等宽等高，不再因文字换行而高低不一。

## v1.4 — 2026-10-29

首页重排，把该先看的放到最上面。

- 三条互斥铁律从页底移到最上方，红色描边横幅。
- 环境准备 / 管理器分支 / 开始 / 参考 各自包成卡片块，标题带步骤序号徽章。
- 参考板块两张图互换：注意事项速览图在前，选型决策图在后。
- 下拉框去掉「临时 root（漏洞提权）」：它只是装管理器的方式，不是管理器分支。漏洞链提权后内核跑的是 FinaSU，按 FinaSU 走即可；「提权后确认 root」「软重启挂载」两步已并入清单。

## v1.3 — 2026-10-29

清单页按「必装 / 选装」分组，分组头带数量，长清单不再一坨。

## v1.2 — 2026-10-29

标题旁新增「查看」，一键打开网页版《安卓 Root 使用者指南》；新增两张参考图和看图页，有 root 就直接替你认分支。

### 新增

- 标题右侧「查看」：App 内直接读网页版《安卓 Root 使用者指南》，12 张速查表，不用登录、离线也能看。
- 新增两块参考：su 管理器选型决策图、Root 注意事项速览图。点开可双指放大，右上角能跳在线版。
- 有 root 时自动识别管理器分支，识别到就直接用它，不用再手动选。
- 标题下新增「v1.2 · 本次更新」，点开是本页的纯文本版。

### 改进

- 没 root 时会明确写「请手动选择」，并给出选型决策图入口，不再只丢一个下拉框给你。
- 手动换分支后，下面的说明与「先改这几项」跟着换，不会还挂着上一个分支的建议。
- 首页按 环境准备 → 管理器分支 → 开始 → 参考 重排，先做什么一眼看得到。
- 检测结果收成一行状态（已拿到 root · 分支 · 模块数 · 挂载点），完整证据放进「查看检测详情」。
- 说明文字压到一两句，去掉了讲原理的长句。

### 需要留意

- 首次打开会请求 su 授权。FinaSU / YukiSU 是白名单模型不弹窗，需要你手动把「root使用指南」加进白名单。
- 两张参考图与指南网页随 App 内置一份，离线也能看；想看更新版点右上角「在线打开」。
- 从 v1.1 覆盖安装即可，不需要卸载，分支选择与进度记录会保留。

## v1.1 — 2026-10-28

- 自动识别 su 管理器分支，并按分支生成「设置项 + 模块」清单。
- 模块支持一键下载安装（ksud / magisk / apd / pm 按体系分派）。
- 移除临时 root 一键诊断页。
- 自适应图标与名称「root使用指南」。
