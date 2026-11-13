# phone-agent 优化需求文档

| 项 | 内容 |
|---|---|
| 版本 | v1.0（需求定稿） |
| 日期 | 2026-11-13 |
| 基线 | `minghao680/phone-agent` v0.8.16（内置 dsh 0.2.0-rc.2） |
| 决策 | **不更换项目，在 phone-agent 上做优化** |
| 依据 | 本文所有现状数字均来自本机实测，见附录 A |

---

## 一、背景

### 1.1 现状（实测）

| 指标 | 当前值 |
|---|---|
| APK | 468 MB |
| 安装后总占用 | ≈ 3.3 GB（`pkg` 1.2G + `home` 815M + `agents` 667M + `usr` 222M） |
| 自有代码 | 52 个 Kotlin 文件 / 459 KB |
| 设备侧补丁层 | `patch-dsh-android.mjs`（19.8 KB）+ 10 个源码锚点补丁 |
| dsh 版本 | 0.2.0-rc.2，硬编码在 APK 安装脚本里 |
| 更新机制 | A/B 槽位（`slot.json`）已存在，但只由 APK 首次启动触发 |
| 停更 | 上游最后提交 2026-10-15 |

### 1.2 问题定位

1. **逻辑重**：为支持 5 个 agent（zcode / dsh / opencode / codex / claude），代码与资产都要为每个 agent 分叉；其中 4 个用户不用。
2. **更新死**：dsh 版本烤进安装脚本，想升版本必须重打 APK，而项目已停更 4 周。
3. **观感差**：系统栏是实色块，手势条后面有系统蒙层，与大屏平板不搭。

---

## 二、需求总览

| 编号 | 需求 | 优先级 | 目标 |
|---|---|---|---|
| **R1** | 去除多智能体，不主动提供多 agent 功能 | P0 | 逻辑与资产单一化 |
| **R2** | 允许 dsh 版本更新（不重打 APK） | P0 | 可自助升级且可回滚 |
| **R3** | 状态栏 / 手势条（小白条）UI 优化 | P1 | 沉浸式显示 |

优先级说明：R1 是 R2 的前提（单 agent 后槽位与补丁逻辑只需服务一份），R3 独立可并行。

---

## 三、R1 去除多智能体

### 3.1 目标

只服务 **dsh 一个 agent**：保留 dsh 的 Web UI 与终端能力，删除 zcode / opencode / codex / claude 的资产、服务与入口。

### 3.2 范围

**删除的资产**（APK `assets/`）

| 资产 | 大小 | 说明 |
|---|---|---|
| `terminal-extra/claude.tgz` | 102 MiB | Claude Code（不装） |
| `terminal-extra/codex.tgz` | 148 MiB | Codex CLI（不装） |
| `terminal-extra/opencode.tgz` | 59 MiB | opencode（不装） |
| `zcode-web/` | 127 MiB | zcode 前端 SPA |
| `zcode-server/` | 116 MiB | zcode 服务端 |
| `packages/zcode-app-cli-*.tgz` | 30 MiB | zcode CLI |

保留：`runtime.tar.xz`（Node 26.4.0 + 工具链，41 MiB）、`packages/deepseek-ai-dsh-*.tgz`、`native/`（pty.node、landlock-wrap）、`scripts/`（patcher）。

**删除的代码**

| 模块 | 处置 |
|---|---|
| `AgentRegistry` 多 agent 注册 | 收敛为单 agent；保留结构但只注册 dsh |
| `ZcodeWebService` / `OpencodeWebService` | 删除（含前台服务、端口 3030/4096 的探活） |
| `TerminalPickerActivity` 五项选择 | 收敛为「dsh Web / dsh 终端」两项 |
| `BaseBinaryTuiActivity` 及其子类（codex/claude/opencode 二进制 TUI） | 删除 |
| `Watchdog` 三端口探活 | 只探 3080（dsh）+ 8899（设备工具） |
| 用量统计 / 会话列表里的 agent 徽标 | 保留字段，固定为 dsh（不做跨 agent 迁移） |
| 跨 agent 会话迁移（`SessionMigrate`） | 降级：保留导出/导入，删除跨 agent 转换 |

**保留不动**（与 agent 数量无关，且是核心价值）

