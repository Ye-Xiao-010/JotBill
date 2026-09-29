# 按功能点全量代码审查(2026-09-06)
> **状态**：[快照·含未处置项]（2026-09-06 时点）—— 按功能全量评审的**静态快照**
>
> **⚠️⚠️ 读本文件前必读：「审计文档中无处置记录」≠「代码未修」**
>
> 2026-09-29 逐条回查代码后的实测结论（**曾被误读为 4 条未修，实际只有 1 条**）：
>
> | 条目 | 当时的判定 | 代码实测（2026-09-29） | 结论 |
> |---|---|---|---|
> | **P0-2** 导入合并目标可为退款账单 → 类型翻转 + 余额双重扣减 | 资损级 | ✅ **已修**：`BillDao.kt:130-131` 目标侧 `AND type != 'refund' AND original_transaction_id IS NULL`；`ImportEngine.kt:577` 行侧 `row.type != "refund"` 豁免。**双侧成对**，KDoc `:122-124` 逐字记录该缺陷与修法 | **已修，欠文档回填** |
> | **P1-10** 编辑退款/手续费账单类型被静默回退为"支出" | 结余偏移 2×金额 | ✅ **已修**：`CreateBillScreen.tsx:72` `editingTypeLocked`，保存用原类型 `:241`、选择器禁用 `:283`、提示 `:300` | **已修，欠文档回填** |
> | **P1-6** `file_hash` 唯一索引致文件永久无法重试 | 静默失效 | ✅ **已修**：`ImportBatchDao.kt:22`「删除 FAILED/CANCELLED/UNDONE 批次行，释放唯一索引」；`ImportEngine.kt:414` 对应判定 | **已修，欠文档回填** |
> | **P1-7** AI 预算 `costMinor` 恒 0 | 功能整体失效 | ❌ **仍未修**：`AiChatModule.kt:953` 与 `CheckoutAiVisionClient.kt:1783` 均硬编码 `costMinor = 0`；全库无发送前预算闸门 | **仍开放** |
>
> **⇒ 本文件真正仍活着的只有 P1-7**（功能缺失，非资损）。其余标「已修」处请以 git 为准，勿重复排期。
>
> **⚠️ 编号警告**：本文件的 P0-1 / P1-1 / P2-1 与 `ISSUE_REGISTER_2026-09-11.md` 的 P1~P22 **是两套完全无关的系统**
> 入口：`00-现状正本.md`

- 基线:分支 `roadmap/batch6-categories`(commit deca16d + 未提交批 6 改动)
- 方法:5 路并行只读审查(识别链路 / AI 模块 / 数据层+规则 / 导入+备份 / 前端功能面),每路通读源码;标注 **[亲核]** 的条目由主审逐行验证过,其余为审查代理给出且附有 file:line 证据。
- 定级:P0=错账/资损/数据丢失;P1=功能失效/静默丢数据/隐私;P2=边界错误/一致性;P3=轻微。
- 与 `docs/ROADMAP_FALLBACK_FIRST.md` 第十一节已记录问题不重复;核对结论见 §15。

---

## 1. 账户与余额引擎(转账/还款)

### P0-1【亲核】还款方向与全系统存储口径相反,每次还款放大欠款
- `data/LedgerRepository.kt:768-777`:
  ```kotlin
  // 负债账户正余额表示欠款,转入减欠款(还款/转到信用卡)
  val direction = if (target.kind == "liability") -1L else 1L
  ```
- 但全系统存储口径是**负数=欠款**,四处交叉印证:
  - `bridge/DataModule.kt:1119-1121` `normalizeBalance`:负债正数输入取负入库;
  - `data/dao/AccountDao.kt:36-45` `getNetWorth`:负债取 `ABS(current_balance_minor)`;
  - `src/screens/settings/AccountsScreen.tsx:171-173`:前端提交 `-Math.abs(amountMinor)`;
  - `backup/BackupManager.kt:472`:备份校验只允许负债初始余额为负。
