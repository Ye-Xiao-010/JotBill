# 事故记录：本地 git 仓库对象库损坏（2026-09-15）
> **状态**：[归档] —— git 仓库损坏事故 + 恢复过程（含**备份位置**信息）
> **⚠️ 撞名**：本文的「方案 A / B / C」指**仓库恢复方案**，与「版本 A / B」（送 AI 时机）**无关**
> 当前状态见 `00-现状正本.md`

> **结论先行**：**工作成果完好无损**（所有代码与文档改动都在工作区）。丢失的是 **git 提交历史**。
> **已处置**：关键文件已备份至 `D:\JotBill_backup_20260915`。

---

## 一、现象

| 现象 | 证据 |
|---|---|
| 分支引用丢失 | `git log` 报 `your current branch 'docs/version-b-design-chain' does not have any commits yet` |
| `.git/refs` / `.git/logs` 目录缺失 | `test -d .git/refs` → 缺失；`test -d .git/logs` → 缺失 |
| **pack 数据文件丢失** | `.git/objects/pack/` 下**只有 `.idx` 索引**，**没有对应的 `.pack` 数据文件** |
| 对象不可读 | `git cat-file -t ae2035d` / `b23a6de` / `69082f1` / `ed78242` 全部 `could not get object info` |
| 提交遍历失败 | `Failed to traverse parents of commit 1404259...`（父 `ed78242` 读不到） |
| loose 对象残留 | `.git/objects/` 仅剩 16 个两字符目录（01/04/13/14/1e/26/34/37/3f/4a/53/6e/73/77/b3） |

**性质**：`.git/objects/pack/` 的 **`.pack` 数据文件缺失** ⇒ 打包对象全部不可读 ⇒ 历史链断裂。

---

## 二、时间线（推测）

1. 本次会话中，批 V-1a 提交 `88e5ceb`、文档提交 `ed78242`、D-6 提交 `1404259` 均**成功**（当时 `git log` 正常）
2. 随后执行 `git add docs/ && git commit` 时，输出显示 **`(root-commit)`** ⇒ 说明**提交时分支已处于 unborn 状态**（refs 已丢）
3. 该 root commit（`3f7cc9f`，660 文件）随后也消失（无 ref 指向，被清理）
4. 诊断过程中执行 `git checkout -f -B docs/version-b-design-chain 1404259` **被 SIGTERM 中断**（工具超时）
5. 此后 git 报 `not a git repository`，进一步排查发现 `.git/refs`、`.git/logs` 缺失

> ⚠️ **可能诱因**：第 4 步的 `checkout -f -B`（涉及数百文件的工作区更新）**被强制中断**，git 正处于写 refs 的中间态 ⇒ 加剧了损坏。**但 refs 丢失早于该步**（第 2 步已显症状），故根因可能更早（外部并发操作或环境问题）。

---

## 三、影响评估

### ❌ 丢失
- **提交历史**：`ae2035d`（文档落盘）、`88e5ceb`（V-1a）、`ed78242`（文档）、`1404259`（D-6）的对象**均不可读**
- 以及更早的 `69082f1` 及其祖先（`main`/`master` 等分支的对象）
- **所有本地分支的对象数据**（pack 数据缺失）

### ✅ 完好
- **工作区全部文件**：
  - 代码含 V-1a 的全部删除（`PaymentOverlayManager.kt` 已删除、4 个死入口已删、`PAYMENT_PAGE_VISIBLE` 已删）
  - 代码含 D-6 的全部改动（两处 30 秒桶 + KDoc 修正 + 2 个新测试）
  - `docs/` 全部文档（KICKOFF 0.3.3、IMPL_PLAN §8、CLAUDE.md 等）
- `packed-refs` 文件本身完好（但指向的对象不可读）

### ⚠️ 无法追溯
- `docs/version-b-design-chain` 分支**从未推送** ⇒ 远程没有该分支的副本
- 若远程可达，**只能恢复 `main`/`master` 等已推送分支的历史**，本会话的工作仍需重新提交