- dsh Web UI（WebView）与 token URL 抓取
- dsh 终端（Termux 终端模拟器 + PTY）
- 会话列表 / 详情 / 删除 / 回收站
- 设备工具通道 `127.0.0.1:8899` + `pa-*`
- A/B 槽位与 patcher
- 设置页（API Key、镜像、代理、存储诊断、更新）

### 3.3 验收标准

1. APK ≤ **130 MB**；首装后应用数据 ≤ **250 MB**（解压运行时 + dsh 依赖）。
2. 冷启动后 `ps` 中无 zcode / opencode / codex / claude 相关进程；监听端口只有 `3080`、`8899`（以及 dsh 自身临时端口）。
3. UI 全量巡检无残留入口：设置页、终端选择器、用量页、诊断页、通知、桌面快捷方式。
4. 代码中不存在对被删资产的引用（CI 加一道检查：源码里 `terminal-extra` / `zcode-web` / `zcode-server` 字符串为零）。
5. 老版本升级场景：已有的 `files/pkg/*.tgz`、`files/agents/zcode` 在启动时可选清理（**不动用户会话**）。

---

## 四、R2 允许 dsh 版本更新

### 4.1 目标

在设备内把 dsh 从当前版本升到任意**已验证**的新版本，全程不重打 APK；失败自动回滚，用户数据不丢。

### 4.2 现状与差距

- ✅ 已有：A/B 槽位目录、`slot.json`（`active/standby/state`）、符号链接指向现役槽、patcher 支持 `DSH_ROOT` 指定槽位。
- ❌ 缺失：① 没有「查版本 / 选版本 / 下载 tgz」的入口；② patcher 遇到锚点失配只打 `WARN` 继续（**必须改成中止**）；③ 没有升级前的冒烟验证与自动回滚；④ 没有版本兼容矩阵。

### 4.3 功能需求

**F2.1 版本来源**

- 从 npm registry（`registry.npmjs.org` 或设置里的镜像）读取 `@deepseek-ai/dsh` 的 `dist-tags` 与版本列表。
- 展示：当前版本、最新稳定、可升级列表；支持输入指定版本。
- 网络失败时给出明确错误，不静默。

**F2.2 安装流程（写入备用槽）**

```
1. 前置检查：磁盘 ≥ 1.5 GB、无进行中会话、standby 槽可用
2. 下载 tgz → 校验完整性
3. npm install --ignore-scripts 到 standby 槽（依赖装全）
4. 注入 assets/native/node-pty/pty.node
5. patcher 以 DSH_ROOT=standby 执行
6. 安装 @img/sharp-wasm32@<同版本>（联网，可跳过但需告警）
7. 冒烟验证：以 standby 槽启动 headless，等待 token URL 出现，
   校验进程存活 / 端口可连通 / 插件树无 fatal，5 分钟内完成
8. 成功 → 写 slot.json(standby, version, state=pending)，提示「下次启动生效」
9. 失败 → 删除 standby 内容，报告失败阶段
```

**F2.3 补丁自检（硬性要求）**

- patcher 输出中出现 `WARN ... anchor not found` 时，**必须判定失败并回滚**，禁止「带病启动」。
- 理由：锚点丢失的后果不对称——sandbox 平台链缺失会让 `bash` 工具 fail-closed，而 App 看起来启动正常，属于沉默故障。

**F2.4 版本兼容矩阵**

- 维护一份「dsh 版本 × 补丁可用性」清单，已有实测：

| dsh 版本 | patcher 结果 | 结论 |
|---|---|---|
| 0.2.0-rc.2 | 全部命中 | 当前现役 |
| 0.2.2-rc.1 | 8 项执行 / 2 项预期跳过 / **0 warning** | 可升级 |
| 其它 | 未验证 | 默认拒绝，需 `--accept-risk` 显式确认 |

**F2.5 回滚与降级**

- 设置页提供「回滚到上一版本」（`standby` 与 `active` 互换）。
- 内置 APK 版本只在「现役缺失或更旧」时重装，不覆盖手动升级（保留现有 `activeNeedsPinned` 语义）。

**F2.6 会话与配置兼容**

- 会话格式必须仍为 `session.v4.jsonl.zstd`（0.2.x 未引入 v5）；升级后历史会话可读可续写。
- `~/.dsh/profiles/web/cordis.patch.yml` 的插件 id（`ui-settings-general`、`agent-default-model`）在新版本仍存在（已核对 0.2.2-rc.1）。

