# JotBill 阶段三任务拆分与文件边界

> 文档状态：阶段三开发与验收规划，不代表任何任务已经完成。
>
> 编写基线：`codex/phase3-task-map`，起点为 `codex/phase2-acceptance-report` 的 `470948c`。
>
> 关联依据：[阶段二验收报告](./PHASE2_ACCEPTANCE_REPORT.md)、[阶段二任务拆分](../tdd/06_phase2_tasks.md)、[Room Schema](../tdd/02_room_schema.md)、[Native Module 设计](../tdd/03_native_module.md)、[Native 契约](../types/Native.d.ts)、[项目启动文档](../NEW_PROJECT_KICKOFF_V2.md)、[开发指南](../DEVELOPMENT.md)。

## 1. 结论与执行规则

阶段二验收报告的正式结论是“验收不通过（证据不足，部分项目待验证，存在阻塞项）”。当前报告统计为：正式完成 0 项、部分完成 5 项、待验证 6 项、阻塞项 3 项。本文件把这些问题收敛为 8 个任务，先关闭数据和业务闭环，再做备份、网络边界、加密和性能实证，最后形成正式验收证据。

本轮任务必须按以下顺序开发和合并，禁止并行修改共享核心文件：

`T1 默认数据与账户余额` → `T2 账单编辑删除闭环` → `T3 备份恢复` → `T4 离线与网络边界` → `T5 SQLCipher 实证` → `T6 报表正确性与性能` → `T7 全量持久化与异常恢复` → `T8 最终验收证据`

通用规则：

- 所有开发分支均从前置任务分支创建，禁止在 `master` 或阶段二报告分支直接修改业务代码。
- 一个任务完成后先提交，再创建下一个任务分支；未提交或工作树不干净时不得交接。
- 所有提交信息使用中文，提交内容只覆盖本任务允许的文件；证据文件必须脱敏，不得提交 API Key、数据库原件、密钥、原始通知或完整支付凭证。
- 现有 [阶段二验收报告](./PHASE2_ACCEPTANCE_REPORT.md) 是审计基线，只读引用，不得为了“通过”而改写其结论。
- “代码已实现”只表示当前源码中能找到实现；“已有验收证据”只表示仓库中存在可观察材料；二者都不能替代本文件任务的操作记录和可复核证据。

## 2. 当前基线事实

### 2.1 代码已实现（仅静态实现事实）

以下内容可以在 `470948c` 基线源码中看到，但不等于已通过验收：

| 范围 | 当前可见实现 | 仍不能推出的结论 |
|---|---|---|
| 数据库 | `jotbill-app/android/app/src/main/java/com/jotbill/app/data/JotBillDatabase.kt` 声明 Room v3、迁移 1→2/2→3；`jotbill-app/android/app/src/main/java/com/jotbill/app/data/JotBillDatabaseProvider.kt` 使用 SQLCipher `SupportFactory` 和 `KeyStoreSecretStore` | 不能推出实际运行中的数据库文件一定加密，或迁移失败一定能回滚 |
| 账单写入 | `jotbill-app/android/app/src/main/java/com/jotbill/app/data/LedgerRepository.kt` 有创建、编辑、删除时的事务、余额差额计算和账单审计记录；`jotbill-app/android/app/src/main/java/com/jotbill/app/bridge/DataModule.kt` 暴露账单 CRUD | 不能推出 RN 表单传入了正确账户，也不能推出编辑、删除后的余额、报表和审计已经被真机核对 |
| 账户 | `jotbill-app/android/app/src/main/java/com/jotbill/app/bridge/DataModule.kt` 暴露账户 CRUD、余额校准、净资产查询；`jotbill-app/src/screens/settings/AccountsScreen.tsx` 有新增、编辑、停用和校准入口 | 不能推出默认 4 个账户存在，或校准会产生可核对的 `ADJUSTMENT` 账单 |
| 默认分类 | `jotbill-app/android/app/src/main/java/com/jotbill/app/bridge/DataModule.kt` 的 `ensureDefaultCategories()` 能幂等创建内置分类，但当前基线代码列出的默认分类为 10 个 | 不能推出阶段二要求的 20 个默认分类和首次启动/二次启动行为通过验收 |
| 备份 | `jotbill-app/android/app/src/main/java/com/jotbill/app/backup/BackupModule.kt` 当前提供 CSV 导出和清除数据；`jotbill-app/src/screens/settings/DataManagementScreen.tsx` 只有导出、清除入口 | 不能推出有版本校验、完整备份、预览、覆盖/合并恢复或损坏文件回滚 |
| 报表 | `jotbill-app/android/app/src/main/java/com/jotbill/app/data/dao/BillDao.kt` 和 `jotbill-app/android/app/src/main/java/com/jotbill/app/bridge/DataModule.kt` 提供收支、分类、平台、日/月趋势查询；RN 有日/月/年视图和资产 Tab | 不能推出 1000 条账单查询满足 500 ms 门槛，或跨年、净资产公式和长字段显示已经验证 |
| 网络 | AI 直连代码和 `jotbill-app/src/services/update/`、原生 `jotbill-app/android/app/src/main/java/com/jotbill/app/hotupdate/` 仍存在；`jotbill-app/android/app/src/main/java/com/jotbill/app/MainApplication.kt` 仍注册热更新包 | 不能由“本地 Data 模块存在”推出运行时无账本后端依赖 |