---

## 四、已执行的保护措施

| 措施 | 结果 |
|---|---|
| 备份 `docs/` | ✅ → `D:\JotBill_backup_20260915\docs` |
| 备份 `jotbill-app/android/app/src/` | ✅ → `D:\JotBill_backup_20260915\app_src` |
| 备份 `CLAUDE.md` | ✅ → `D:\JotBill_backup_20260915\CLAUDE.md` |
| 远程可达性探测 | ❌ 失败（代理 `127.0.0.1:7890` 未启动，Clash 未开） |

---

## 五、恢复方案（待用户决定）

### 方案 A：从远程恢复历史 + 重新提交本次工作（**推荐**）
1. 启动 Clash 代理（或临时 `git -c http.proxy= -c https.proxy=` 直连）
2. `git fetch --all` ⇒ 恢复 `main`/`master` 及 codex/roadmap 分支的历史对象
3. 基于恢复的历史重建 `docs/version-b-design-chain` 分支
4. 把**工作区当前状态**作为新提交（V-1a + D-6 + 文档一起，或按主题拆若干提交）

**代价**：本次的提交粒度丢失（V-1a / D-6 会合并或重拆），内容不丢。

### 方案 B：完全重建仓库
1. 保留工作区（已备份）
2. 删除损坏的 `.git`，`git init` 新建仓库
3. 首次提交工作区全部内容
4. 重新配置 remote

**代价**：全部历史丢失（只保留工作区快照）。适用于方案 A 不可行时。

### 方案 C：先不动，等确认
先确认是否存在**其他副本**（其他 clone、其他机器、IDE 的 local history），再决定。

---

## 六、教训（写入协作纪律）

1. **⚠️ 绝不在可能超时的情况下运行大型 git 工作区操作** —— `git checkout -f -B`（需更新数百文件）在本环境容易触发工具超时，SIGTERM 中断会破坏 git 内部状态。**应改用 `git update-ref`（不动工作区）或分步执行**。
2. **⚠️ 提交后立即验证对象可读** —— 不能只看 `git commit` 的"成功"输出。建议 `git log --oneline -1` + `git cat-file -t HEAD` 双确认。
3. **⚠️ 提交前确认分支非 unborn** —— `git commit` 在 unborn 分支上会静默产生 root commit（全量文件），破坏历史连续性。
4. **重要分支应及时推送远程**（`docs/version-b-design-chain` 从未推送，导致无远程副本可用）。
5. **定期确认 `objects/pack/*.pack` 与 `*.idx` 成对存在** —— 只有 `.idx` 没有 `.pack` 是对象库损坏的明确信号。

---

## 七、恢复结果（2026-09-15 最终，**已恢复可用**）

### 恢复动作

| 步骤 | 动作 | 结果 |
|---|---|---|
| 1 | 从备份 `E:\代码副本\9.15 jotbill 项目改造前备份\JotBill\.git\objects\pack` 复制 `.pack` / `.rev` | ✅ 恢复了 `b23a6de`、`main`/`master`/所有 `roadmap/*`/`codex/*`/`origin/*` 等分支的对象 |
| 2 | 从备份复制 200+ 个 loose object 目录 | ✅ 恢复了 `ae2035d`、`69082f1`、`1404259` 的 commit 对象 |
| 3 | 整体替换 `.git`（用备份的完整 `.git`） | ✅ 结构完整化 |
| 4 | 删除损坏的 `.git/index` 并 `git reset` 重建索引 | ✅ **`git status` / `log` / `diff` 全部恢复正常** |
| 5 | 分支指向 `b23a6de`（**已验证 tree 完整**） | ✅ 仓库进入**完全可用**状态 |

### 最终状态

