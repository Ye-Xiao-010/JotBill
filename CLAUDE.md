# JotBill —— AI 协作说明

> 本文件供 AI 编程助手读取。**改代码前请先读完本文件。**
> 最后更新：2026-11-04

## 一、项目结构

```
jotbill-app/          React Native + 原生 Android（Kotlin）—— 当前开发重心
  android/app/src/main/java/com/jotbill/app/
    recognition/      ★ 识别子系统（自动截屏 / 支付匹配 / 入账）—— 主要改动区
    pending/          待确认中心
    notification/     支付通知（活跃：N-1 放行 + N-2 强证据入账；以代码为准，2026-11-04 核实）
    overlay/          悬浮窗
  android/app/src/test/java/com/jotbill/app/   Kotlin 单元测试
litenote-backend/     NestJS 后端（历史遗留，非当前重心）
docs/                 设计与裁定文档 —— 改代码前必读（导航与状态标记见 docs/README.md；冲突时以代码为准）
```

## 二、改代码前必读（三份互补，缺一会漏规格）

1. **`docs/IMPL_PLAN_VERSION_B_2026-09-13.md`** —— 实施方案（回答"代码怎么改"）。其中 **§8「开工前决策与修正」= 已定案的 8 项决策**，含阻断级前置项。
2. **`docs/CONFLICT_REVIEW_SESSION_2026-09-11.md`** —— 见其「**代码评估清单**」节：批 V-1 的逐条规格在此（含 D-9/D-10/D-11/D-16/D-19/D-20/D-21 + 死入口清理）。
3. **`docs/DECISIONS.md`** —— 裁定正典（D 系列速查）。

## 三、标准协作流程（每个批次都按此执行）

> 以下流程已固化为**项目约定**。接到任务**直接按此执行**，无需用户重复说明。

### 第 0 步：读材料
按「二、改代码前必读」读完对应章节，**不要跳过**。

### 第 1 步：先复述，等确认（**此时不要改任何代码**）

输出以下内容，然后**停下等确认**：

1. **理解**：任务各项分别要改什么、依据是什么
2. **改动清单**：逐个文件列出具体改动点（方法名 / 常量 / 枚举值 / 整文件删除）
3. **自行复核**：文档中"无 caller""死代码""行号"等结论**可能是几天前的实测** —— 必须自己 **grep 全仓复核**并报告结果。若发现与文档不符 ⇒ **立即停止并报告，不要改**
4. **风险点**：识别到的"可能误改 / 误删"项
5. **将要运行的验证命令**

### 第 2 步：实施
严格按**确认后的清单**执行，**不顺手改清单外内容**。同步清理 import / 注释 / KDoc / 常量 / 测试中的引用。

### 第 3 步：验证（**必须做，不得跳过**）
```bash
cd jotbill-app/android
./gradlew.bat :app:compileDebugKotlin      # 必须 BUILD SUCCESSFUL
./gradlew.bat :app:testDebugUnitTest       # 必须全绿
```
把命令的**实际输出**贴进报告。**不得只写"测试通过"而不贴证据。**

### 第 4 步：报告（固定格式）
```
### 1. 改动清单
| 文件 | 改动 | 说明 |

### 2. 复核结果
（每个"死代码 / 无引用"结论：grep 命令 + 实际结果）

### 3. 验证结果
（两条命令的实际输出 + 测试通过数）

### 4. 遗留与存疑

### 5. 建议的 commit message
```

### 第 5 步：等用户确认后再提交
**改完先不提交、不推送**，等用户 review 决定。

---

## 四、构建与验证（已实测有效）

**命令用 `gradlew.bat` 或 `./gradlew` 均可**（2026-09-16 起 bash 环境已修复；下方命令沿用 `gradlew.bat`，以兼容其他未修复的机器）。