- 后果:信用卡欠款 1000(库存 -1000),从零钱还款 400 → 目标 delta = 400×(-1) = -400 → 库存 -1400,**越还欠得越多**;删账单反向撤销同样错。日常刷卡(expense 挂信用卡)反而正确,所以只有"负债作为转账/还款目标"这一必经路径全错(`CreateBillScreen.tsx:193` 还款目标强制负债账户)。
- 同源复制:`importing/ImportEngine.kt:867`、`importing/ImportUndo.kt:231` 同中毒。
- 修复:统一"负数=欠款"口径,负债目标 direction 恒 `+1L`(即删掉 kind 分支),同步改三处注释;补还款双账户 Robolectric 集成测试(`LedgerRepositoryTest.kt` 现只测源侧)。

### P2-1【代理】updateBill 可改 orderId/sourceType 而不查重
- `bridge/DataModule.kt:244-271`:更新路径不执行 DuplicateGuard L1,可人为制造重复 `(sourceType, orderId)`,后续合并目标不确定。

---

## 2. 待确认核销(ReviewScreen + confirmPendingTransaction)

### P1-1【代理】退款"累计不得超过原交易金额"校验被确认路径绕过
- `LedgerRepository.kt:318-475` 全文无 `sumRefundsByOriginalId` 检查;该检查只在 `recognition/RefundLinker.kt:155-160` 自动入账路径存在。
- 绕过路径:① 两笔同原交易退款事件先后到达,首笔尚在待确认时第二笔自动入账(sum=0),再确认首笔 → 累计 2×;② 用户在确认面板改大金额(`DataModule.kt:948` 允许覆盖 amountMinor)。
- 修复:confirm 事务内对 `type=="refund" && originalTransactionId!=null` 重算累计上限。

### P2-2【代理】"支出型待确认改为退款并手动关联原交易"必然被拒
- `ReviewScreen.tsx:579-586` + `LedgerRepository.kt:337,347-349`:`isRefund` 取 pending **原类型**而非用户改后类型,`originalTransactionId != pending.sessionId` 即抛 "source binding is immutable"。与 08f43d5 修复的退款问题是同族。

### P2-3【代理】fee 型待确认的 originalBillId 确认时被静默丢弃
- `LedgerRepository.kt:336-342` 只对 `type=="refund"` 回填;`FeeExtractor.kt:171` 写入的手续费主交易关联在确认后丢失。修复:回填条件放宽为 `refund || fee`。

### P2-4【代理】transfer/repayment 待确认快捷确认必然报英文错
- `ReviewScreen.tsx:151-156` 未展开编辑即确认,`targetAccountId` 为空 → `LedgerRepository.kt:678-680` 抛英文异常直出 alert。

### P2-5【代理】确认入账不写 field_locks
- `BillEntity.kt:70` 默认 `"[]"`,`DataModule.kt:969-1001` 确认路径未传;用户核销时修正的字段不被锁定(当前"只补空字段"策略下暂无害,语义弱于手动编辑)。

### P3
- 版本冲突后列表不自动刷新(`ReviewScreen.tsx:89-93`);金额 `parseFloat` 宽容度过大(`ReviewScreen.tsx:702`);确认/忽略后系统通知不消除(`pending/PendingReminderNotifier.kt:101` 无 cancel 调用)。
- 健全:confirm 走事务内乐观锁 + 会话终态收口 + 墓碑审计;getPage 只返回 pending 状态,无残留;AI 金额 0 占位禁止确认。

---

## 3. 自动记账识别链路(无障碍/截图/AI 视觉)

### P1-2【亲核】支付结果页截图兜底对支付 App 任意前台页面无差别触发,屏幕内容周期性外发 AI
- 触发路径:`CheckoutAccessibilityService.kt:1348-1368` —— 前台为微信/支付宝的每次内容变化事件都会进入 `observePaymentResultIfApplicable`;节点树为空/判定非结果页时**直接**调 `maybeCapturePaymentResultScreenshot`。
- 门禁只有三道(:1581-1596):15 秒限频、90 秒成功证据冷却、前台包名是支付 App——**没有支付旅程上下文门槛**。用户平时刷微信聊天/朋友圈,AI 客户端已配置时每 15 秒截全屏(聊天记录、相册内容)编码发往外部模型。隐私泄露 + 持续 API 成本 + AI 误读产生垃圾待确认。
- 修复:兜底加"最近存在支付交接/结算会话"门槛(`handoffContext != null` 或时间窗),至少要求页面命中支付语义信号。

