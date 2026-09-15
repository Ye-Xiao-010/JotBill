# JotBill —— AI 协作说明

> 本文件供 AI 编程助手读取。**改代码前请先读完本文件。**
> 最后更新：2026-09-15

## 一、项目结构

```
jotbill-app/          React Native + 原生 Android（Kotlin）—— 当前开发重心
  android/app/src/main/java/com/jotbill/app/
    recognition/      ★ 识别子系统（自动截屏 / 支付匹配 / 入账）—— 主要改动区
    pending/          待确认中心
    notification/     支付通知（v1 停用，代码保留）
    overlay/          悬浮窗
  android/app/src/test/java/com/jotbill/app/   Kotlin 单元测试
litenote-backend/     NestJS 后端（历史遗留，非当前重心）
docs/                 设计与裁定文档 —— 改代码前必读
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

**必须用 `gradlew.bat`，不能用 `./gradlew`** —— 本机 Git Bash 缺 `uname`/`xargs`，bash 版脚本会直接失败（报 `uname: command not found`）。

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

## 七、当前开工状态（2026-09-15）

- 文档已就位：分支 `docs/version-b-design-chain`，`NEW_PROJECT_KICKOFF_V2.md` 版本 **0.3.3**。
- **8 项决策已定案**（`IMPL_PLAN` §8），**无遗留待拍板项**。
- **建议开工顺序**：**V-1a（纯删除，零行为变化）** → V-1b（行为改动）→ V-2（Y4 + 页面金额字段）→ V-3（改造 B + E）→ V-4（触发点后移，**含 D-11**）→ V-5（落库顺序）。
- ⚠️ **批次结构已于 2026-09-15 修订**（详见 `IMPL_PLAN` §4 的注）：
  1. **批 V-1 拆为两小批** —— **V-1a「纯删除」**（D-10 死枚举 + 死入口清理 + D-20 删死代码，验收仅"编译通过 + 全测试绿"）/ **V-1b「行为改动」**（D-9 / D-16 / D-19 / D-21 / D-6，逐项独立验证）。理由："规格完整 ≠ 零风险"，混做会让测试红**无法归因**。
  2. **D-11 从 V-1 移至 V-4** —— 它的 `Service:934-936` 是**当前活的**派发路径；单独先删会出现"旧触发点已删、新触发点未接"的空窗。它与 V-4 接入 `PAYMENT_RESULT` 是**同一语义变更的两半**。
- ⚠️ **`DECISIONS.md` 不覆盖全部 D 项** —— 它是按主题分节的"决策日志"；**D-10 / D-11 / D-20 在其中无编号行**（2026-09-15 实测）。裁定口径请查 `CONFLICT_REVIEW_SESSION_2026-09-11.md` 的**待议总表**。
- ⚠️ **批 V-3 的硬前置**：`IntentCoordinator` 侧当前**拿不到 `sessionId`**（`SafeCheckoutContext` / `StableCheckoutPage` / `CheckoutPageObservation` 三者均无该字段）⇒ 须先补"sessionId 传递链路"，否则改造 B 的 `retained.sessionId` 编译不过。
