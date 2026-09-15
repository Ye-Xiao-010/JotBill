# docs 目录索引（2026-09-13 整理）

> 本文件是 `docs/` 全域的**总索引**：每份文档是什么、什么作用、当前状态。各子文件夹内另有**空的 txt 标记文件**（文件名即该文件夹内容的类型与作用）。
>
> **一句话导览**：主设计规格 = `NEW_PROJECT_KICKOFF_V2.md`（0.3.3）· 裁定正典 = `DECISIONS.md` · 实施方案 = `IMPL_PLAN_VERSION_B_2026-09-13.md`（批 V-1~V-5）· 讨论记录主线 = `CONFLICT_REVIEW_SESSION_2026-09-11.md` · 流程图 = `diagrams/version-b-business-flows-2026-09-13.md`。

---

## 一、治理与长期文档（随项目演进持续修订）

| 文档 | 作用 | 状态 |
|---|---|---|
| `ROADMAP_FALLBACK_FIRST.md` | 落袋优先路线图（批 0~6 + 插队位 + 审计修正）。⚠️ 批 4=journey 已于 2026-09-13 裁决**重新定位**：版本 B 批 V-1~V-5 先行，分支冻结（见第六节裁决注） | 活文档 |
| `NEW_PROJECT_KICKOFF_V2.md` | **主设计规格**（0.3.3）：识别/截图/交接/匹配/入账全链设计；版本号变更摘要集中在文首 | 活文档 |
| `DECISIONS.md` | **裁定正典**：产品与技术决策速查表；D 系列裁定的正典落点（含 R1 通道分列、D-19 容差、D-22/D-24、TTL 三步修订链） | 活文档 |
| `MIGRATION_MAP.md` | 阶段 ↔ 文件边界迁移映射（净资产口径等已统一） | 稳定 |
| `DEVELOPMENT.md` | 开发环境与构建指南 | 稳定 |
| `TESTING.md` | 测试指南 | 稳定 |

## 二、设计核对链条（2026-09-03 ~ 09-13，按时间顺序阅读）

> 这条链的产出：**版本 B 全套裁定（D-1~D-24 + R1~R6）与实施方案**。不确定信息的口径来源 = 第 6 份（9.11 讨论记录）。

| 序 | 文档 | 作用 |
|---|---|---|
| 1 | `PROJECT_PROGRESS_REVIEW_2026-09-03.md` | 进度盘点：两套规划框架执行情况、优先级漂移警告（注：其"批 4 未编码"结论有误，journey 分支代码存在） |
| 2 | `CODE_REVIEW_BY_FEATURE_2026-09-06.md` | 按功能全量代码评审（P0~P3）。**P2-8（脱敏吞三位数金额）与 P2-9（去重键吞同额第二笔）与版本 B 直接相关**，均已并入批 V 范围 |
| 3 | `qa/payment-notification-effectiveness-2026-09-10.md` | 通知链路真机零产出诊断（→ D-3 裁定 v1 停用通知） |
| 4 | `CONFLICTS_2026-09-10.md` | 23 项冲突清单（文档↔代码↔分支，三层取证；A5 锚点漂移表） |
| 5 | `SCHEME_INVENTORY_2026-09-10.md` | 识别·截图·入账链路**方案全景**（§6.9 多订单推演是改造 E 的依据） |
| 6 | `CONFLICT_REVIEW_SESSION_2026-09-11.md` | ★**讨论式核对记录主线**（G1~G8、裁定 D-1~D-24、用户原话逐条在案；含内部一致性审计与两轮追加） |
| 7 | `RECONCILIATION_2026-09-11.md` | 三方对账：代码实现 ↔ 文档声明 ↔ 新发现（G1~G5/M1~M9/O1~O4/N1~N9） |
| 8 | `ISSUE_REGISTER_2026-09-11.md` | 问题总表 P1~P22（按处置优先级；顶部有后续裁定对照表） |
| 9 | `DESIGN_REVIEW_2026-09-13.md` | 设计自查评审：第一轮 R1~R6 + **第二轮核对（矛盾 1~3 + 观察 4~5，全部已裁决落地）** |
| 10 | `IMPL_PLAN_VERSION_B_2026-09-13.md` | ★**版本 B 实施方案**：改造 A~E 规格、批 V-1~V-5、27+2 条回归测试、§0.1 战略裁决（甲）、§2.6 去重键收窄 |
| 11 | `diagrams/version-b-business-flows-2026-09-13.md` | 版本 B 业务流程图（5 张 Mermaid + 限制清单 15 条 + 设计原则 10 条） |