阶段二同一时间线上还有 `codex/phase2-default-data` 的 `360075c`、`codex/phase2-native-contract` 的 `f14c11d` 及 `codex/phase2-integration` 的后续提交。这些提交不是当前基线的祖先，未纳入本任务分支；其代码或提交说明不能被写成当前基线已实现或已有验收证据。

### 2.2 已有验收证据（只记录能观察到的事实）

| 证据路径 | 可以支持的事实 | 明确不能支持的结论 |
|---|---|---|
| `docs/qa/device/native-core-dashboard.xml`、`native-category-fix-dashboard.xml` | 包名为 `com.jotbill.app`，主页可显示账本汇总或单笔支出 | 不能证明干净安装、默认数据、断网运行或全量功能 |
| `docs/qa/device/native-core-create-bill-filled.xml`、`docs/qa/device/native-core-create-bill-filled.png` | 创建账单表单可以填入 `123.45` 并选择分类 | 不能单独证明保存成功、余额变化或审计记录 |
| `docs/qa/device/native-core-bill-detail-verified.xml`、`native-category-fix-all-bills.xml` | 详情和全部账单列表可以显示 `¥123.45` | 未覆盖编辑、删除、余额回滚和统计回退 |
| `docs/qa/device/native-core-dashboard-relaunch.xml`、`docs/qa/device/native-core-dashboard-relaunch.png` | 一笔账单在标记为 relaunch 的材料中仍可显示 | 未覆盖全实体、force-stop、迁移和异常恢复 |
| `docs/qa/device/report-native-income-final.xml`、`report-native-month.xml` | 日/月收支页面可以显示本地账单金额 | 没有 1000 条数据计时、跨年边界或无网络证明 |
| `docs/qa/device/2026-08-06-settings-native-screens-accounts.xml` | 账户页曾显示“暂无账户”和 `¥0.00` | 与 4 个默认账户要求冲突，不能当作初始化通过证据 |
| `docs/qa/device/2026-08-06-settings-native-screens-data-management.xml`、`docs/qa/device/2026-08-06-settings-native-screens-export-picker-after-fix.xml` | 数据管理页有导出入口并能打开 Android 文件选择器 | 不能证明导出内容、恢复能力、一致性或回滚 |
| `docs/qa/device/native-category-fix-dashboard-logcat.txt` | 日志显示 `libsqlcipher.so` 加载成功 | 不能证明实际 DB 无法被明文 `sqlite3` 打开，也不能替代抓包 |

以上材料由阶段二报告审查，不能把“有截图/有日志”改写成“该用例通过”。后续每项证据都必须记录 APK SHA-256、包名、设备型号、Android 版本、安装方式、操作时间、数据是否清空和对应日志路径。

### 2.3 文件边界总览

| 边界 | 目录/文件 | 责任 |
|---|---|---|
| 本地数据核心 | `jotbill-app/android/app/src/main/java/com/jotbill/app/data/` | Entity、DAO、数据库、事务仓储、迁移和加密 Provider |
| RN-原生数据契约 | `jotbill-app/android/app/src/main/java/com/jotbill/app/bridge/DataModule.kt`、`jotbill-app/src/native/data.ts`、`docs/types/Native.d.ts` | DTO、错误码、账单/账户/统计接口 |
| 业务页面 | `jotbill-app/src/screens/finance/`、`jotbill-app/src/screens/settings/`、`jotbill-app/src/screens/reports/`、`jotbill-app/src/hooks/` | 表单、缓存失效、账户、数据管理和报表展示 |
| 备份边界 | `jotbill-app/android/app/src/main/java/com/jotbill/app/backup/`、`jotbill-app/src/native/backup.ts`、`jotbill-app/src/screens/settings/DataManagementScreen.tsx` | 备份、恢复、导出、导入和清除 |
| 网络边界 | `jotbill-app/src/services/ai.ts`、`jotbill-app/src/services/update/`、`jotbill-app/android/app/src/main/java/com/jotbill/app/ai/`、`jotbill-app/android/app/src/main/java/com/jotbill/app/hotupdate/` | 只允许明确标注的 AI/更新流量，账本数据不得进入网络通道 |
| 验收材料 | `docs/qa/device/` 及阶段三专用证据子目录 | 脱敏截图、XML、日志、抓包清单和性能原始数据 |

## 3. 任务与依赖

### T1 默认数据与账户余额闭环（必须顺序开发）

