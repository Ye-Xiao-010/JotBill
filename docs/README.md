> **状态头**：索引 | 最后核实 2026-11-04 | 冲突时以 `jotbill-app/android` 代码为准（导航见 `docs/README.md`）

# docs 文档导航（三层：必读 → 主题 → 历史）

> **代码为准**：文档与代码冲突时，一律以 `jotbill-app/android` 实现为事实源。
> 每份文档首部的「状态头」标注其分类与最后核实日期（2026-11-04 统一核实）。
> **正典身份说明**：改代码的规格约束来自**必读清单 5 份的组合**，任何单一文档都不自称全量正典。

## 一、必读（改自动记账代码前，5 份）

| 文档 | 读它回答什么 |
|---|---|
| [`CURRENT_SYSTEM.md`](./CURRENT_SYSTEM.md) | **现状规格（首读）**：现在系统怎么工作——六条信号源 → 归并 → Bill/Pending → RN，含冲突码表与测试映射；改代码先在此定位符号 |
| [`CLAUDE.md`](../CLAUDE.md)（根目录） | 流程与纪律：改代码前读什么、先复述再动手、构建/验证命令、硬性纪律（含敏感页零采集） |
| [`CONFLICT_REVIEW_SESSION_2026-09-11.md`](./CONFLICT_REVIEW_SESSION_2026-09-11.md) | **D 系列唯一完整出处**（DECISIONS.md 缺 D-10/11/20）+ 代码评估清单：判重、优先级、字段锁定等硬约束的裁定原文 |
| [`DECISIONS.md`](./DECISIONS.md) | D 系列裁定速查（按主题分节的决策日志；不全，配合同上使用） |
| [`IMPL_PLAN_VERSION_B_2026-09-13.md`](./IMPL_PLAN_VERSION_B_2026-09-13.md) | §8「开工前决策与修正」8 项已定案决策（含阻断级前置）；⚠️ §4 批次（V-1~V-5）语义已被后续改造接续，冲突以代码为准 |
| [`AUTO_BOOKKEEPING_2026-10-25.md`](./AUTO_BOOKKEEPING_2026-10-25.md) | 页面规则引擎（8 渠道 + `kind` 分档）现状：渠道定义、认领互斥、与弹窗管线的边界 |

**分工**：`CURRENT_SYSTEM.md` 回答"系统现在怎么工作"（现状规格，含已知限制）；`NEW_PROJECT_KICKOFF_V2.md` 回答"当时为什么这么设计"（设计史料，含已废止需求）。改行为以 CURRENT_SYSTEM + 代码为准，查设计意图与裁定口径才翻史料。

## 二、主题参考（按需查）

| 文档 | 作用 | 状态 |
|---|---|---|
| [`NEW_PROJECT_KICKOFF_V2.md`](./NEW_PROJECT_KICKOFF_V2.md) | 0.3.3 主设计：完整规格、FR 清单、信息架构 | **设计史料（含已废止需求）**——截图/通知停用时代正文已逐块加注（⚠️ 标记），改代码勿照抄其已废止章节 |
| [`00-现状正本.md`](./00-现状正本.md) | 精简版方向 / 现状快照（2026-10-24） | 参考；部分口径已加注失效 |
| [`MIGRATION_MAP.md`](./MIGRATION_MAP.md) | LiteNote → 精简版边界映射 | 参考；阶段边界已被 B1-B3/合并改造接续 |
| [`ROADMAP_FALLBACK_FIRST.md`](./ROADMAP_FALLBACK_FIRST.md) | 落袋优先路线图 | 批 2/3/4/5 已冻结，部分被 2026-10~11 改造实现 |
| [`DEVELOPMENT.md`](./DEVELOPMENT.md) / [`TESTING.md`](./TESTING.md) | 开发环境与构建 / 测试指南 | 稳定 |
| [`lean-icon-system.md`](./lean-icon-system.md) | 图标系统规范 | 稳定 |
| [`LITENOTE_COMPARISON_2026-10-10_payment_overlay.md`](./LITENOTE_COMPARISON_2026-10-10_payment_overlay.md) | 上游 LiteNote 弹窗机制对照（架构债出处） | 稳定 |
| [`FIX_TASK_2026-09-29_attribution_pending_dup.md`](./FIX_TASK_2026-09-29_attribution_pending_dup.md) | 归属/待确认重复取证与修复（D-25 出处） | 问题已处置 |