> ### 背景：本机 Bash 曾缺一批常用命令（已修复）
>
> **症状**：`ls` / `cat` / `grep` / `head` / `tail` / `sed` / `awk` / `wc` / `uname` / `xargs` / `dirname` 全部不可用 ⇒ bash 版 gradlew 直接失败，只能用 `gradlew.bat`。
> **根因**：Git for Windows 只把 `C:\Program Files\Git\cmd` 写进系统 PATH；`usr\bin` 是 `git-bash.exe` 启动时才注入的。而本环境的 Bash 工具**直接调 `bash.exe`**（`...\PortableGit\versions\1.2.0\bin\bash.exe`），**从不经过 git-bash** ⇒ `usr\bin` 永远不在 PATH 里。
> **连带现象**：BASH_ENV 脚本 `shell-runtime-bash-env.sh:3` 的 `$(dirname ...)` 因此失败，每次 bash 调用都打印 `dirname: command not found` + `cd: null directory`，并导致 **safe-bin 安全脚本静默未加载**。
> **修复**：`C:\Program Files\Git\usr\bin` 已**追加**（未前置）到 Windows **用户级** PATH —— 注册表 `HKCU\Environment\Path`，**值类型保持 `ExpandString`**（未降级为 `REG_SZ`，`%DevEco Studio%` 等引用完好）；原值备份于 `D:\JotBill_backup_20260915\user_path_backup_20260916.txt`。
> **验证（重启后实测）**：11 个命令全部恢复；**BASH_ENV 报错消失**；`rm` / `unlink` / `rmdir` 的安全包装函数层**已激活**（此前静默未加载）；`./gradlew --version` 输出 `Gradle 8.14.3`。`find` 仍解析到 `C:\Windows\system32\find` —— 追加不前置的预期结果，未覆盖系统工具。

⚠️ **若换到未修复的机器上工作**（两招 + 两类误判）：

- Bash 调用开头加一行：`export PATH="/c/Program Files/Git/usr/bin:$PATH"`
- 判断文件是否存在**只能用 `test -f`** —— 不能用 `cat x || echo "不存在"` 推断（`cat` 缺失会让 `||` 分支误触发，**本项目已两次踩此坑**）
- **`cmd | grep ... || echo 兜底` 会给假阴性** —— 同样因 `grep` 缺失直接走到 `||`（2026-09-15 实测误判为「无告警」，实际有）

⚠️ **仍然有效（与上述修复无关）**：

- **删文件优先用 PowerShell `Remove-Item -LiteralPath`，一次只给一个路径** —— `rm` 被 safe-bin 安全包装拦截，且可能"删一部分后静默失败"。
- **PowerShell 写日志必须 `Out-File -Encoding utf8`** —— `Tee-Object` 产出 UTF-16LE，Read 工具会报 "Cannot display content of binary file"。
- **测试计数只看** `android/app/build/reports/tests/testDebugUnitTest/index.html` 的 `<div class="counter">`；用 `[int]$d.testsuite.tests` 粗聚合会得虚高值（曾误得 378，真实 261）。

```bash
cd jotbill-app/android

# ① 编译（最快，改完先跑这个）
./gradlew.bat :app:compileDebugKotlin

# ② 跑单个测试类
./gradlew.bat :app:testDebugUnitTest --tests "*CheckoutSnapshotBufferTest*"

# ③ 跑 recognition 全包测试
./gradlew.bat :app:testDebugUnitTest --tests "com.jotbill.app.recognition.*"
```

- 增量构建约 **2 分钟**（实测 1m47s）。
- Android SDK 由 `android/local.properties`（`sdk.dir=D:/Android/Sdk`）提供，**无需**设 `ANDROID_HOME`。
- **改完必须至少跑 ①**；涉及识别链路时跑 ②/③。
- 测试报告：`android/app/build/reports/tests/testDebugUnitTest/index.html`

## 五、硬性纪律

1. **行号会漂，用符号名定位** —— 文档中的行号（如 `Buffer:352`）仅作参考；重新定位请用符号名（`hasRetainableCandidate`、`store()`、`moveToPaymentProcess`）。