- **目标**：在干净安装首次启动时幂等创建 20 个默认分类和 4 个默认账户；补齐账户新增、编辑、停用、余额校准及 `ADJUSTMENT` 记录的可核对闭环，确立后续账单任务唯一使用的账户余额规则。
- **前置分支**：`codex/phase3-task-map`。
- **建议工作分支**：`codex/phase3-t1-default-account`。
- **允许修改的文件/目录**：
  - `jotbill-app/android/app/src/main/java/com/jotbill/app/data/seed/`（可新增默认数据初始化器及数据文件）；
  - `jotbill-app/android/app/src/main/java/com/jotbill/app/data/JotBillDatabaseProvider.kt`、`jotbill-app/android/app/src/main/java/com/jotbill/app/data/dao/AccountDao.kt`、`jotbill-app/android/app/src/main/java/com/jotbill/app/data/dao/CategoryDao.kt`、`jotbill-app/android/app/src/main/java/com/jotbill/app/data/entity/AccountEntity.kt`、`jotbill-app/android/app/src/main/java/com/jotbill/app/data/entity/CategoryEntity.kt`；
  - `jotbill-app/android/app/src/main/java/com/jotbill/app/bridge/DataModule.kt`、`jotbill-app/android/app/src/main/java/com/jotbill/app/data/LedgerRepository.kt`（仅账户余额/校准和初始化相关事务）；
  - `jotbill-app/src/native/data.ts`、`jotbill-app/src/screens/settings/AccountsScreen.tsx`、`jotbill-app/src/screens/finance/CategoryManageScreen.tsx`、相关账户/分类 Hook；
  - `jotbill-app/android/app/src/test/`、`jotbill-app/__tests__/` 中与默认数据、账户和校准直接相关的测试；`docs/qa/device/phase3-t1-default-data-*` 证据文件。
- **禁止修改的文件/目录**：`jotbill-app/src/screens/reports/`、`jotbill-app/src/screens/finance/BillDetailScreen.tsx`、`jotbill-app/src/screens/finance/CreateBillScreen.tsx`、`jotbill-app/src/screens/finance/AllBillsScreen.tsx`；`jotbill-app/android/app/src/main/java/com/jotbill/app/backup/`；`jotbill-app/src/services/ai.ts`、`jotbill-app/src/services/update/`、原生 `jotbill-app/android/app/src/main/java/com/jotbill/app/ai/` 和 `jotbill-app/android/app/src/main/java/com/jotbill/app/hotupdate/`；`docs/qa/PHASE2_ACCEPTANCE_REPORT.md`；`litenote-backend/`；`master`。
- **输入输出契约**：
  - 空数据库输入：默认分类数量必须为 20，默认账户数量必须为 4；默认账户包含现金、支付宝、微信零钱、信用卡（名称可由产品最终确认，但数量、资产/负债类型和 0 分初始余额必须固定）。
  - 初始化输出：第二次启动、并发初始化或已有用户数据时不重复插入、不覆盖用户修改；默认数据使用稳定判定键或等价幂等策略。
  - `addAccount/updateAccount` 输入使用 `AccountInput/AccountUpdateInput`；`calibrateAccountBalance(id, actualBalanceMinor, note)` 输出 `BalanceCalibration`，并在同一事务中产生一条可查询的 `adjustment` 账单或等价审计事实，差额、校准前后余额和账户 ID 一致。
  - 账户余额、净资产均使用分为单位；资产和负债的符号、`netWorth = assets - abs(liabilities)` 规则必须与 `Native.d.ts` 一致。
- **验收标准**：
  - 清除数据后首次启动得到 20 个分类、4 个账户；再次 force-stop/启动数量不变，已有自定义数据不被覆盖。
  - 新增资产账户和负债账户，编辑名称、停用、校准均可重开页面后看到；校准前后值、差额、`ADJUSTMENT` 记录和审计记录可按 ID 对齐。
  - 账户列表、净资产和后续账单写入使用同一余额来源；不存在“暂无账户”与默认数据同时成立的未解释状态。
  - 保存脱敏 XML/截图、原始操作日志和计数记录；静态代码、单元测试通过不替代真机证据。
- **提交要求**：提交信息使用中文，例如 `阶段三任务1：补齐默认数据与账户余额闭环`；代码、测试和证据分层提交，至少包含一个可独立回滚的实现提交，提交前工作树必须干净。

### T2 账单编辑删除闭环与分类引用保护（必须顺序开发）

- **目标**：把“编辑入口/删除入口”闭合为账单、账户余额、Dashboard、报表、列表和审计记录的一致事务；处理版本冲突、账户变更和分类引用保护，避免误删或重复扣减。
- **前置分支**：`codex/phase3-t1-default-account` 完成并已提交。
- **建议工作分支**：`codex/phase3-t2-bill-crud`。
- **允许修改的文件/目录**：
  - `jotbill-app/android/app/src/main/java/com/jotbill/app/data/LedgerRepository.kt`、`jotbill-app/android/app/src/main/java/com/jotbill/app/data/dao/BillDao.kt`、`jotbill-app/android/app/src/main/java/com/jotbill/app/data/dao/AuditEntryDao.kt`、`jotbill-app/android/app/src/main/java/com/jotbill/app/data/dao/CategoryDao.kt`；
  - `jotbill-app/android/app/src/main/java/com/jotbill/app/bridge/DataModule.kt`、`jotbill-app/android/app/src/main/java/com/jotbill/app/bridge/BridgeDtoMapper.kt`、`jotbill-app/src/native/data.ts`；
  - `jotbill-app/src/screens/finance/CreateBillScreen.tsx`、`jotbill-app/src/screens/finance/BillDetailScreen.tsx`、`jotbill-app/src/screens/finance/AllBillsScreen.tsx`、`jotbill-app/src/screens/finance/StatisticsScreen.tsx`、`jotbill-app/src/screens/dashboard/DashboardScreen.tsx`；
  - `jotbill-app/src/hooks/` 中账单、Dashboard、资产和统计查询；`jotbill-app/src/lib/queryClient.ts`；账单/Bridge 单元或集成测试；`docs/qa/device/phase3-t2-bill-crud-*`。