### P2-6【代理】API 24-29 触发兜底截图即进程崩溃
- `CheckoutAccessibilityService.kt:1608-1609` 直接调 API 30+ 的 `takeScreenshot`,`@Suppress("NewApi")` 且外层只 catch RuntimeException;minSdk=24(`android/build.gradle:4`)。NoSuchMethodError 是 Error 不会被捕获,叠加 P1-2 的 15 秒重试。修复:加 `SDK_INT >= R` 守卫。

### P2-7【代理】系统截图回调丢失(MIUI)无看门狗,手动重试永久卡死
- `CheckoutSnapshotCaptureCoordinator.kt:1738-1777` beginCapture 后无超时,pendingCapture 只靠系统回调清理;回调被静默丢弃后:(a) 同页手动截图永远 CAPTURE_IN_FLIGHT;(b) 支付结果页兜底(`paymentScreenshotFallbackInFlight`)永久停用。修复:8-10 秒看门狗 + 超时按 CANCELLED 收口。

### P2-8【代理】脱敏正则吞掉三位以上整数金额,PDD 类"无金额标签页"≥100 元订单系统性漏拍
- `AccessibilityPageSummaryCollector.kt:531-532` 把 `¥128.00` 中的 `128` 替换为 ` number `,而兜底金额信号要求 `currency+数字`(:422-427)→ `MISSING_FINAL_AMOUNT` 拒绝。方向是漏记(fail-conservative),但对百元以上订单是系统性失效。

### P2-9【代理】"包名+2分钟桶+金额"sourceEventKey 静默吞掉同额第二笔支付
- `PaymentResultParser.kt:206-210` 无订单号时,同包同额 2 分钟内第二笔支付与第一笔同键,60 秒去重 + 证据 2 分钟同额合并后**连待确认都不产生**。两次同价共享单车第二笔永久漏记。

### P2-10【代理】孤儿待确认与会话入账是同一笔支付的双重表示
- 通知回退(5 秒+1 秒复查)与无障碍慢写竞态时,同一支付产生"自动入账 Bill + 孤儿 pending"两份;自动入账只清会话 pending(`CheckoutPaymentMatcher.kt:1636-1639`),确认孤儿时 L1 按 `(sourceType, orderId)` 精确匹配必然未命中 → 重复入账。窗口窄,需真机验证竞态时序。

### P3
- `markPaymentResultEvent` HashMap 跨线程(:1768);PendingChangeHub 在事务提交前 emit,回滚出幽灵提醒(FeeExtractor.kt:177);ambiguous 孤儿 emit 的 pendingId 与实际落库 ID 不一致(matcher:531-572);AI 失败占位重开绕过状态机(matcher:685-690)。
- 健全:SnapshotBuffer 全 `@Synchronized`、字节 copy-on-store/用后清零;熔断/自愈判据与 10 秒节流重建闭环;AI handoff 三标志无重复释放;RefundLinker/TransferClassifier/FeeExtractor 事务性与幂等键健全;悬浮按钮模式切换状态一致。

---

## 4. 支付通知监听

### P1-3【代理】onListenerDisconnected 无自愈,四类证据入口静默停摆
- `notification/PaymentNotificationService.kt:78-82` 只打日志;`requestRebind()` 全工程零调用。国产 ROM 杀后台后监听不重连,此后直接扫码付(无结算会话)的支付整笔漏记且无痕迹。修复:断开时 `requestRebind()` + 面包屑。

---

## 5. 账单导入

### P1-4【亲核】微信官方账单(XLSX)完全无法导入
- `importing/WechatBillAdapter.kt:23` `supportedFormats = setOf(FORMAT_CSV)`,parse 直接喂 CSV 读取器;全仓库无微信 XLSX 适配。根目录真实样本(XLSX、表头第 18 行)验证:一行都进不来,抛 UNSUPPORTED_FORMAT。

