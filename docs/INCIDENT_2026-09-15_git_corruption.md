# 事故记录：本地 git 仓库对象库损坏（2026-09-15）

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