- **禁止修改的文件/目录**：`jotbill-app/android/app/src/main/java/com/jotbill/app/data/seed/`、`jotbill-app/android/app/src/main/java/com/jotbill/app/data/JotBillDatabaseProvider.kt`、`jotbill-app/android/app/src/main/java/com/jotbill/app/security/KeyStoreSecretStore.kt`、`jotbill-app/android/app/src/main/java/com/jotbill/app/backup/`、`jotbill-app/src/screens/settings/DataManagementScreen.tsx`、`jotbill-app/src/services/update/`、原生 `jotbill-app/android/app/src/main/java/com/jotbill/app/hotupdate/`、`docs/types/Native.d.ts`（除非先单独建立契约变更提交并停止本任务交接）；`docs/qa/PHASE2_ACCEPTANCE_REPORT.md`；`master`。
- **输入输出契约**：
  - `updateBill(id, BillUpdateInput)` 必须携带当前 `version`，成功返回新账单且版本号加 1；旧版本返回 `VERSION_CONFLICT`，不得产生任何余额、账单或审计副作用。
  - `deleteBill(id)` 成功后账单不可查询，且返回无业务数据的成功结果；不存在的 ID 返回明确 `NOT_FOUND`。
  - 创建、编辑、删除均通过原生事务同时处理账单和账户余额；编辑要先撤销旧账单差额再应用新账单差额，账户或分类变更不得遗留旧余额。
  - 每次创建、编辑、删除至少有可按账单 ID 查询的审计记录；分类被账单引用时必须拒绝误删或按已确认的外键策略保留可解释关联。
- **验收标准**：
  - 使用固定合成账单完成“填写→保存→列表→详情→Dashboard/报表→账户余额”记录；金额从 A 改为 B 后所有视图和余额只反映 B。
  - 删除同一账单后列表移除、统计回退、净资产回退，重复删除不会再次扣减或误删其他账单。
  - 账户从 A 改到 B、金额和类型变化、分类删除冲突、过期版本提交均有明确结果；审计记录的旧值、新值和时间线可复核。
  - 证据必须包含每个动作后的 XML/截图、账单 ID、余额前后值和日志，不能只提交单张详情页截图。
- **提交要求**：提交信息使用中文，例如 `阶段三任务2：闭环账单编辑删除与余额回滚`；`DataModule.kt`、`LedgerRepository.kt` 和 `BillDao.kt` 的修改集中在本任务提交，提交后将其视为后续任务只读的已验证核心。

### T3 完整备份与恢复闭环（必须顺序开发）

- **目标**：从当前只有 CSV 导出的状态补齐完整备份、版本/完整性校验、恢复预览、覆盖/合并策略和失败回滚；恢复后数据与账户余额、关联关系和配置一致，API Key 不进入备份。
- **前置分支**：`codex/phase3-t2-bill-crud` 完成并已提交。
- **建议工作分支**：`codex/phase3-t3-backup-restore`。
- **允许修改的文件/目录**：
  - `jotbill-app/android/app/src/main/java/com/jotbill/app/backup/`（可新增 `BackupManager`、导出/解析器及事务恢复实现）；
  - `jotbill-app/src/native/backup.ts`、`jotbill-app/src/screens/settings/DataManagementScreen.tsx`；必要的文件选择器调用封装；
  - `jotbill-app/android/app/src/test/`、`jotbill-app/__tests__/` 中备份恢复测试；`docs/qa/device/phase3-t3-backup-*`。
- **禁止修改的文件/目录**：`jotbill-app/android/app/src/main/java/com/jotbill/app/data/entity/`、`jotbill-app/android/app/src/main/java/com/jotbill/app/data/dao/`、`jotbill-app/android/app/src/main/java/com/jotbill/app/data/JotBillDatabase.kt`、`jotbill-app/android/app/src/main/java/com/jotbill/app/data/JotBillDatabaseProvider.kt`、`jotbill-app/android/app/src/main/java/com/jotbill/app/data/LedgerRepository.kt`、`jotbill-app/android/app/src/main/java/com/jotbill/app/bridge/DataModule.kt`、`jotbill-app/src/native/data.ts`、账单/账户/报表业务页面；`docs/types/Native.d.ts`（当前契约已提供 Backup 方法，缺字段须先走契约变更）；`docs/qa/PHASE2_ACCEPTANCE_REPORT.md`；`master`。
- **输入输出契约**：
  - `createBackup({ encrypt? })` 输出 `BackupSummary`；备份文件必须有版本号、完整性校验值、生成时间和各实体计数，默认不加密但支持用户选择加密。
  - `restoreBackup(filePath)` 只做文件存在性、校验和版本兼容性检查，输出 `RestorePreview`，不改变当前数据库。
  - `executeRestore(filePath, 'overwrite'|'merge')` 输出 `ImportResult`；至少覆盖账单、账户、分类、预算、财务目标、规则、审计/校准所需关系和必要应用配置，不包含 API Key、Keystore 密钥、原始截图或敏感凭证。
  - 任一校验失败、损坏文件、版本不兼容、取消或中途异常都必须保持原数据库不变；覆盖和合并的重复计数规则必须写入结果。