### P1-5【代理】支付宝 2026 官方导出格式表头无法识别
- `AlipayBillAdapter.kt:127-133` 签名要求 `交易号`+`付款时间|交易创建时间`;2026 年电子客户回单表头为 `交易时间/交易订单号/商家订单号/收付款方式`(第 24 行),且扫描上限 `MAX_HEADER_SCAN_LINES=20`(:340)不够。271 笔全部导入失败。修复:登记 alipay-csv-v2 表头族 + 扫描上限提到 40。

### P1-6【代理】file_hash 唯一索引使失败/取消/撤销后的同一文件永久无法重试
- `ImportBatchEntity.kt:26`(unique)+ `ImportEngine.kt:144-155`(失败保留批次行)+ `ImportBatchDao` 无删除方法。一次瞬时失败后该文件被 UNIQUE 约束永久阻断,UI 只报"导入执行失败"。

### P0-2(疑似,需构造数据验证)【代理】导入合并目标可为退款账单 → 类型翻转 + 余额双重扣减
- `ImportEngine.kt:530-535` `findByOrderIdForImportMerge` 不过滤 type(`BillDao.kt:117-133`);RefundLinker 创建的退款账单携带与原单相同 order_id(`RefundLinker.kt:191,211`)。命中时 `mergeImported` 把 refund 覆写为 expense,先反冲再扣一次,余额净错 2×金额。修复:merge 目标排除 `type='refund'`(建议同时排除 `original_transaction_id` 非空)。

### P2
- 备份不覆盖 import_batches/pending/evidence/candidates/checkout_sessions/chat(11/18 张表,`BackupManager.kt:734-1002`),恢复后:旧待确认可确认入账与恢复账单重复;audit 里的导入审计失去批次锚点,撤销链断裂;残留 file_hash 继续占坑。field_locks/import_record_id 本身往返已确认到位(ff52216 修复成立)。
- 失败路径半批清理失败被静默吞(`ImportEngine.kt:144,159` runCatching)——用户视角"失败"但半批钱已入账。
- 整批撤销无条件删 pending,用户补全的账户/分类随之丢失(`ImportUndo.kt:92-99`)。
- 微信 XLSX 时间列为 Excel 序列数,`WechatBillAdapter.kt:218-225` 无序列换算(补 XLSX 时必踩;支付宝侧 :266-281 有 parseExcelSerial 可抄)。
- 0.00 行被计为解析错误;类型关键词优先于收/支方向 → "转账"对方 + 明确收支的行被误判 transfer,账户未双匹配时余额不动(账单在、资金流丢)。

### P3
- CsvTableReader `previousWasCr` 边缘混行尾 bug(官方样本未触发);inspect/execute 无强互斥;ZIP 解压无大小限制(zip 炸弹);解析错误不计入 failedCount。
- 健全:CSV 状态机/GBK 探测与真实样本吻合;分批事务写入、审计驱动回滚、L3 同批去重、退款 ¥10,000 阈值;恢复单事务原子、FK 顺序正确、clearData `clearAllTables()` 无遗漏;ImportUndo 撤销范围精确。

---

## 6. AI 助手(对话/语音/视觉/服务商)

### P1-7【亲核】AI 用量预算功能整体失效:costMinor 恒为 0
- `ai/AiChatModule.kt:858` 与 `CheckoutAiVisionClient` 记录处 `costMinor = 0` 硬编码,无 token 计价;月度消费 `SUM(cost_minor)` 恒 0;`getAiBudget/setAiBudget/getAiCostRecords` 无任何 UI 消费方,发送前无预算闸门。修复:建模型单价表折算,或至少按请求数设闸。

