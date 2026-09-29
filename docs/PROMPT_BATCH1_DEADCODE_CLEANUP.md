# JotBill 版本 B 重构 —— 第一批开工指令（死代码清理）
> **状态**：[已失效] —— 本文件是交给其他 AI 的一次性开工提示词，对应批 V-1（**已于 2026-09-17 完成**）
> **勿再使用**。批 V-1a / V-1b 的规格见 `CONFLICT_REVIEW_SESSION_2026-09-11.md` 代码评估清单节 与 `IMPL_PLAN_VERSION_B_2026-09-13.md` §4
> 入口：`00-现状正本.md`

> 用法：将本文件全文粘贴给实现用的 AI。**第一批刻意选"纯删除"**，因为它在零行为变化下即可跑通完整工作流（读规格 → 复述 → 实施 → 验证 → 报告）。

---

## 一、角色与背景

你是 JotBill 项目的实现工程师。JotBill 是 React Native + 原生 Android（Kotlin）的智能记账应用，项目根目录 `D:\JotBill`。

当前正在实施一份**已定案的**「版本 B」重构方案（核心变化：送 AI 的触发点由"付款前"后移到"支付成功后"）。本批是整个重构的**第一批**。

## 二、本次任务范围（严格遵守，不得扩大）

**只做第一批：死代码与死枚举清理。**

这是**「纯删除」批次** —— 不改变任何运行时行为，只移除已被取代、已停用或从未接线的代码。

**本批只含以下 3 项（2026-09-15 修订，原因见文末「附二」）：**

| 编号 | 内容 |
|---|---|
| **D-10** | 删除 `PurchaseIntent.PAYMENT_PAGE_VISIBLE` 死枚举及其链路（**实测零触发点**） |
| **死入口清理** | 删除 4 个无 caller 的服务入口方法 |
| **D-20** | 删除 `notification/PaymentOverlayManager.kt` 整文件及其在模块中的注册 |

**待删的 4 个入口方法**（源码实测零 caller）：`requestSubmitAction`、`requestPaymentAppHandoff`、`requestPaymentPageVisible`、`requestUserRetry`。

**必须保留（不得误删）**：`requestPurchaseIntent`（通用入口，批 V-4 要用）、`requestUserRetryForSession`、`requestPaymentNotificationFallback`、`requestPaymentNotificationFallbackSignal`。

> ⚠️ **邻接符号警示**：`requestPaymentNotificationFallback` 与 `requestPaymentNotificationFallbackSignal` 属**通知回退链路**，D-3 裁定"v1 停用但**代码保留**"，**绝不在删除范围**。

> 🔴 **D-11 已从本批移出，改归批 V-4（2026-09-15 裁定）**
>
> **原因**：D-11 的 `Service:934-936` 是**当前活的**派发路径（提交订单点击 ⇒ 消费快照 + 派 AI）——删它会**改变运行时行为**，与本批"零行为变化"的定位冲突；且它与批 V-4"接入 `PAYMENT_RESULT`"属**同一语义变更的两半**（旧触发点换新触发点），分做会出现"两个版本都不完整"的中间态。
>
> **本批严禁触碰**：`CheckoutSubmitActionDetector`（整文件）、`shouldTriggerSubmitAction`、`triggerSubmitAction`、`Service:934-936` 调用点、`submitActionTriggeredKey`、`PurchaseIntent.SUBMIT_ACTION` 枚举值、Coordinator 的 `SUBMIT_ACTION ->` 分支，以及两个测试文件中的 `SUBMIT_ACTION` 用例。

**❌ 明确不在本批**（属后续批次，不得顺手改）：**D-11**、D-9（敏感页提前解除）、D-16（自动优先）、D-19（金额容差）、D-21（0 金额弹窗）、D-6（去重键收窄），以及任何与"触发点后移 / 支付过程槽位 / handoffContext 映射化"相关的改动。

## 三、首先读材料（不要跳过）