- **验收标准**：
  - 使用包含多账户、多分类、编辑/删除后账单、校准记录、预算、目标、规则和 AI 配置的合成数据，核对备份摘要计数与数据库计数。
  - 恢复预览显示正确范围；覆盖恢复和合并恢复均可重启后复核，账单、余额、净资产、分类关联和配置一致。
  - 修改校验值、截断文件、使用不兼容版本和取消操作均不会破坏原数据；恢复失败有明确错误码和日志。
  - 验证导出文件列头、记录数、金额和编码；CSV 导出成功不得代替完整恢复通过。
- **提交要求**：提交信息使用中文，例如 `阶段三任务3：实现本地备份恢复与失败回滚`；备份格式、恢复策略和测试样本说明随代码同一提交，任何真实数据库或密钥不得进入提交。

### T4 离线运行与网络边界实证（必须顺序开发）

- **目标**：证明账本、分类、账户、预算和报表不依赖后端；明确 AI/更新等允许网络通道与账本通道的边界，消除“静态删除 API 就算无后端”的误判。
- **前置分支**：`codex/phase3-t3-backup-restore` 完成并已提交。
- **建议工作分支**：`codex/phase3-t4-offline-network`。
- **允许修改的文件/目录**：
  - `jotbill-app/src/services/ai.ts`、`jotbill-app/src/native/ai.ts`、`jotbill-app/src/config/env.ts`、`jotbill-app/src/utils/url.ts`；原生 `jotbill-app/android/app/src/main/java/com/jotbill/app/ai/`；
  - `jotbill-app/src/services/update/`、`jotbill-app/src/hooks/useAppUpdate.ts`、`jotbill-app/src/hooks/useHotUpdate.ts`、`jotbill-app/src/components/UpdateModal.tsx`；原生 `jotbill-app/android/app/src/main/java/com/jotbill/app/hotupdate/`、必要的 `jotbill-app/android/app/src/main/java/com/jotbill/app/MainApplication.kt`/`jotbill-app/android/app/src/main/AndroidManifest.xml` 网络开关；
  - 仅为网络边界所需的静态扫描、Native Mock 和日志测试；`docs/qa/device/phase3-t4-offline-*`、`docs/qa/device/phase3-t4-capture-*` 脱敏证据。
- **禁止修改的文件/目录**：`jotbill-app/android/app/src/main/java/com/jotbill/app/data/`、`jotbill-app/android/app/src/main/java/com/jotbill/app/bridge/DataModule.kt`、`jotbill-app/android/app/src/main/java/com/jotbill/app/data/LedgerRepository.kt`、`jotbill-app/android/app/src/main/java/com/jotbill/app/backup/`、`jotbill-app/src/native/data.ts`、所有账单/账户/报表页面；不得修改 `litenote-backend/` 以伪造本地化结果；不得把 AI 请求删除后宣称边界通过；`docs/qa/PHASE2_ACCEPTANCE_REPORT.md`；`master`。
- **输入输出契约**：
  - 在飞行模式下，Data Module 的账单、分类、账户、预算、净资产和报表读写必须成功；AI 允许失败，但必须返回可理解的网络错误，不得创建错误账单或阻塞本地操作。
  - 抓包操作 1-9 时账本/分类/账户/统计请求数必须为 0；AI 请求、更新请求若存在，必须按目的地、时间和触发动作单独列出，不得混入账本请求。
  - 所有本地功能的输入输出仍使用既有 Native 契约；网络边界任务不得偷偷改变金额、时间、版本号或错误码语义。
- **验收标准**：
  - 飞行模式下重复阶段二用例 1-9，保存每一步成功/失败结果；账本、分类、账户、预算和报表均正常，AI 失败不影响账本。
  - 使用 Charles、mitmproxy 或系统 VPN 保存脱敏请求清单、过滤规则、时间范围和设备信息；明确允许的 AI/更新流量，账本流量为 0。
  - 静态搜索、运行日志和抓包三者结论一致；不把 `libsqlcipher.so` 日志当作网络证据，不把 AI 外部请求误报为账本后端请求。
- **提交要求**：提交信息使用中文，例如 `阶段三任务4：收口离线运行与网络边界`；代码变更和抓包/飞行模式证据分开提交，证据中不得包含 API Key、原始请求体或完整账单。

### T5 SQLCipher 实际数据库加密实证（必须顺序开发）