### P2(AI)
- **取消轮间空窗**:`StreamingSseClient.kt:61-62` onCompleted 前 remove activeCall,多轮间隙 cancel 返回 false 且无记忆标志 → 用户点停止后第 2 轮照常发起并计费。
- **工具前文本 UI 丢失**:JS `onToolResult` 清空 accumulatedText(`AIChatScreen.tsx:1557-1558`),而原生跨轮累积拼接成一条入库 → UI 与 DB 不一致。
- **断流当成功**:未见 `[DONE]` 也走 onCompleted;200 响应体里的 `{"error":...}` 被静默忽略(`StreamingSseClient.kt:46-62,93`)→ 空气泡、截断的 tool_calls 参数误导模型。
- **多轮中途出错丢前轮文本**:`AiChatModule.kt:588-598` onError 不落库 accumulatedText。
- **会话切换状态污染**:切换/新建会话不 stop 当前流、不清 pendingBills(`AIChatScreen.tsx:1151-1155`)→ 旧会话账单卡片混入新会话,误点保存会写错账本。
- **assistant content:NULL + 每轮 usage 丢失**(`AiChatModule.kt:649-654,538-541`);未带 `stream_options.include_usage`。
- **endpoint 不补 /v1**:`AiEndpointResolver.kt:51-61`,用户填根域必 404,而 testAiProvider 只 GET 根路径 404 也算"可达"(`AiModule.kt:156-168`)→ 测试成功、聊天必挂。
- **finishStream 无 catch**(`AiChatModule.kt:801-830`):DB/Keystore 异常沿 SupervisorJob 上抛可崩溃。
- **语音转写用聊天模型**(`AiChatModule.kt:154-184`):`input_audio` 喂给 qwen-plus/glm-4v-flash 等预设模型,大概率全部 ASR 失败。

### P3(AI)
- KeyStore 无迁移路径 + `getOrCreateKey` 并发竞态;`createBillsResult` 非法 type/日期静默兜底;`tool_result` JSON 失败后流未终止(`services/ai.ts:166-175`);onDone 空消息清理为死代码;types/ai.ts 的 claude/openai/deepseek 预设为死配置;LIKE 通配符未转义;callTimeout 2 分钟硬切。
- 健全:queryBillsTool 无注入面(type 白名单、limit 1..20、绑定参数、CASE 参数化排序);dateBoundary 时区正确;密钥 GCM+随机 IV、HTTPS 强制、无 Key 日志、JS 只见掩码;视觉客户端重试语义与 USER_RETRY 对齐、字节清零。

---

## 7. 规则引擎

- **总体健全**:六种条件全部 `ignoreCase = true`(`RuleEngine.kt:249-265`,9d0e015 修复确认);`(priority, createdAt)` 仲裁、set_* 先到先得、ignore/send_to_review 短路、`sanitizePatch` 防炸事务;FieldPriorityResolver 7 级 rank 与锁定保护正确。
- **P2-11【代理】L1 去重不比较类型**:`rules/DuplicateGuard.kt:45-54` 豁免只看入单类型,查询不限 type → 同 `(sourceType, orderId)` 的 expense 入单可命中既有 refund 账单走合并,新账单金额静默丢弃。
- **P3**:RuleDao `ORDER BY priority DESC` 与引擎 ASC 语义相反(展示误导);`applyRulesToExisting` 是 NOT_IMPLEMENTED 桩(`DataModule.kt:856-861`)——规则页若露出"应用到存量"入口会直接报错,属未完成功能而非缺陷。

---

## 8. 统计 / 报表 / 首页

### P1-8【亲核】Dashboard"本月"区间 UTC 偏移,整天错位
- `src/hooks/useDashboard.ts:27-39`:`startOfMonth.toISOString()` 在 UTC+8 恒为上月最后一天(本地 0 点=UTC 前一日 16 点);`today.toISOString()` 早 8 点前为昨日。区间实际为 [上月末, 昨日/今日]:每天早上记的账不进首页,且整天多算上月末一天。与统计页(本地日期)口径矛盾。修复:用 `utils/date.ts` 本地格式化。