| 项 | 状态 |
|---|---|
| **工作区文件** | ✅ **完好** —— 所有改动在位（V-1a 的删除、D-6 的两处 30 秒桶、全部文档） |
| `git log` / `status` / `diff` | ✅ **全部可用** |
| 历史（到 `b23a6de` = main） | ✅ 可用（`main`/`master`/`roadmap/*`/`codex/*`/`origin/*` 均可读） |
| **`b23a6de..ae2035d` 之间的提交对象** | ❌ **丢失** —— 它们在 `pack-84326b67` 中，而该 `.pack` **本机与备份两处都缺失** |
| 备份副本 | ✅ **全程只读**，未修改 |

### 遗留影响

1. `docs/version-b-design-chain` 现指向 `b23a6de`（main，批0 安全网）⇒ **`git status` 显示的差异 = b23a6de 之后的所有工作**（含 69082f1 系列 + V-1a + D-6 + 全部文档），比实际"本次改动"大得多（正常现象）。
2. 会话中此前的 4 个提交（`ae2035d` / `88e5ceb` / `ed78242` / `1404259`）**提交粒度丢失**，但**内容不丢**（都在工作区）。
3. **待决策**：① 直接提交工作区（一个大提交）；② 或先开代理从 GitHub 恢复更新的历史后再提交。

### 附带发现（备份的隐患）

⚠️ **备份的 `.git` 本身也不完整** —— 它同样缺 `pack-84326b67...pack`，只留了 `.idx`。
⇒ **建议**：今后做项目备份时，**校验 `.git/objects/pack/` 下 `.pack` 与 `.idx` 成对**，或直接备份完整仓库 + 推送远程（双保险）。

---

## 八、后续复检与二次修复（2026-09-15 23:4x，事后体检）

> 恢复完成后做例行环境体检，**发现一处恢复时未清除的隐患**，已修复。

### 8.1 新发现：失效的 `multi-pack-index`（派生缓存）

**症状**（体检时的原始输出）：

| 命令 | 结果 |
|---|---|
| `git fsck --full` | ❌ 报出**上千条** `failed to load pack in position 1` + `failed to load pack entry for oid[...]` |
| `git multi-pack-index verify` | ❌ 同样报错，**exit=1** |
| `git count-objects -v` | ⚠️ `warning: no corresponding .pack: .git/objects/pack/pack-84326b67b23623614f4fa6e6841d26fd8a9c7ddb.idx`；`garbage: 1` |

**根因**：恢复时从备份替换 `.git`，但**事故期间的孤儿 `.idx`（`pack-84326b67...idx`，其 `.pack` 已永久丢失）仍留在 `objects/pack/`**，而 `multi-pack-index` 这个派生缓存仍把它算作有效 pack ⇒ 任何走 MID 的对象查询都会失败。

**定位手法（可复用）**：对照跑一次禁用 MID 的 fsck ——

```bash
git fsck --connectivity-only                                    # 报上千条错误
git -c core.multiPackIndex=false fsck --connectivity-only       # 只剩 2 处已知丢失对象
```

⇒ 错误全部消失 = **对象库本身没坏，坏的是 MID 缓存**。另用 `git verify-pack -s <idx>` 逐个校验，两个真实 pack **均 exit=0**，进一步佐证。

### 8.2 修复动作（按"删除类先备份"惯例）