- **目标**：针对当前仅有库加载日志的缺口，使用授权 Debug 设备上的实际运行数据库完成明文 SQLite 打开失败、应用密钥正常读写和日志不泄露密钥的证据闭环；若实证失败，只修复 Provider/密钥路径，不扩大业务范围。
- **前置分支**：`codex/phase3-t4-offline-network` 完成并已提交。
- **建议工作分支**：`codex/phase3-t5-sqlcipher-proof`。
- **允许修改的文件/目录**：`jotbill-app/android/app/src/main/java/com/jotbill/app/data/JotBillDatabaseProvider.kt`、`jotbill-app/android/app/src/main/java/com/jotbill/app/security/KeyStoreSecretStore.kt`；`jotbill-app/android/app/build.gradle` 中 SQLCipher 依赖；与密钥/Provider 直接相关的 Android 测试；`docs/qa/device/phase3-t5-sqlcipher-*` 和脱敏原始命令输出。
- **禁止修改的文件/目录**：`jotbill-app/android/app/src/main/java/com/jotbill/app/data/entity/`、`jotbill-app/android/app/src/main/java/com/jotbill/app/data/dao/`、`jotbill-app/android/app/src/main/java/com/jotbill/app/data/LedgerRepository.kt`、`jotbill-app/android/app/src/main/java/com/jotbill/app/bridge/DataModule.kt`、`jotbill-app/android/app/src/main/java/com/jotbill/app/backup/`、RN 业务页面、报表查询、AI/网络代码；不得把数据库原件、密钥、Keystore 导出物或未脱敏数据写入仓库；`docs/qa/PHASE2_ACCEPTANCE_REPORT.md`；`master`。
- **输入输出契约**：
  - 输入必须是当前 APK 运行实例创建并写入合成账单后的实际 DB 副本，不得使用空文件、示例文件或仅依赖库文件。
  - 系统 `sqlite3` 直接执行 `.tables`/查询时输出无法识别数据库或等价失败结果；应用通过现有 Keystore/SQLCipher Provider 能正常打开、读取并写入哨兵数据。
  - 日志输出只能证明库加载、打开成功或失败和错误码，不得输出 passphrase、API Key 或完整账单。
- **验收标准**：
  - 记录设备、APK SHA-256、数据库文件来源、复制时间、工具版本和明文 SQLite 原始失败输出；失败结果与文件哈希可复核。
  - 同一 DB 副本由应用正常读取，写入哨兵账单后重启仍可读取；明文工具失败不能被“应用启动成功”替代。
  - 检查 logcat、崩溃日志和临时目录无密钥泄露；如失败，先阻塞后续最终验收，不得改写成部分通过以外的状态。
- **提交要求**：提交信息使用中文，例如 `阶段三任务5：补充 SQLCipher 实际数据库实证`；提交只包含必要 Provider/依赖修复、测试和脱敏证据，严禁提交数据库和密钥材料。

### T6 报表正确性与 1000 条性能闭环（必须顺序开发）

- **目标**：在核心账单和账户闭环稳定后，验证资产/负债/净资产、日/月/年收支、分类和跨年边界；建立可重复的 1000 条合成账单报表基准，满足阶段二 500 ms 门槛并记录统计方法。
- **前置分支**：`codex/phase3-t5-sqlcipher-proof` 完成并已提交。
- **建议工作分支**：`codex/phase3-t6-report-performance`。
- **允许修改的文件/目录**：
  - `jotbill-app/android/app/src/main/java/com/jotbill/app/data/dao/BillDao.kt`、`jotbill-app/android/app/src/main/java/com/jotbill/app/data/model/QueryRows.kt`、`jotbill-app/android/app/src/main/java/com/jotbill/app/bridge/DataModule.kt`、`jotbill-app/src/native/data.ts`（仅统计查询、索引和分页）；必要时 `jotbill-app/android/app/src/main/java/com/jotbill/app/data/entity/BillEntity.kt`、`jotbill-app/android/app/src/main/java/com/jotbill/app/data/JotBillDatabase.kt` 的查询索引/迁移；
  - `jotbill-app/src/screens/reports/`、`jotbill-app/src/components/reports/`、`jotbill-app/src/hooks/useAssetsData.ts`、`jotbill-app/src/hooks/useIncomeExpenseData.ts`、`jotbill-app/src/hooks/useStatistics.ts`、`jotbill-app/src/utils/nativeStatistics.ts`；报表/DAO 性能测试；`docs/qa/device/phase3-t6-report-*`、`docs/qa/performance/phase3-*`。
- **禁止修改的文件/目录**：`jotbill-app/android/app/src/main/java/com/jotbill/app/data/seed/`、`jotbill-app/src/screens/settings/AccountsScreen.tsx`、账单编辑删除 UI、`jotbill-app/android/app/src/main/java/com/jotbill/app/data/LedgerRepository.kt` 的余额事务、`jotbill-app/android/app/src/main/java/com/jotbill/app/backup/`、`jotbill-app/android/app/src/main/java/com/jotbill/app/security/`、AI/网络代码；不得通过删除数据、降低查询范围或伪造计时达到 500 ms；`docs/qa/PHASE2_ACCEPTANCE_REPORT.md`；`master`。
- **输入输出契约**：
  - `getBillStatistics(filter)` 必须保持 `BillStatistics` 字段、金额分单位和 `occurredAt` 本地时区归类；`getNetWorth()` 必须使用账户当前余额，净资产公式为资产余额合计减负债绝对值合计。
  - 性能输入为 1000 条可追溯的合成账单；输出至少包含冷查询、热查询各 30 次的平均值、P95、最大值、设备信息、数据库状态和计时起止点，阶段二门槛为查询不超过 500 ms。
  - 报表查询必须仍是本地调用，不新增 HTTP；查询结果需覆盖收入/支出分类、平台、日/月/年趋势和 2025-12→2026-01 跨年边界。
- **验收标准**：
  - 资产 Tab 的总资产、总负债、净资产与账户页逐项一致，账单新增/编辑/删除后统计同步回退或增加。
  - 日/月/年切换、跨年、分类筛选、长商户名/商品名均有 XML/截图和输入数据记录；长字段不遮挡相邻内容。
  - 1000 条基准原始计时日志完整，冷/热查询均达到 500 ms 门槛；若失败，提交查询计划和后续修复，不得只保留“最快一次”。