### P2
- 全部账单页月合计把 transfer/repayment 计为负向支出(`AllBillsScreen.tsx:130-136,305-308`),与原生统计(排除转账)矛盾,一笔 5000 转账月合计红显 -5000。
### P3
- StatisticsScreen 支出占比收入 0 时爆表(:176-178);TrendAnalysis 周视图 N+1(7 次串行请求);资产趋势反推忽略余额校准;日明细 100 条/待确认 50 条静默截断;Dashboard 近期交易 refund 显示负向;useStatistics 上一周期 `new Date("YYYY-MM-DD")` UTC 解析(中国时区无影响)。
- **健全**:统计口径单一来源在原生 SQL(`BillDao.kt:241-358`:income+refund→收入、expense+fee→支出、transfer/repayment/adjustment 不计),摘要/分类/日/月趋势四处自洽;前端只做分→元换算不重算;分类统计 `COALESCE(parent)` 归父级与预算口径吻合。

---

## 9. 预算 / 财务目标

- **P2-12【代理】通知/悬浮窗自动入账不触发预算超支提醒**:`PaymentOverlayManager.kt:381`、`TransferClassifier.kt:177`、`RefundLinker.kt:197` 直写账库无 `checkAndNotify`;触发点只有手动路径(DataModule.kt:100)与导入(BackupModule.kt:557,830e987 修复确认在)。与 6efdad9 承诺的"补齐提醒"意图不一致。建议把提醒下沉到 LedgerRepository 写路径末尾。
- **P2**:Budget/Goal mutations 无 onError(`BudgetScreen.tsx:72-94`、`FinancialGoalScreen.tsx:84-106`),原生 reject 时静默无反馈。
- **P3**:提醒档位去重键"最高已提醒档位",同周期调低阈值后不再提醒;SharedPreferences 无锁并发可重复通知。
- **健全**:超支口径与 `getBudgetProgress` 同源同参(sumExpenses、含子分类);目标 currentAmount 原生保留、双向 clamp。

---

## 10. 分类体系(含批 6 未提交改动)

- **未提交改动总体方向正确【亲核】**:`nextChildSortOrder/nextTopLevelSortOrder` 排序重算、DefaultDataInitializer 部分播种幂等修复(`maxOf(100, maxExistingSort+1)`)、CategoryDao `is_default DESC` 排序、前端 `sortChildren` 与后端口径一致;addCategory 父级校验(存在/同类型/两级)齐全;deleteCategory 三重拒绝且在事务内,无级联孤儿;CreateBillScreen 删除 handleDelete 无悬空引用(删除入口在 BillDetailScreen);子分类面板交互(选子不收起、再点顶级回退顶级)逻辑自洽。
- **P2-13【代理】updateCategory 改类型不校验**:`DataModule.kt:549-553` — 有子分类或已挂账单的分类可改类型,造成两级类型不一致/账单分类在两类选择器中消失。修复:有 children 或有账单时拒绝改类型。
- **P3**:播种用 ABORT 冲突策略(预设 ID 冲突=启动硬失败,建议 IGNORE);`nextChildSortOrder` 读-算-写无事务,并发可撞 sortOrder;子分类无子时也不能升顶级的报错文案与逻辑不符。

---

## 11. 热更新 / 应用更新

### P1-9【亲核】热更新崩溃计数永不复位,热包第 3 次冷启动必被自动删除
- `MainApplication.kt:52-72`:每次启动对已验证热包 `crash_count+1`,≥2 删热包回退内置。复位唯一入口 `markBundleLoaded()` 挂在 `useHotUpdate`(`useHotUpdate.ts:38-41`),而 **useHotUpdate 全工程无人挂载**(App.tsx 只用 useAppStateManager,grep 验证)。下载当次复位(`services/hotUpdate/index.ts:208-210`)只覆盖一次。结论:任何成功热更在第 3 次冷启动被静默回滚,热更新功能整体失效;MIUI 杀进程也消耗计数。修复:App 入口启动即调 `markBundleLoaded()`。
- **P2**:APK/热包下载整体 `arrayBuffer` 进 JS 堆 + base64 放大 33%(`update/client.ts:156-176`),低端机 OOM 风险(疑似);`verifyApk` 读包信息失败时跳过包名校验(`InstallApkModule.kt:78-91`);版本比较依赖 `BUILD_NUMBER` 缺省 '1'(`env.ts:42`)。
- **健全**:applyPreparedBundle marker 状态机 + SHA-256 校验 + `recoverInterruptedSwap` 中断恢复;下载 URL 安全层(HTTPS 强制、拒凭据/重定向/路径穿越)。