1. **`CLAUDE.md`**（项目根）—— 项目结构、构建与验证命令、硬性纪律。**必须完整读完。**
2. **`docs/CONFLICT_REVIEW_SESSION_2026-09-11.md`** —— 「代码评估清单」节，找到 **D-10 / D-11 / D-20 / 死入口清理** 四条（含逐条规格、文件位置、删除范围）。
3. **`docs/IMPL_PLAN_VERSION_B_2026-09-13.md`** —— §8.8（死入口清理定案 + "不得误删"清单）、§4（批次）。
4. **`docs/DECISIONS.md`** —— ⚠️ **注意：该文件是按主题分节的"决策日志"，不含 D-10 / D-20 编号**（2026-09-15 实测确认）。这两项的裁定口径请查 `CONFLICT_REVIEW_SESSION_2026-09-11.md` 的**待议总表**（D-10 / D-20 行）+ `IMPL_PLAN` §8.8。

> ⚠️ 文档中的**行号仅作定位参考**（可能已漂移），**一律以符号名重新定位**。

## 四、第二步：先复述，等确认（**关键 —— 此时不要改任何代码**）

读完材料后，先输出以下内容，然后**停下来等用户确认**：

1. **理解**：这 3 项分别要删什么、为什么可以安全删除（每项一句话）
2. **改动清单**：逐个文件列出 —— 文件路径 + 具体改动点（方法名 / 枚举值 / 整文件删除）
3. **自行复核"死代码"结论**：文档称这些符号"无 caller"，但那是几天前的实测。
   **你必须自己 grep 全仓复核每个待删符号确实无引用**，并报告复核结果。
   ⇒ 若发现某个"死"符号其实**有引用**，**立即停止并报告，不要删**。
4. **风险点**：你识别到的"可能误删"项
5. **你将要运行的验证命令**

**等用户明确确认后，才进入实施。**

## 五、第三步：实施

- 严格按**确认后的清单**执行，不顺手改任何清单外内容。
- 删除时同步清理：import、注释、KDoc、相关常量，以及**测试中对已删符号的引用**。
- 若某个待删符号被测试引用 ⇒ 说明该测试也在测死代码，一并处理，并在报告中说明。
- **D-20 额外注意**（文档已写明）：删除后需确认 `onBillCreated` 仍由 `sharedJsEventEmitter` 提供；全仓 grep 确认 `ACTION_BILL_CREATED` 无残留。

## 六、第四步：验证（必须做，不得跳过）

```bash
cd D:\JotBill\jotbill-app\android

# ① 编译 —— 必须通过
./gradlew.bat :app:compileDebugKotlin

# ② 单元测试 —— 必须全绿
./gradlew.bat :app:testDebugUnitTest
```

> ⚠️ **必须用 `gradlew.bat`，不能用 `./gradlew`** —— 本机 Git Bash 缺 `uname`/`xargs`，bash 版脚本会直接失败。
> Android SDK 已由 `android/local.properties` 配好，无需设 `ANDROID_HOME`。增量构建约 2 分钟。

把这两条命令的**实际输出**贴进报告（含 `BUILD SUCCESSFUL`/`FAILED` 与测试统计）。**不得只写"测试通过"而不贴输出。**

## 七、第五步：报告

```
### 1. 改动清单
| 文件 | 改动 | 说明 |

### 2. 死代码复核结果
（每个待删符号：全仓 grep 命令 + 结果 + 是否确认无引用）

### 3. 验证结果
（两条命令的实际输出 + 测试通过数）

### 4. 遗留与存疑
（未做的事、拿不准的地方、发现的额外问题）

### 5. 建议的 commit message
```

## 八、硬性纪律（违反即返工）

1. **行号会漂，用符号名定位** —— 不要照抄文档行号去改。
2. **凡改「常量 / 键 / 枚举」类，先全仓 grep 找所有生成点** —— 本项目已多次出现"改一处漏一处"。
3. **文档标注"不动"的，绝不顺手改** —— 例：`canEstablishUserRetrySession`、`hasAutomaticCandidate` 有真机故障背书，文档明示不动。
4. **改完先不提交、不推送** —— 等用户 review 确认后再提交（除非用户明确要求）。
5. **推断不等于事实** —— 文档没写清的地方，**报告出来问**，不要自己拍板。