- **提交要求**：提交信息使用中文，例如 `阶段三任务6：完成报表校验与性能基准`；查询代码、性能测试和结果报告同一任务提交，不能改动 T1/T2 已验证的账户和账单事务。

### T7 全量实体持久化与异常恢复（必须顺序开发）

- **目标**：把当前单笔账单 relaunch 证据扩展为所有阶段二实体的 force-stop、进程终止、数据库重开和迁移失败回滚证据，确认默认初始化不重复、审计和配置不丢失。
- **前置分支**：`codex/phase3-t6-report-performance` 完成并已提交。
- **建议工作分支**：`codex/phase3-t7-persistence-recovery`。
- **允许修改的文件/目录**：`jotbill-app/android/app/src/main/java/com/jotbill/app/data/JotBillDatabase.kt`、`jotbill-app/android/app/src/main/java/com/jotbill/app/data/JotBillDatabaseProvider.kt`、已有或新增 `jotbill-app/android/app/src/main/java/com/jotbill/app/data/migration/`、与应用启动/进程恢复直接相关的 `jotbill-app/android/app/src/main/java/com/jotbill/app/MainApplication.kt`；迁移、重启和恢复测试；`docs/qa/device/phase3-t7-relaunch-*`、`docs/qa/device/phase3-t7-recovery-*`。
- **禁止修改的文件/目录**：`jotbill-app/android/app/src/main/java/com/jotbill/app/data/seed/` 的默认数据语义、`jotbill-app/android/app/src/main/java/com/jotbill/app/data/LedgerRepository.kt` 的业务余额算法、`jotbill-app/android/app/src/main/java/com/jotbill/app/backup/` 的备份格式、`jotbill-app/android/app/src/main/java/com/jotbill/app/bridge/DataModule.kt` 的 DTO 契约、报表查询、AI/网络代码和业务页面；不得用清除数据或重新初始化掩盖丢失；`docs/qa/PHASE2_ACCEPTANCE_REPORT.md`；`master`。
- **输入输出契约**：
  - 输入快照至少包含账单、分类、账户及余额校准、预算、财务目标、AI Provider 配置、AI 会话、审计记录和必要设置；记录每个实体的数量、关键 ID 和摘要。
  - 执行 `adb shell am force-stop com.jotbill.app`、重新启动、进程被杀和数据库重开后，输出逐实体对照结果；数量、ID、版本、余额和关联关系不得改变，默认数据不得重复。
  - 迁移失败输出明确 `DB_MIGRATION_FAILED` 或等价错误，原数据库保持可打开且数据不变；迁移成功输出新版本和迁移日志。
- **验收标准**：
  - 全部实体在 force-stop 后逐项恢复，不能只验证 Dashboard 一笔账单；AI 配置和会话也必须单独核对。
  - 模拟升级/迁移成功与失败各一次，失败后可回到旧数据并能继续使用本地账本；没有未解释的空账户或空分类状态。
  - 保存设备、APK、时间、进程控制命令、实体快照、日志和恢复结果；异常场景证据单独标注，不与“正常重启通过”混写。
- **提交要求**：提交信息使用中文，例如 `阶段三任务7：补齐全量持久化与异常恢复`；只提交迁移/启动恢复修复、测试和脱敏证据，若发现业务算法缺陷必须退回对应任务，不在本任务越界修改。

### T8 最终验收证据与签署材料（必须顺序开发）

- **目标**：汇总 T1-T7 的提交、操作记录和证据，形成可复核的阶段三最终验收报告；严格区分通过、部分完成、待验证、失败和阻塞，不以代码提交替代操作证据。
- **前置分支**：`codex/phase3-t7-persistence-recovery` 完成并已提交，且 T1-T7 的证据清单均已交接。
- **建议工作分支**：`codex/phase3-t8-final-acceptance`。
- **允许修改的文件/目录**：新增 `docs/qa/PHASE3_ACCEPTANCE_REPORT.md`；新增或补充 `docs/qa/device/phase3-final-*`、`docs/qa/performance/phase3-final-*` 等脱敏证据索引和汇总材料。只读引用各任务提交、阶段二报告和现有设备证据。
- **禁止修改的文件/目录**：所有业务源码、`docs/types/Native.d.ts`、`docs/qa/PHASE2_ACCEPTANCE_REPORT.md`、本任务拆分文档 `PHASE3_TASK_BREAKDOWN.md`、`litenote-backend/`、`master`；不得删除失败证据、改写历史提交或把“代码已实现”栏目改成“验收已通过”。
- **输入输出契约**：
  - 输入为 T1-T7 的提交号、分支、APK SHA-256、设备/Android 信息、操作时间线、XML/截图、日志、抓包清单、SQLCipher 原始输出、备份恢复计数和性能原始计时。
  - 输出报告必须逐项覆盖阶段二 14 个用例及总标准：首次启动、增删改账单、分类、账户、预算、资产/收支报表、AI 边界、离线、抓包、SQLCipher、重启保持、备份恢复和性能。
  - 每项必须有“当前状态、前置、操作、预期、实际、证据路径、提交号、复核人/时间”；“部分完成”和“待验证”不得写成“通过”。
