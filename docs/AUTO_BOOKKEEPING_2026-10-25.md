> **状态头**：正典·必读 | 最后核实 2026-11-04 | 冲突时以 jotbill-app/android 代码为准（导航见 docs/README.md）
# 规则式自动记账（mipay 逆向移植）—— 实施记录

> 日期：2026-10-25　分支：`feat/auto-bookkeeping`（基于 `feat/lean-version` @ 3571675）
> 逆向依据：`D:\opencodeProject\mipay-ai-bookkeeping\`（设计方案 / mocked_rule.json / ce.b、ce.p1 反编译源）

## 1. 已落地（三个提交）

| 提交 | 内容 |
|---|---|
| 9875754 | 规则引擎核心库：PageRule 模型、PageNodeDumper（mipay dump 格式逐行复刻）、PageRuleRegistry（18 key/27 条内置规则资产）、BookkeepingChannels（总开关+渠道白名单） |
| b5d0e43 | 事件引擎（ce.b 状态机移植）+ AutoBookkeeper（落账）+ 通知/撤销 + CheckoutAccessibilityService 接入 |
| (本次) | 设置页（总开关/渠道开关/规则规模）+ RN 桥接 AutoBookkeepingModule |

**对应里程碑**：M1（本地闭环）、M3 前半（二次确认，随引擎落地）、M4（微信/云闪付规则随资产启用）、M6 规则资产（短信通道默认关）一次完成。未做：M3 后半（服务端 /rules 下发与 /records 去重）、~~M5（图片 OCR）~~ ⚠️ 2026-11-04 加注：已砍除，详见本文 §4 第 3 条加注。

## 2. 架构与 mipay 的对照

| mipay（逆向） | JotBill 落点 | 差异说明 |
|---|---|---|
| `ce.b` AutoBookKeeping 状态机 | `bookkeeping/AutoBookkeepingEngine.kt` | 忠实移植：200ms 内容变化节流、窗口去重（mWindowId/mReportedWindowId）、页面/窗口/通知三流分流、pending_confirm 5 分钟、matchMode=1 语义。EventSnapshot 投影使引擎纯 JVM 可测 |
| `ce.p1.b` 规则 schema | `bookkeeping/PageRule.kt` | 1:1（singleField 多候选、extractFieldsMapper 三级归一化、locateConfirm 继承定位字段）；全部正则 DOTALL 编译（原实现语义） |
| dump 格式（e0/f0） | `bookkeeping/PageNodeDumper.kt` | `<View text=xxx contentDesc=yyy />`，4 空格缩进，无引号转义；locateMaxTraverseCount 只限搜索阶段。**改动此处必须对照逆向源** |
| assets/mocked_rule.json | `assets/bookkeeping/bookkeeping_builtin_rules.json` | 原样移植 + 5 条收入规则 `jotbillType:"income"` 标注（小米把分类留给服务端，本地靠标注+detailType 字段定收支） |
| `ce.o0` 开关体系 | `BookkeepingChannels`（SP `auto_bookkeeping`） | ~~总开关默认开；渠道：支付宝/微信/云闪付开、短信关~~ ⚠️ **2026-11-04 核实（以代码为准）**：实际 **8 渠道** —— 支付宝/微信/云闪付/京东/美团/拼多多/抖音默认开、银行短信默认关（`BookkeepingChannels.CHANNELS`；jd/meituan/pdd/douyin 于 2026-10-25 差距复查补入）。同批新增 `ChannelInfo.kind` 采集档位（PAYMENT/SHOPPING_CORE/SHOPPING_RESULT_ONLY/NOTIFICATION_RULES_ONLY）与识别白名单 `RecognitionPackages` 的 CORE/RESULT_ONLY 降级分档（B2 `b8cf887`） |
| 上报→轮询→通知（ce.j1） | `AutoBookkeeper` 本地化 | 无服务端：抽取→本地入账→通知（带撤销）。去重 = createBillWithDedup level-1(orderId) + DuplicateGuard 相似度 + 引擎认领指纹 |
| FLAG_INCLUDE_NOT_IMPORTANT_VIEWS 运行时舞蹈（l0） | 不做 | 本服务该 flag 常开（lean 既有决定） |

## 3. JotBill 特有：认领互斥协议（拍板项①的落地）

两条管线并存：规则引擎（自动入账）与既有文本解析弹窗管线（待确认兜底）。

- 引擎挂在 `onAccessibilityEvent` **最顶端**，主线程同步完成 dump+抽取，成功即在认领注册表登记 `(包名, 金额, 15s)`；
- `scanPaymentResultCore` 在证据解析成功后查询认领：同包名**同金额**在窗内 → 弹窗管线让路（不同金额放行，避免误伤并发第二笔）；
- 引擎异步入账失败的兜底 = 自己转待确认（`recordOrphanPaymentSignal`，含同额合并语义）——认领后弹窗已让路，证据不能丢；
- 通知事件（typeNotificationStateChanged，新增）只属于规则引擎，事件入口早退隔离，既有管线零行为变化。

## 4. 待办 / 已知边界

1. **真机验收未做**（M1 验收：支付宝付 0.01 自动入账+通知+撤销）；
2. M3 后半：litenote-backend `/rules` 版本协商、`/records` 指纹去重（`PageRuleRegistry` 已预留覆盖点）；
3. ~~M5：图片/截图记账（入口 + OCR 服务端选型待拍板）~~；账单文件导入 lean 已有； ⚠️ 2026-11-04 加注：M5 已随截图识别链路（`4c62779`）整体砍除，不再是待做项，若重启需另行拍板。
4. 短信通道翻开即用（资产已含招行两条 + 通用银行短信 singleField 提取器），建议真机验证招行通知后再默认开；
5. 微信平台对抗是常态（mipay 教训 §2.6）：单通道失效 → 弹窗/待确认降级路径已就位，属设计内行为。