**链条结论速记**：版本 B = AI 由支付成功信号触发（自动通道）+ 支付过程专用槽位（`payment:<sessionId>`，2min 兜底/事件驱动）+ Y4 修法（金额变化即新会话）+ 落库顺序②（AI 定论后才落待确认/弹窗）+ `handoffContext` 映射化；journey（批 4）暂缓、金额字段作共享基建。

## 三、QA 与真机验证（`qa/`）

| 文档 | 作用 |
|---|---|
| `qa/checkout-manual-capture-priority-fix.md` | ★**主 QA 记录**（5.1~5.11）：五轮真机验证、X1/Y5/Y4 修复批次实施、0.0.98 验证预案、环境异常备忘（§7） |
| `qa/checkout-recognition-verification.md` | 早期识别链路验证（P0 结论：敏感页/指纹/缓冲行为） |
| `qa/checkout-automatic-capture-review.md` | 自动截图链路评审 |
| `qa/golden-path-regression.md` | **黄金回归脚本**（3 正向 + 4 负向；第一节=服务假死/半死检查，每次覆盖安装必查） |
| `qa/pending-reminder-logs.md` | 待确认提醒日志采集约定（**按需抓取**，不常开） |
| `qa/PHASE2_ACCEPTANCE_REPORT.md` / `PHASE3_TASK_BREAKDOWN.md` / `PHASE4_CONTRACT_CHANGE_NOTES.md` | 阶段二验收报告 / 阶段三任务分解 / 阶段四契约变更说明 |
| `qa/phase6/`、`qa/phase7/` | 阶段六（导入）/ 阶段七（稳定性+支付匹配）T9 验收矩阵与缺陷修复记录 |
| `qa/batch1/`、`qa/device/`、`qa/import-samples/` | **原始证据**（logcat/截图/布局 dump/官方样本索引）——**勿修改** |

## 四、TDD 技术规格（`tdd/`）

`01_ai_provider_adapter`（AI 适配器）· `02_room_schema`（Room 架构 v12）· `03_native_module`（原生模块）· `04_phase1_tasks` / `06_phase2_tasks` / `07_phase3_tasks`（分阶段任务分解）· `05_test_samples`（测试样本）· **`08_phase4_journey_spec`（journey 模型规格——2026-09-13 裁决暂缓实施，金额字段由批 V-2 先行建成共享基建）**

## 五、图与截图

- `diagrams/`：系统架构图（HTML 交互版 + 渲染 PNG）· **版本 B 业务流程图（Mermaid）**
- `screenshots/`：产品界面截图与演示动图（根 README 展示用）

## 六、归档（`archive/`）

旧版设计文档，仅作考古：`LITENOTE_AI_PAYMENT_DESIGN.md`（LiteNote 支付设计，`PaymentOverlayManager` 的出处，该类已按 D-20 删除）· `NEW_PROJECT_KICKOFF.md`（V1 启动文档，被 V2 取代）

---

## 维护约定

1. 文件名含日期的核对链文档是**历史快照**：被后续裁定超越的内容按"加注不覆盖"处理（~~删除线~~ → ⚠️ 指向新裁定），不改正文原话。
2. 任何新裁定先落 `CONFLICT_REVIEW_SESSION`（讨论记录）→ 正典同步 `DECISIONS.md` → 规格同步 `NEW_PROJECT_KICKOFF_V2.md` → 实施同步 `IMPL_PLAN_VERSION_B`。
3. `qa/device/` 等原始证据目录**只增不改**；日志分析引用行号时必须钉死到文件+时间戳。