- **验收标准**：
  - 14 项均有明确结论和证据索引；无阻塞项时才可签署通过；任一断网、抓包、SQLCipher、默认数据、备份恢复或性能证据缺失，最终状态必须保留为未通过/阻塞。
  - 报告能从一个用例追溯到输入数据、操作记录、原始证据和实现提交，也能从每个提交追溯到验收用例。
  - 最终报告和证据脱敏检查通过，工作树干净；不运行本任务以外的构建或测试来填补缺失证据。
- **提交要求**：提交信息使用中文，例如 `阶段三任务8：形成最终验收证据`；最终报告和证据索引单独提交，提交后必须执行只读状态检查并保持工作树干净，不得提交真实敏感材料。

## 4. 共享核心文件与冲突风险

下表中的文件只能由列出的任务按顺序修改；禁止两个任务分支同时改同一文件。任务交接以提交号为边界，后续任务必须从前置分支创建并先读取前置提交。

| 共享核心文件/目录 | 修改所有权 | 顺序要求 | 冲突风险 |
|---|---|---|---|
| `jotbill-app/android/app/src/main/java/com/jotbill/app/bridge/DataModule.kt` | T1 账户/初始化，T2 账单 CRUD，T6 统计查询 | T1→T2→T6 | 原生方法、错误码和事务入口集中在一个文件，最容易产生覆盖和漏失 |
| `jotbill-app/android/app/src/main/java/com/jotbill/app/data/LedgerRepository.kt` | T1 校准相关事务，T2 账单余额事务 | T1→T2；T6/T7 禁止改业务算法 | 账单编辑删除若与账户校准并行，会造成余额重复加减 |
| `jotbill-app/android/app/src/main/java/com/jotbill/app/data/dao/BillDao.kt`、`jotbill-app/android/app/src/main/java/com/jotbill/app/data/dao/AccountDao.kt`、`jotbill-app/android/app/src/main/java/com/jotbill/app/data/dao/AuditEntryDao.kt` | T1 账户/校准，T2 账单/审计，T6 统计 | T1→T2→T6 | SQL 查询、余额字段和审计读取互相影响；不能分别在多个任务中重写 |
| `jotbill-app/android/app/src/main/java/com/jotbill/app/data/JotBillDatabase.kt`、`jotbill-app/android/app/src/main/java/com/jotbill/app/data/JotBillDatabaseProvider.kt` | T1 初始化入口，T5 Provider 实证修复，T6 查询索引，T7 迁移恢复 | T1→T5→T6→T7 | 数据库版本、SQLCipher Provider 和迁移数组必须成组演进 |
| `jotbill-app/android/app/src/main/java/com/jotbill/app/security/KeyStoreSecretStore.kt` | T5 | 仅 T5 可改 | 密钥路径变化会使 T3 备份和 T7 重启结果失真 |
| `docs/types/Native.d.ts` | 基线契约冻结；如需变更，先单独建立契约提交并重新排队 | 不允许并行修改 | DTO/错误码字段不一致会让 RN、原生和验收报告出现三套事实 |
| `jotbill-app/src/native/data.ts` | T1 账户，T2 账单，T6 统计 | T1→T2→T6 | 包装器输入输出和缓存失效容易被后续改动覆盖 |
| `jotbill-app/src/lib/queryClient.ts` 及账单/报表 Hooks | T2 账单闭环，T6 报表 | T2→T6 | 只刷新账单而不刷新账户/报表会形成界面假一致 |
| `jotbill-app/android/app/src/main/java/com/jotbill/app/MainApplication.kt` | T4 网络边界，T7 启动/恢复 | T4→T7 | 热更新、启动加载和数据库初始化顺序相互影响，禁止并行改动 |

证据目录不应形成文件冲突：T1-T7 使用各自的 `phase3-tN-*` 前缀，T8 只读取并建立索引。任何任务若发现必须修改其他任务所有的核心文件，应停止合并，先新增契约/边界变更记录，再调整依赖顺序。

## 5. 闭环映射与放行门槛

| 阶段二未闭环问题 | 负责任务 | 放行条件 |
|---|---|---|
| 默认 20 分类、4 账户、账户余额和校准 | T1 | 初始化幂等、账户操作和 `ADJUSTMENT` 证据完整 |
| 账单编辑、删除、余额回滚、审计、缓存一致 | T2 | 固定合成账单的完整操作时间线和版本冲突证据完整 |
| 完整备份、恢复预览、覆盖/合并、损坏回滚 | T3 | 备份摘要、恢复前后计数和失败不变性证据完整 |
| 离线账本运行与无账本 HTTP 请求 | T4 | 飞行模式用例和脱敏抓包均满足边界 |
| 实际数据库明文 SQLite 打开失败 | T5 | 当前运行 DB 的原始失败输出、应用正常读写和无密钥泄露证据完整 |
| 报表公式、跨年边界、1000 条不超过 500 ms | T6 | 正确性证据和冷/热计时统计完整 |
| 全实体重启、force-stop、迁移失败回滚 | T7 | 全量快照对照和异常恢复记录完整 |
| 最终 14 项验收证据和报告签署 | T8 | 证据可追溯、无阻塞项，才允许把阶段结论改为通过 |

在 T8 之前，任何任务只能报告自己的“代码状态”和“证据状态”，不能声称阶段二或阶段三整体通过。