| 步骤 | 动作 | 结果 |
|---|---|---|
| 1 | 备份 2 个失效索引 → `D:\JotBill_backup_20260915\git_repair_20260915\` | ✅ |
| 2 | 删除孤儿 `pack-84326b67...idx` | ✅ |
| 3 | 删除失效的 `multi-pack-index` | ✅ |
| 4 | `git multi-pack-index write` 重建 | ✅ `multi-pack-index verify` **exit=0** |

### 8.3 修复后体检结论：**环境健康，可继续开工**

| 检查项 | 结果 |
|---|---|
| `.git` 结构（`refs`/`logs`/`index`/`HEAD`） | ✅ 齐全，无 `index.lock` 等残留 |
| `git fsck --full` | ✅ 上千条伪错误**清零**，仅剩 2 处**已知永久丢失**（`missing tree 77c7e88b` / `missing commit ed78242`）+ 1 个破损悬空提交 `1404259`（内容已在 `e949156`） |
| 对象库 | ✅ 404 loose / 5823 in-pack / **2 packs（`.pack` 与 `.idx` 全部成对）**；`garbage: 0` |
| 工作区 | ✅ 干净（`git status --short` 空，`git diff HEAD` 空） |
| 历史可达 | ✅ `main` 262 / `master` 90 / `roadmap/batch4-journey` 278 / `codex/phase7-integration` 217 个提交 |
| 远程 | ✅ `git ls-remote origin`：`main` 与 `docs-version-b-design-chain` 均 = `e949156` |
| 构建 + 测试 | ✅ `:app:testDebugUnitTest --rerun` ⇒ BUILD SUCCESSFUL，**261 tests / 0 failures / 0 ignored / 6.929s** |
| 个人账单数据 | ✅ 微信/支付宝账单文件**未被跟踪**（`.gitignore` 生效） |
| 大文件 | ⚠️ `docs/qa/device/2026-09-10-e-detail-buy-fullchain.logcat.txt` **85 MB 已入库**（超 GitHub 建议上限 50 MB）⇒ 建议后续 Git LFS 或移出 |

### 8.4 🔴 新增教训（已写入 `MEMORY.md`）

**`git fsck` 报错 ≠ 对象库损坏。** 派生缓存（`multi-pack-index`）失效会产生**上千条伪错误**，足以掩盖真实问题、也能让人误判"仓库又坏了"。
⇒ **今后体检必做两项**：① `objects/pack/` 下 `.pack`/`.idx` 成对校验；② `git multi-pack-index verify`。
⇒ 定位手法：`-c core.multiPackIndex=false` 对照跑 fsck 二分。
⇒ 事故恢复**收尾时必须做这一步** —— 恢复只补了对象，**没清派生索引**，才留下这个尾巴。

---

## 九、根因分析（2026-09-15 深夜追加，事后复盘）

> 追问"**为什么会出问题**"。结论：直接触发点是 AI 的一次大工作区 git 操作被强杀；**但 refs 丢失更早，且症状表明有外部程序动过 `.git`**。

### 9.1 能确证的事实

| # | 事实 | 证据 |
|---|---|---|
| 1 | **refs 丢失早于 `checkout`** | 事故第 2 步 `git commit` 输出 **`(root-commit)`** ⇒ 提交时 `.git/refs` 已不存在 |
| 2 | `checkout -f -B` 是**放大**而非根因 | 它是在**已损坏的仓库**上执行，且被 SIGTERM 强杀 |
| 3 | **症状不是 git 能干出来的** | git 从不删除 `.git/refs` / `.git/logs` **目录本身**（删分支只删内部文件、`pack-refs` 只写 `packed-refs`）；删 pack 时 `.pack`/`.idx` 是**成对**处理的 |
| 4 | 非磁盘 / 文件系统故障 | SSD `Healthy` / `OK`；C/D/E 均 NTFS `Healthy`；System 日志 9/14 起**零条** disk / Ntfs / volmgr / Chkdsk 错误 |
| 5 | **不是 Windows Defender** | Defender 隔离区**0 个文件**；`Get-MpThreatDetection` 历史为空 |
| 6 | 本机在跑第三方安全软件 | `360tray`（360安全卫士，`C:\Program Files (x86)\360`）+ `360zipUpdate`；另有 `PrivaZer`（磁盘清理/隐私清理） |

### 9.2 ⭐ 关键新证据：那个 pack **在备份生成时就已经不在**

对比 E 盘"开工前备份"的 `.git/objects/pack/`：

| 位置 | 内容 |
|---|---|
| 本机（事故后） | `pack-240698fc`（成对）、`pack-f537209c`（成对）、**`pack-84326b67` 只有 `.idx`** |
| **E 盘备份（事故前）** | `pack-240698fc`（成对）、`pack-f537209c`（成对）、**`pack-84326b67` 连 `.idx` 都没有** |

⇒ **备份的 `.git` 里也不存在这个 pack** ⇒ 移除动作**早于备份生成时刻**，不是 21:50 那次崩溃造成的。
⇒ 且两份拷贝**都缺同一个文件**，用"git 自己崩溃"无法解释。

⚠️ **替代解释（不能排除）**：也可能是**复制备份时安全软件拦截了对该文件的读写**，导致备份里同样缺它。两种解释都指向同一个外部因素，但"早于备份"这个结论**只有在排除复制拦截后才成立**。

### 9.3 结论（分置信度）

| 置信度 | 结论 |
|---|---|
| **确定** | ① 20:5x 前后 AI 执行 `checkout -f -B`（需更新数百文件）被工具超时强杀；② 在那之前 `.git/refs` 已丢失；③ 非磁盘故障、非 Defender |
| **高** | 有**外部安全/清理类程序**对 `.git` 做过文件级移除（`360tray` 在运行）。"整目录消失 + `.pack` 单独消失而 `.idx` 留下"与"git 自身崩溃"不符 |
| **待确认** | 360 是否实际执行者 —— 需查 360 的隔离区 / 防护日志 |

**🔍 决定性验证动作（只有本机能做）**：

1. 打开 **360安全卫士 → 木马查杀 → 隔离区 / 恢复区**，搜索 `pack-84326b67` 或 `JotBill`
2. 查 **360 → 防护日志 / 操作记录**，看是否有对 `D:\JotBill\.git` 的拦截，**重点看备份生成日之前**
3. 命中 ⇒ 实锤；未命中 ⇒ 继续排查（其他清理工具、便携工具的复制拦截）

### 9.4 放大因素（确定，且可改）

| # | 放大因素 | 说明 |
|---|---|---|
| 1 | **仓库里存了 85 MB logcat（累计百万行提交）** | pack 达 62 MB ⇒ **更容易被安全软件的启发式盯上**，也超 GitHub 50 MB 建议上限 |
| 2 | **`docs/version-b-design-chain` 从未推送** | 本机是唯一副本 ⇒ 出事无退路 |
| 3 | **备份的 `.git` 本身不完整** | "备份"没起到保险作用（同一个 pack 同样缺） |
| 4 | **AI 对"可能超时的大工作区 git 操作"无预判** | 被强杀后还继续在已损坏的仓库上操作，加剧损坏 |

### 9.5 新增防范措施（在前七节基础上追加）

1. **把 `D:\JotBill`（至少 `.git` 目录）加入 360 的信任区 / 白名单** ← 立刻可做，成本最低
2. **提高推送频率**：重要分支一有提交就推送（当前"未获批准不推送"的纪律要配合更短的窗口）
3. **清理大文件**：把 85 MB logcat 移出仓库（或 Git LFS），减小 pack 体积 = 降低被误判概率
4. **备份必须校验**：备份后立刻比对 `.git/objects/pack/` 的 `.pack`/`.idx` 成对性，并跑一次 `git fsck --connectivity-only`
5. **降低自动 repack 风险**：`git config gc.auto 0`（或加大阈值），避免在重要操作中途自动 repack —— **repack 删除旧 pack 时被强杀正是"只留 `.idx`"的经典窗口**

---

## 十、体检补充（2026-09-16 凌晨，工具环境修复后）

> Bash 环境修复（PATH 补 `usr/bin`）后重做体检，得以使用此前不可用的 `ls`/`grep`/`find`/`awk`。**结论：对象库健康，但发现两处新问题。**

### 10.1 ✅ 健康项（实测）

| 检查项 | 结果 |
|---|---|
| pack 结构 | ✅ 2 组 `.idx` / `.pack` / `.rev` **全部成对**，直接 `ls` 确认，无孤儿残留 |
| `garbage` | ✅ **0**（修复前为 1） |
| `git multi-pack-index verify` | ✅ **exit=0** |
| `git fsck --full` | ✅ 仅剩已知的 2 处永久丢失 + 破损悬空提交 `1404259` |
| 远程 | ✅ `ls-remote`：`main` 与 `docs-version-b-design-chain` 均 = `e949156` |
| 磁盘 / 体积 | ✅ 余 43 GB；`.git` = 191 MB |

### 10.2 🆕 抢救出 103 个"不可达提交"中完整的一部分

`git fsck --no-reflogs --unreachable` 列出 **103 个不可达提交**，且**全部零缺失、零破损**。

**其中 `ae2035d`（"docs: 版本B设计链条落盘"）整条链完整**：335 个提交 / 5562 个对象，`ae2035d:docs/IMPL_PLAN_VERSION_B_2026-09-13.md` 的 blob 实测可读（51944 字节）。这条链正是**事故前的工作线**：

```
ae2035d（版本B文档落盘）→ 69082f1 → f2bdc16 → 9329a63(Y4) → a250e96(Y5) → …（共 335 提交）
```

⚠️ **风险**：不可达对象是 `git gc` 的清除对象 —— **一次 `git gc` 就会把它们永久删掉**。

✅ **处置**：新建保护性分支 **`recovered-docs-version-b-chain` @ `ae2035d`** ⇒ 不可达提交从 **103 降到 29**（**救回 74 个**），分支已跨调用验证存活。

### 10.3 🆕 `refs/remotes/*` 在本环境**无法被 git 写入**（可复现）

**判别实验**（同一 SHA，逐命名空间调用 `git update-ref`）：

| 命名空间 | 结果 |
|---|---|
| `refs/heads/*` | ✅ 成功 |
| `refs/tags/*` | ✅ 成功 |
| `refs/notes/*` | ✅ 成功 |
| **`refs/remotes/origin/*`** | ❌ **静默失败：`exit=0`，引用不存在** |
| **`refs/remotes/other/*`** | ❌ **同样静默失败（与 remote 名无关）** |

**后果**：`git fetch origin` 打印 `c411522..e949156  main -> origin/main`，但 `origin/main` **仍是旧值** ⇒ 这就是"远程追踪引用刷新滞后"的真正机制（此前只当作"缓存滞后"，实为**写入失败**）。

**已排除**：非 reflog（`-c core.logAllRefUpdates=false` 仍失败）／非 safe-bin 包装（`git` 未被包装）／无 `GIT_*` 环境变量／`core.hooksPath` 未设置／hooks 目录无非 sample 文件。

**绕行**：手工写松散 ref 可生效 —— `printf '<sha>\n' > .git/refs/remotes/origin/main`（已按此修正 `origin/main`）。判断远程真实状态**始终以 `git ls-remote origin` 为准**。

### 10.4 🆕 `.git/.git` 是一个完整嵌套仓库（恢复残留）

- `.git/.git/` 内含 `HEAD` / `config` / `index` / `objects` / `packed-refs` / `refs` / `logs` / `worktrees` **全套**
- 其 `HEAD` → `ref: refs/heads/docs/version-b-design-chain`（外层 `.git/HEAD` → `refs/heads/main`）
- **判定**：事故恢复过程的残留（疑似旧 `.git` 被移入 `.git/` 内未清理）
- ⏳ **待处置**：是否删除需用户决定 —— 属 `.git` 内删除，**须先备份**（本轮未擅动）

### 10.5 ⚠️ 未解现象：`refs/remotes/origin/` 目录会自行消失

手工写入并**跨调用验证为"存活"**之后，隔两次调用再查，`ls` 报 `No such file or directory` —— 整个 `origin/` 子目录不见了。同期 `refs/heads/*` 稳定存活。**机制未查明**。

> 这与本事故的核心症状（`refs`、`logs` **目录消失**）形态相同 ⇒ **可能存在仍在活动的同一诱因**，建议持续观察。