### 4.4 验收标准

1. 设备内完成 0.2.0-rc.2 → 0.2.2-rc.1，全程 ≤ 5 分钟（含下载），重启后 `slot.json` 显示新版本且 `state=verified`。
2. 升级后逐项通过：会话列表 → 打开历史会话 → 继续对话；插件页可打开；`bash` 工具可执行命令；`read_image` 可用。
3. 故障注入：① 断网；② 手工破坏一处锚点；③ 磁盘写满一半 —— 三种情况均自动回滚，现役版本不变、会话无损。
4. 连续两次升级（0.2.1 后再到 0.2.2）不产生残留目录，磁盘无累积。

### 4.5 风险

| 风险 | 影响 | 缓解 |
|---|---|---|
| 上游改动被锚定的 10 个文件 | 补丁失效 → 升级被拒 | 已有 F2.3 硬门禁；矩阵登记 |
| npm 依赖体积（每槽约 566 MB） | 磁盘翻倍 | 升级前检查空间；失败即清理 |
| 镜像不同步 | 查不到新版本 | 优先官方源，镜像作回退 |
| sharp wasm 需联网 | 读图不可用 | 可跳过但必须在结果里明确标注 |

---

## 五、R3 状态栏 / 手势条 UI 优化

### 5.1 目标

- 状态栏与手势导航条**透明**，内容铺满，无独立色块；
- 手势条（小白条）背后的系统蒙层关闭，让小白条悬浮在内容上；
- 任何界面下内容不被系统栏遮挡，键盘弹出不挡输入框。

### 5.2 实现要点

1. 主题层：两套主题的 `statusBarColor` / `navigationBarColor` 改透明，`windowDrawsSystemBarBackgrounds=true`，刘海模式 `never → shortEdges`。
2. 代码层：`enableEdgeToEdge(SystemBarStyle.dark(TRANSPARENT))` + `setDecorFitsSystemWindows(false)` + **`isNavigationBarContrastEnforced=false`**（小白条悬浮的关键）。
3. 内缩策略两档：
   - `fullBleed=false`（默认）：栏透明、背景统一，内容留在安全区；
   - `fullBleed=true`：内容真铺到栏下，需要页面侧 safe-area 支持。
4. 覆盖范围：dsh Web、终端、设置、用量、诊断、浏览器工作台（共 10 个 Activity）。

> 已有可编译补丁草案：`phone-agent-沉浸式系统栏.patch`（12 文件，+83/−5，未编译验证）。

### 5.3 验收标准

1. 状态栏区域无独立色带，与页面背景连成一片。
2. 手势条悬浮在内容上，背后无灰色蒙层。
3. dsh Web 页面顶部标题不被状态栏遮住；设置页顶部标题完整可见。
4. 横屏（`shortEdges`）时刘海/挖孔区内容不被切断。
5. 键盘弹出时输入框仍可见（保持 `adjustResize` 行为）。
6. 终端最后一行不被手势条压住。

### 5.4 风险

| 风险 | 说明 |
|---|---|
| 双重内缩 | Miuix 组件若自带 inset，会出现多一段空白；需实机确认后去掉 `padContentBySystemBars()` |
| Web 内容上移 | dsh web UI 无 safe-area 感知，`fullBleed=true` 时可能遮挡；默认关闭 |
| targetSdk 28 | Android 15+ 不会自动边到边，必须显式调用（已处理） |

---

## 六、非目标

- 不新增任何 agent（不接 codex/claude/opencode/zcode）。
- 不改 dsh 自身行为、不改 web UI 布局。
- 不做应用商店上架、不改 `targetSdk 28`（W^X 取舍）。
- 不重写 patcher（只加「失败即中止」语义）。

## 七、约束

- 保留 A/B 槽位与 patcher 架构（这是自助升级的地基）。
- 保留设备工具通道与 `pa-*` 契约（AGENTS.md 依赖它们）。
- 构建需走 CI（本地构建缺 `runtime.tar.xz`，首启会崩）。
- 签名不同，自建包无法覆盖官方包：需改 `applicationId` 并存或卸载重装。

## 八、交付物与里程碑