---

## 12. 前端事件链(修正代理结论)

- **[亲核] 修正**:前端代理"原生事件全部无订阅"不成立——`App.tsx:35-58` 已订阅 `onBillCreated`(失效 bills 缓存)与 `addPendingCreatedListener`(失效 recognition 缓存)。
- 真正缺口(**P2-14**):`onPendingChanged`(确认/忽略后,`DataModule.kt:895,1010`)、`onBillUpdated/onBillDeleted`(:278,293)、`onBudgetChanged`(:654,679,688)、`onBudgetAlert` 无任何订阅 → 停在 Review 页时他处核销不刷新、预算告警只剩系统通知通道。修复:根组件挂事件→invalidateQueries 映射桥。

---

## 13. 数据层基础设施

- **P2-15【代理】CancellationException 时 Promise 永久悬挂**:`DataModule.kt:1171-1187` rethrow 不 reject;非 Exception 的 Throwable 同样既不 reject 也不留痕。
- **P3**:建库即全量备份 + runBlocking(`JotBillDatabaseProvider.kt:32-53`,首访来自无障碍/悬浮窗线程有 ANR 风险,疑似);`restoreOnMigrationFailure` 吞异常无日志;keyword LIKE 未转义。
- **健全**:MIGRATION 1→12 链条连续无断档、无 fallbackToDestructiveMigration;守卫备份含 wal/shm;Room 2.8.3 withTransaction 嵌套语义经反编译确认;checked arithmetic 全覆盖;DatabaseConverters 为死代码无风险。

---

## 14. 手动记账(CUD)

- **P1-10【代理】编辑退款/手续费账单时类型被静默回退为"支出"**:`CreateBillScreen.tsx:45-51,229-247` 表单只支持 4 类,编辑 refund/fee 保存即翻转类型,结余双向偏移 2×金额。修复:编辑态锁定类型。
- P3:金额 parseFloat 宽容(`CreateBillScreen.tsx:198,233`);NaN 穿透前端校验由原生报英文错。

---

## 15. 与路线图第十一节核对

- `REFUND_PENDING_THRESHOLD_MINOR=10_000_00L` 在;退款确认回填 originalBillId 在(LedgerRepository.kt:336-345);导入触发预算提醒在(BackupModule.kt:557);规则忽略大小写在——文档声称的修复**全部成立**。
- `advanceStatus` 非法迁移静默 no-op(matcher:1382-1384)与 `CheckoutJourney` 未实例化**仍未修**,与文档"批 4 待修"定位一致,非新问题。

---

## 修复优先级建议

| # | 问题 | 级别 | 功能点 |
|---|------|------|--------|
| 1 | 还款/转账负债目标方向错账(三处同源) | P0 必现 | 余额引擎 |
| 2 | 微信 XLSX 不可导 + 支付宝 2026 表头不可识别 | P1 | 导入 |
| 3 | 截图兜底无差别触发(隐私)+ API24-29 崩溃 | P1 | 识别链路 |
| 4 | 热更新崩溃计数不复位,热包 3 次启动即删 | P1 | 热更新 |
| 5 | file_hash 阻断重试 + 导入合并目标排除 refund | P1/P0疑似 | 导入 |
| 6 | onListenerDisconnected requestRebind | P1 | 通知监听 |
| 7 | confirm 路径补退款累计上限校验 | P1 | 待确认核销 |
| 8 | Dashboard UTC 时区 | P1 | 统计 |
| 9 | costMinor 计价(或移除死预算 UI) | P1 | AI |
| 10 | 编辑 refund/fee 类型翻转 | P1 | 手动记账 |
| 11 | 截图看门狗(MIUI 回调丢失) | P2 | 识别链路 |
| 12 | markBundleLoaded 挂载 + 事件订阅桥(onPendingChanged/onBudgetAlert) | P2 | 前端 |