## 三、历史与过程（取证用，不引导开发）

### 历史取证（保留证据价值，结论可能已过时——先查状态头与 ⚠️ 加注）
`CODE_REVIEW_BY_FEATURE_2026-09-06.md`、`CODE_REVIEW_LEAN_2026-10-24.md`（⚠️"通知停用"已失效）、`CONFLICTS_2026-09-10.md`、`RECONCILIATION_2026-09-11.md`、`DESIGN_REVIEW_2026-09-13.md`、`IMPL_READINESS_RISKS_2026-09-15.md`、`DOC_VERIFICATION_2026-09-13.md`、`ISSUE_REGISTER_2026-09-11.md`、`SCHEME_INVENTORY_2026-09-10.md`、`PROGRESS_REVIEW_2026-09-28.md`、`PROJECT_PROGRESS_REVIEW_2026-09-03.md`、`ISSUE_2026-10-10_lean_payment_overlay_popup_timing.md`

### 过程记录（🗑 纯工作痕迹，无规格价值）
`PROMPT_BATCH1_DEADCODE_CLEANUP.md`、`PROMPT_HANDOFF_2026-09-29_attribution_fix.md`、`INCIDENT_2026-09-15_git_corruption.md`、`worklog/`（4 份）、`diagrams/version-b-business-flows-2026-09-13.md`（图，时代描述已过时）

### 归档（`archive/`）
[`archive/NEW_PROJECT_KICKOFF.md`](./archive/NEW_PROJECT_KICKOFF.md)（旧版主设计）、[`archive/LITENOTE_AI_PAYMENT_DESIGN.md`](./archive/LITENOTE_AI_PAYMENT_DESIGN.md)（上游设计参考）

### QA 原始证据（`qa/`，只增不改、禁止移动）
- 验收 / 证据索引：[`qa/lean-version-acceptance.md`](./qa/lean-version-acceptance.md)（⚠️ L6 通知兜底验收待更新）、[`qa/golden-path-regression.md`](./qa/golden-path-regression.md)、[`qa/lean-user-scenarios.md`](./qa/lean-user-scenarios.md)、[`qa/PHASE2_ACCEPTANCE_REPORT.md`](./qa/PHASE2_ACCEPTANCE_REPORT.md)、[`qa/phase6/T9_ACCEPTANCE_REPORT.md`](./qa/phase6/T9_ACCEPTANCE_REPORT.md)、[`qa/import-samples/README_EVIDENCE_INDEX.md`](./qa/import-samples/README_EVIDENCE_INDEX.md)
- 行为契约 / 真机证据：[`qa/checkout-recognition-verification.md`](./qa/checkout-recognition-verification.md)、[`qa/checkout-automatic-capture-review.md`](./qa/checkout-automatic-capture-review.md)、[`qa/checkout-manual-capture-priority-fix.md`](./qa/checkout-manual-capture-priority-fix.md)、[`qa/payment-notification-effectiveness-2026-09-10.md`](./qa/payment-notification-effectiveness-2026-09-10.md)、[`qa/lean-vs-litenote-comparison.md`](./qa/lean-vs-litenote-comparison.md)、[`qa/pending-reminder-logs.md`](./qa/pending-reminder-logs.md)、`qa/PHASE3_TASK_BREAKDOWN.md`、`qa/PHASE4_CONTRACT_CHANGE_NOTES.md`、`qa/phase7/`（5 份，含断链的 `../../../docs/` 历史链接）、`qa/import-samples/`（导入数据样本）

## 四、维护约定

0. **代码为准**：文档冲突时以 `jotbill-app/android` 实现为事实源；历史文档按「加注不覆盖」订正。2026-10~11 的采集/合并改造（B1-B3、orderId 归并、N-2 吸收）已使早期"v1 唯一来源/通知停用/站内不支持"等口径失效，相关文档均已加 ⚠️ 标记。
1. **方向、状态、缺陷变化时，同步更新 [`00-现状正本.md`](./00-现状正本.md)**（尤其第二、三、四节）。
2. **裁定变化必须同步 `CONFLICT_REVIEW_SESSION` 待议总表**；`DECISIONS.md` 按需补。
3. **任何版本升级、范围修正，必须并行更新 `NEW_PROJECT_KICKOFF_V2.md` 正文与版本注记**（现已降级为设计史料，改为「加注不覆盖」）。
4. **代码文档过期就修** —— 不留"以后补"。
