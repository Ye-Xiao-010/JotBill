> **状态头**：过程记录 | 最后核实 2026-11-04 | 冲突时以 jotbill-app/android 代码为准（导航见 docs/README.md）
# 接力提示词：归属判定失效 → 待确认重复（2026-09-29）

> **状态**：[已失效]
>
> 🗑 **该提示词的任务已于 2026-09-29 当天完成，勿再使用。**
> 它写的是「本轮任务：只做 ① —— **取证，不是修**」，而取证结论已落成
> 裁定 **D-25**（归属判定单候选放行 + 孤儿待确认跨桶去重）并已实现。
> 要了解当时的排查思路与**已作废的结论**，读
> [`FIX_TASK_2026-09-29_attribution_pending_dup.md`](./FIX_TASK_2026-09-29_attribution_pending_dup.md) §二；
> 要看当天全貌，读 [`worklog/2026-09-29.md`](./worklog/2026-09-29.md)。
>
> 本文件保留**取证价值**：它记录了"当时还不知道要取证什么"的状态，
> 与任务书 §二.1 的结论对照，能看出排查是怎么收敛的。

---

> 用法：~~复制下面代码块的内容，粘进**新对话**的第一条消息。~~
> ~~本文件只做存档，实际发送用代码块原文。~~

---

```text
你在 D:\JotBill（JotBill，Android + React Native 记账 App）工作，当前分支 fix/attribution-and-pending-dup。

【第 0 步：按顺序读完再动手】
1. CLAUDE.md —— 项目纪律（5 步流程、先复述再动手、文档「加注不覆盖」）
2. docs/00-现状正本.md —— 唯一入口（方向已定、进度、待办）
3. docs/FIX_TASK_2026-09-29_attribution_pending_dup.md —— 本次任务书，含真机实证数据、
   诊断设施用法、以及**一条已被复核推翻、必须避开的错误结论**

【背景（一句话）】
真机回归跑通后挖出：归属判定恒 0 命中 ⇒ 同一笔支付在待确认中心产生多条记录。
实测 2026-09-29 晚：用户支付 2 笔，待确认中心出现 5 条。
直接原因是 CheckoutPurchaseIntentCoordinator.kt:1042 ——
`val pageAmount = context.finalAmountMinor ?: return@filter false`，该字段恒为 null。

【本轮任务：只做 ① —— 取证，不是修】
在 AccessibilityPageSummaryCollector.kt:347 附近（`resolveFinalAmountMinor()` 调用处）加一条 WARN，
把下列值落进已有的 jotbill-diag.log：
  - labeledAmounts        —— 去重后的带标签金额集合
  - amounts.distinct()    —— 无标签兜底候选
  - finalAmountMinor      —— 最终结果
  - hasFinalAmountSignal 的判定依据（它走**另一条通道**：:494-508，已有「拼多多无标签金额」兜底，
    而 finalAmountMinor 的 accumulator 没有对应兜底 —— 两者不同源，很可能就是缺口）
建议把这段逻辑抽成纯函数，便于补单测。

【⚠️ 绝对不要做的事】
1. 不要改 PageAmountExtractor.resolveFinalAmountMinor()。
   任务书早期版本里的「labeledAmounts.distinct()」**已作废**：该字段是
   LinkedHashSet<Long>（PageAmountExtractor.kt:140），本身就天然去重，
   那个改动是**空操作**，改完会得到「代码改了 + 测试全绿 + 真机依旧 5 条待确认」的假绿。
   现有用例 PageAmountExtractorTest.repeatedSameValueIsNotAmbiguous（:85）已钉死该行为。
2. 不要现在就决定怎么修。两条假设的修法性质完全不同：
   - 假设 A（标签与金额分处两个 TextView，无法在同一节点内配对 ⇒ labeledAmounts 恒空
     ⇒ 落到 amounts.distinct().singleOrNull() 兜底 ⇒ 页面有 ≥2 个不同金额即 null）
     这是**实现缺口**，可修，但须先补单测钉住负例。
   - 假设 B（两个带标签金额确实不同，如 订单金额¥15.00 + 实付¥11.76 ⇒ null）
     这是**裁定 (A) 的正确行为**（PageAmountExtractor.kt:129-133 KDoc 已记载），
     要提高命中率**必须走新裁定流程**：CONFLICT_REVIEW_SESSION → DECISIONS.md，
     **不得**靠 finalAmountTerms 词表顺序表达优先级。
   取证结果出来之前不要预设方向。
3. 不要推远程、不要合并 main。
4. 不要动 qa/device/ 等原始证据目录（只增不改）。

【验证步骤】
编译 → 装包 → 起 Metro（debug 走 RN，没起就是红黑屏）→ 确认无障碍仍在启用列表
→ 付一笔 → pull jotbill-diag.log → 看 labeledAmounts 是空还是有值 → 据此判定 A 还是 B。
具体命令见任务书 §四；已踩过的坑见任务书 §五（含：force-stop 会停掉无障碍、
PowerShell 控制台中文乱码不可用于判断、拉二进制必须用 cmd /c）。

【验收基线】
385 tests / 0 failures / 0 ignored（本轮只加日志，不应改变任何既有行为）。

【纪律】
- 动手前先复述：你要改哪个文件、哪几行、为什么。等用户确认后再改。
- 不确定的地方标注「推断」，**不要把推断写成裁定**。
- 本轮不提交，等确认后再提交。

【可以并行推进的（如果你想在取证期间做别的）】
任务 ②「孤儿落库加同金额+时间窗二次去重」与 ③「待确认过期/清理机制」不受取证阻塞。
但 ② 有个坑要先想清楚：直接按金额去重会与 D-6「同额两笔必须都保留」冲突，
安全做法是仅在 conflictReason = PAYMENT_RESULT_NO_MATCHING_SESSION 且金额相同、
时间差 < N 秒时合并，并保留两条 conflictReason（合并先例见 CheckoutPendingTimingTest）。
```