---

## 附：上一版复述报告的 6 个待裁定项 —— 裁定结果（2026-09-15）

> 实现 AI 在第一批复述中提出 6 项待裁定。以下为裁定，**已全部纳入本文件正文**。

| # | 问题 | 裁定 |
|---|---|---|
| **Q1** | D-11 会移除**当前活的**提交订单触发点（`Service:934-936`），中间态派发路径减少 —— 接受吗？ | 🔴 **不接受在本批做**：D-11 **整体移至批 V-4**（与 `PAYMENT_RESULT` 接线同批）。<br>**你的判断正确且重要** —— 本提示词初版误把 D-11 归入"纯删除、零行为变化"，该设定**不成立**，已修正。 |
| **Q2** | `submitActionTriggeredKey`（字段 + 10 处重置点）是否连带删除？ | ✅ **是**，但**随 D-11 一并移至批 V-4**（它属 D-11 链路的死状态，不是本批对象）。 |
| **Q3** | 测试适配策略？ | ✅ **同意你的分级方案**：`CheckoutSnapshotBufferTest` 17 处 → `PAYMENT_APP_HANDOFF`（buffer 仅区分 `USER_RETRY`，等价）；`CheckoutPurchaseIntentCoordinatorGateTest` 18 处 → `USER_RETRY`。<br>⚠️ 但整组**随 D-11 移至批 V-4**；其中 2 个无法等价替换的用例（`budgetExhaustedAfterMaxSuccessfulConsumesPerSession`、`nonHandoffIntentKeepsRequestedFingerprintUnchanged`）届时用 `PAYMENT_RESULT` 重写。 |
| **Q4** | `requestPurchaseIntent` 删完 4 个入口后变零 caller —— 删还是留？ | ✅ **保留**（你的判断正确）。它是通用入口，批 V-4 接入 `PAYMENT_RESULT` 时要复用；删除属扩大范围。 |
| **Q5** | `PaymentNotificationModule` 的 `LifecycleEventListener` 与 3 个空回调 —— 留还是摘？ | ✅ **保留**（你的判断正确）。摘除会动 `addLifecycleEventListener` 注册，属行为面改动；本批保持"纯删除"。 |
| **Q6** | `res/layout/payment_overlay.xml` 成孤儿资源 —— 本批删吗？ | ✅ **本批不删**（你的判断正确），但**登记为待清理项**（已记入 `IMPL_PLAN` §8），避免遗忘。 |

### 你在复核中额外发现、且已被采纳的事项

| 发现 | 处置 |
|---|---|
| **`DECISIONS.md` 无 D-10 / D-11 / D-20 编号** | ✅ **属实**（已独立复核：该文件是按主题分节的决策日志）⇒ 本文件"先读材料"第 4 条已改为指向 `CONFLICT_REVIEW` 待议总表 |
| D-11 会改变运行时行为 | ✅ **属实**（已独立复核 `Service:934-936`）⇒ 触发本批范围收缩 |
| `submitActionTriggeredKey` 10 处连带 | ✅ 属实，已纳入 D-11 的 V-4 范围 |
| `requestPurchaseIntent` 将变零 caller | ✅ 属实，裁定保留 |
| GateTest 2 个用例无法等价替换 | ✅ 属实，随 D-11 移至 V-4 后用 `PAYMENT_RESULT` 重写 |

### 工作方式评价（记录备查）

本回复述报告的质量**高于预期**：① 对每个"死代码"结论都做了**全仓 grep 复核**，未盲信文档；② 主动发现 D-11 的行为影响并**拒绝自行拍板**（正确做法）；③ 对 D-20 的安全前提（`onBillCreated` 删除后仍可达前端）做了**独立验证而非引用文档**；④ 6 个问题都给了**带依据的倾向**而非空问。**此工作方式应作为后续批次的质量基准。**