2. **凡改「常量 / 键 / 枚举」类，先全仓 grep 找所有生成点** —— 本项目多次出现"改一处漏一处"。
   - 已实例：结果页事件去重键有**两个**生成点（`PaymentResultParser.kt:209` 主路径 + `CheckoutAccessibilityService.kt:1840` 截图兜底）。只改一个，主路径仍按旧值撞键 ⇒ 问题未修、测试假绿。

3. **文档常显式标注「哪些不动」，不得顺手改** —— 例：`canEstablishUserRetrySession`、`hasAutomaticCandidate` 有真机故障背书，文档明示不动。

4. **敏感页零截图** —— 硬不变式（密码键盘、付款码等）；触碰需格外谨慎并补负向测试。

5. **先复述再动手** —— 接到任务先复述理解 + 列出要改的文件清单，确认后再改。

6. **提交纪律**：本地原子提交 + 清晰 commit message；**未获明确批准不推送远程**。

## 六、文档写作约定

- 历史文档「**加注不覆盖**」：用 `~~删除线~~` + ⚠️ 指向新裁定，**不删原文**（保留取证价值）。
- `docs/qa/device/`、`docs/qa/batch*/device/` 等原始证据目录**只增不改**。
- 裁定流转：先落 `CONFLICT_REVIEW_SESSION` → `DECISIONS.md`（正典）→ `NEW_PROJECT_KICKOFF_V2.md`（设计规格）→ `IMPL_PLAN_VERSION_B`（实施）。
- **不要将自己推断的内容写成"裁定"** —— 推断必须显式标注（本项目已有先例：`IMPL_PLAN` §8 中 8.2 / 8.4 标注为"推断"，未写入 `DECISIONS.md`）。

## 七、当前开工状态（2026-11-04 更新；原 2026-09-15 的 V-1~V-5 批次计划已被后续改造接续，历史状态见 `IMPL_PLAN` §4）

- 分支 `feat/auto-bookkeeping`。**已落地**（截至 2026-11-04，均已提交）：
  1. **采集层**（B1-B3）：站内结果页接入文本解析腿（`78b25b4`）、包名白名单收敛 `RecognitionPackages` + CORE/RESULT_ONLY 分档（`b8cf887`）、银行短信渠道（乙口径：现金兜底自动入账 + 规则命中转待确认，`11ccea2`）。
  2. **多信号合并**：orderId 强键跨源跨包归并（2min 窗、冲突显式拒绝）+ 合并留痕 `mergedSourceEventKeys` + 冷却层 orderId 护栏 + 导入三条件强一致（`3338441`）。
  3. **N-2 吸收**：强证据入账吸收同单待确认卡（先删后写、失败降级归并，`e4df699`）。
- **当前存活链路**（6 条信号源）：无障碍支付结果页文本（微信/支付宝/云闪付/站内）+ 支付通知（N-1/N-2）+ 页面规则引擎（8 渠道）+ 银行短信 + 账单导入 + 手动。
- **已知死代码**（显式标注未删，删除需拍板）：`ensureSession`/`findReferenceForPayment` 无生产调用方（会话链路随截图识别链路 `4c62779` 整链删除后断裂）；`TransactionCandidateEntity.aiOrderId` 恒 null（AI 候选缺席）。详见各符号 KDoc。
- ⚠️ **`DECISIONS.md` 不覆盖全部 D 项** —— `CONFLICT_REVIEW_SESSION_2026-09-11.md` 的**待议总表**是 D 系列唯一完整出处。
- 历史批次（V-1~V-5，2026-09-15 计划）的执行细节与 8 项决策见 `IMPL_PLAN_VERSION_B_2026-09-13.md` §8；其语义已部分被精简版改造（`4c62779`）与上述 B1-B3/合并改造取代，冲突处**以代码为准**。