| 里程碑 | 内容 | 产出 |
|---|---|---|
| **M1** | R1 瘦身 | 删除清单 + 代码收敛 patch + CI 产物 ≤130 MB |
| **M2** | R2 更新器 | 版本查询 UI + 备用槽安装流水线 + 补丁硬门禁 + 回滚 |
| **M3** | R3 系统栏 | 沉浸式补丁 + 实机视觉验收 |

依赖顺序：M1 → M2（M2 的槽位逻辑依赖单 agent 简化）；M3 可与 M1/M2 并行。

## 九、待确认问题

1. **dsh 终端是否保留**？dsh 0.2.x 没有 TUI 表面（只有 web/headless/sdk/acp），当前「dsh TUI」实际落到 CLI shell。保留它（可跑 headless 命令）还是干脆去掉？
2. **更新入口位置**：设置页「Agent 更新」内加入口，还是独立页面？
3. **未知版本策略**：默认拒绝（需确认）还是允许并提示风险？
4. **R3 默认档位**：保守（`fullBleed=false`）还是直接上真沉浸？
5. **瘦身版命名**：包名沿用 `com.phoneagent`（需卸载重装）还是加后缀与官方版并存？

---

## 附录 A：现状证据

| 项 | 位置 / 值 |
|---|---|
| dsh 版本硬编码 | APK dex 字符串 `install_pkg "deepseek-ai-dsh-0.2.0-rc.2.tgz"` |
| 槽位状态 | `files/agents/dsh/slot.json` = `{"active":"a","activeVersion":"0.2.0-rc.2","state":"verified"}` |
| patcher | APK `assets/scripts/patch-dsh-android.mjs`（10 组补丁） |
| 安装日志 | `/data/data/com.phoneagent/cache/dsh.log`（含 532 包安装与 10 条补丁结果） |
| 资产构成 | `terminal-extra` 318 MiB、`zcode-web` 127 MiB、`zcode-server` 116 MiB、`packages` 30 MiB、`runtime.tar.xz` 41 MiB |
| 0.2.2-rc.1 补丁实测 | 8 项执行 / 2 项预期跳过（启动器不存在、sharp 需联网）/ **0 warning** |
| 版本源 | npm latest `0.2.2-rc.1`；腾讯镜像同步 |

## 附录 B：已产出的前置工件

- `phone-agent-沉浸式系统栏.patch` —— R3 的可编译草案（12 文件，已通过 `patch --dry-run`）
- `phone-agent-沉浸式系统栏-说明.md` —— 实现细节与验证清单
- `DSH-phone-agent-升级评估报告.md` —— R2 的可行性与补丁兼容性依据

---

*本文档为需求定稿，不含排期。实施前请先回答第九节的 5 个问题。*

---

## 附录 C：决策记录（2026-11-13 第二轮）

用户对第九节的答复，以及由此改写的实现口径：

| 问题 | 决策 | 落实方式 |
|---|---|---|
| 1. dsh 终端是否保留 | **不保留**。平板另有 Termux 兜底 | 终端页、`terminal-emulator`/`terminal-view` 模块、`ExtraKeys`、`SessionSwitcher` 全部删除；命令执行链不受影响（`bash` 工具走 `usr/bin/bwrap` 垫片 + landlock/proot，PATH 由 `NodeRuntime.environment()` 注入） |
| 2. 更新入口 | 设置页「Agent 更新」内，**不走官方更新通道** | 自建 registry 查询 + 备用槽安装（`AgentUpdate` 已收敛为 dsh 单包） |
| 3. 未知版本策略 | **不拒绝** | 允许安装，UI 标注「未验证版本」并二次确认；只有 patcher `WARN anchor not found` 仍判定失败并回滚 |
| 4. R3 默认档位 | 待定（默认 `fullBleed=false`） | `Immersive.fullBleed` 开关保留，默认关 |
| 5. 包名 | **并存**：`com.dshandroid.next` | 已改；旧包迁移延后到本分支自测通过之后 |

补充：旧包数据（`files/home` + `.dsh/sessions`）迁移暂缓，等瘦身版自测稳定后再做；换包名会改路径前缀，
届时需要同步改写会话目录名、`storages/workspace.json` 与 `session_projcache/*.json` 里的绝对路径，
API Key 因绑定 Android Keystore 必须重填。
