# 11. 阶段七任务拆分与执行基线

> 技术实现文档（TDD）第十一部分。
>
> 关联文档：[新项目启动基线](../NEW_PROJECT_KICKOFF_V2.md) 第 18/19 节、[阶段六任务基线](10_phase6_tasks.md)、[Room Schema](02_room_schema.md)、[公开 RN 契约](../types/Native.d.ts)。发生冲突时，以 `docs/types/Native.d.ts` 为唯一 RN 契约。
>
> 本文是开发、审查、证据和验收的分工基线。任务完成必须以分支、提交和交接物为单位记录，开发对话不得自行宣称真机样本或真机验收通过。

---

## 1. 文档使用规则

### 1.1 本阶段的执行对象

阶段七的工作对象是稳定性与验收：把阶段二到阶段六已实现的本地账本、识别链路、规则、导入与备份能力收敛为可长期运行的 MVP。阶段七不扩展新业务来源，只补齐自动化测试、数据库迁移回滚、性能验证、自发布 APK 升级、运行状态真实性与隐私/日志审计。

阶段七代码基线为 `codex/phase6-integration`（阶段六 T1~T7 的集成分支）。阶段六 T8 真机样本与 T9 真机验收未完成时，可以开发阶段七 T1~T7，但阶段七最终验收必须引用阶段六 T8/T9 证据；否则阶段六/七状态只能写"待验证"。

### 1.2 角色定义

| 角色 | 责任 | 不得替代的角色 |
|---|---|---|
| 原生开发 | 修改 Kotlin、Android 配置、Room/迁移/备份/升级边界内的实现 | 不得代替证据角色宣称真机通过 |
| RN/契约开发 | 只使用 `Native.d.ts` 已声明方法完成页面接入；契约缺口独立提交 | 不得自行新增未声明 Native 方法 |
| 契约审查 | 检查升级/运行状态/迁移回滚相关类型、方法名和错误码 | 不得把代码存在误判为真机验收通过 |
| 证据角色 | 采集真机稳定性、升级、性能、日志和备份恢复证据 | 不得由开发对话兼任或合成真机证据 |
| 验收角色 | 按独立矩阵执行真机验收，记录通过、失败、未执行和阻塞 | 不得只根据开发自述填写通过 |

### 1.3 分支和提交规则

1. 每个任务从已确认的阶段七基线创建独立分支，分支名使用 `codex/phase7-<任务简称>`；不得在 `master` 或文档分支直接修改业务代码。
2. 一个任务的代码提交只包含该任务的允许文件。审查修正必须在同一任务分支追加中文提交。
3. 提交信息使用中文并包含任务编号，例如：`阶段七任务T1：修复测试基线并补纯函数测试`。
4. 涉及 `docs/types/Native.d.ts` 的契约变更必须独立成提交并先经契约审查批准，不得与业务实现混在同一提交。
5. 开发提交不得包含真机日志原文、完整包名之外的敏感信息、APK 签名密钥或用户数据样本；性能数据只提交抽样与口径。
6. 阶段七真机证据和验收报告分别由证据/验收角色提交，开发分支不得代为提交。

---

## 2. 阶段七范围与边界

### 2.1 阶段七允许交付

- 自动化测试基线修复与新增纯函数测试（解析器、金额、去重因素、优先级合并、CSV/XLSX 读取）。
- Room 迁移与事务集成测试（10→11 及既有迁移链）、崩溃恢复与唯一索引测试。
- 数据库迁移失败回滚：迁移前自动备份加密数据库文件，迁移异常回退到旧版本数据库并提示用户导出数据。
- 性能专项：10 万笔账单查询/列表/统计抽样，1 万行官方账单导入小于 30 秒的实测抽样。
- 自发布 APK 升级：版本号/版本码管理、应用内检查更新、APK SHA-256 校验、安装前签名一致性检查、下载互斥与临时文件清理。
- 运行状态真实化：无障碍、通知监听、悬浮窗、电池优化、数据库版本、导入孤儿批次恢复状态全部来自系统真实查询。
- 服务自愈：通知监听服务与无障碍服务被系统杀/断开后提供可见状态和重新授权路径。
- 隐私与日志审计：密码/截图/API Key/通知原文不落日志、不落库、不进崩溃上报；Sentry 脱敏与本地日志边界核查。

### 2.2 阶段七明确不做

| 后续能力 | 阶段七处理方式 |
|---|---|
| 银行短信、邮件账单 | 不接入；`sms` 枚举保持保留 |
| 跨设备同步、云端备份恢复 | 不实现；备份仍在用户选择的本地文件 |
| 多用户/多账本 | 不实现；单账本基线不变 |
| 热更新 JS Bundle | 不使用；升级只走 APK 自发布 |
| 新识别来源或新业务语义 | 不扩展；阶段七只收口阶段二~六功能 |
| iOS 适配 | 不实现；阶段七只验证 Android 主力设备 |

### 2.3 现有实现边界

以下类与契约在阶段七基线中已经存在，后续开发必须复用或修正，不得重复新建同职责类：

| 现有职责 | 当前类/契约 |
|---|---|
| 数据库构建与迁移 | `.../data/JotBillDatabase.kt`、`.../data/JotBillDatabaseProvider.kt` |
| 审计日志与 90 天清理 | `.../data/entity/AuditEntryEntity.kt`、`.../data/dao/AuditEntryDao.kt` |
| 备份创建/预览/覆盖恢复 | `.../backup/BackupManager.kt`、`BackupModule.kt` |
| 导入批次与孤儿恢复 | `.../importing/ImportEngine.kt`、`ImportUndo.kt` |
| APK 安装桥 | `.../utils/InstallApkModule.kt`、`InstallApkPackage.kt` |
| 更新检查与下载 | `jotbill-app/src/hooks/useAppUpdate.ts`、`src/services/update/client.ts`、`src/services/update/appVersion.ts` |
| 运行状态桥 | `.../runtime/RuntimeModule.kt`、`RuntimePackage.kt`、`jotbill-app/src/screens/settings/RuntimeStatusScreen.tsx` |
| 通知监听服务 | `.../notification/PaymentNotificationService.kt` |
| 无障碍服务 | `.../recognition/CheckoutAccessibilityService.kt` |
| 日志与崩溃上报 | `.../utils/SentryLogger.kt` |
| 既有单元测试 | `jotbill-app/android/app/src/test/`、`jotbill-app/__tests__/` |

---

## 3. 唯一 RN 契约与阶段七契约变更

阶段七需要在 T5/T6 立契约变更（独立提交，先经契约审查）：

```text
Runtime.getRuntimeHealth(): 补充真实状态字段，不得再返回硬编码 false
InstallApk.verifyApk(filePath: string, expectedSha256?: string): Promise<ApkVerification>
```

`ApkVerification` 至少包含：`packageName`、`versionCode`、`versionName`、`sha256`、`sha256Matched`、`signatureConsistent`。签名一致性以设备当前安装包（存在时）与待安装 APK 的签名证书 SHA-256 比较为准；未安装当前包时只返回待安装包自身签名有效。

除上述字段/方法外，阶段七不新增其他公开方法或事件。更新进度继续使用现有 Hook 状态，不新增事件。

---

## 4. 任务总览

阶段七共 9 个任务：7 个开发任务，2 个独立证据与验收任务。

| 编号 | 任务 | 角色 | 依赖 | 类型 |
|---|---|---|---|---|
| T1 | 测试基线修复与纯函数测试 | 原生开发 + RN/契约开发 | 阶段六基线 | 开发 |
| T2 | Room 迁移与事务集成测试 | 原生开发 + 契约审查 | T1 | 开发 |
| T3 | 数据库迁移失败回滚 | 原生开发 + 契约审查 | 阶段六基线 | 开发 |
| T4 | 性能索引与实测抽样 | 原生开发 + 证据角色 | 阶段六基线 | 开发+证据 |
| T5 | 自发布 APK 升级与签名校验 | RN/契约开发 + 原生开发 + 契约审查 | T3 | 开发 |
| T6 | 运行状态真实化与服务自愈 | 原生开发 + RN/契约开发 + 契约审查 | T5 | 开发 |
| T7 | 隐私与日志审计 | 原生开发 + RN/契约开发 + 证据角色 | T6 | 开发+证据 |
| T8 | 真机稳定性证据采集 | 证据角色 | T1~T7 审查通过 | 证据 |
| T9 | 阶段七真机验收 | 验收角色 | T1~T8 | 验收 |

---

## 5. 任务卡

<a id="task-t1"></a>

### T1. 测试基线修复与纯函数测试

**目标**：修复既有 3 个失败测试；为阶段五/六纯函数补自动化测试：`WechatBillAdapter`、`AlipayBillAdapter`、`CsvTableReader`、金额转分、类型映射、状态跳过、`FieldPriorityResolver`、`DuplicateGuard.similarFactors`。RN 侧补 `native/import.ts` 错误码归一化和更新 URL 校验测试。

**允许修改文件**：

- `jotbill-app/android/app/src/test/java/com/jotbill/app/**`（既有测试与新增测试）
- `jotbill-app/__tests__/**`
- 测试所需 `jotbill-app/android/app/build.gradle`（仅 testImplementation 依赖，若需）

**必须遵守**：

- 修复测试只能修改测试断言或测试实现，不得为通过测试改变生产语义。
- `LedgerRepositoryTest` 按阶段五 T3 语义修正 transfer 期望。
- 注解反射类测试改为不依赖本地 JVM 缺失的 Android stub；无法在本地 JVM 验证的 Room 注解迁移到 T2 集成测试。
- 纯函数测试必须覆盖失败样本，不能只测理想输入。

**提交要求**：中文提交信息 `阶段七任务T1：修复测试基线并补纯函数测试`。

**交接产物**：测试清单、修复说明、纯函数覆盖矩阵、未做真机验证声明。

<a id="task-t2"></a>

### T2. Room 迁移与事务集成测试

**目标**：新增 Room 集成测试：9→10 迁移后 `bills.import_record_id` 与 `import_batches` 结构正确；`ImportBatchDao` 唯一哈希索引生效；导入执行回滚不残留半批数据；审计 operator=batchId 反查可用。测试使用 `room-testing` 的 `MigrationTestHelper` 与内存 SQLite（若 SQLCipher 无法在本地 JVM 运行，改为 `androidTest` 并在交接物说明）。

**允许修改文件**：

- `jotbill-app/android/app/src/androidTest/**` 或 `src/test/**` 新增测试
- `jotbill-app/android/app/build.gradle`（测试依赖与 runner 配置）

**必须遵守**：

- 不修改生产迁移代码；发现缺陷反馈给对应任务分支修正。
- 集成测试必须能静态编译；能否在本地 JVM 执行要在交接物中区分。
- 不把真机数据库文件提交到仓库。

**提交要求**：中文提交信息 `阶段七任务T2：补Room迁移与事务集成测试`。

**交接产物**：测试用例清单、room-testing 版本、运行环境说明、未执行真机 instrumentation 的声明。

<a id="task-t3"></a>

### T3. 数据库迁移失败回滚

**目标**：在 `JotBillDatabaseProvider` 增加 `DatabaseMigrationGuard`：打开数据库前把当前加密数据库文件复制到私有备份路径；Room 迁移失败时关闭实例、恢复旧文件、删除半迁移文件并返回可识别错误；恢复成功后下次启动继续使用旧版本数据库并提示用户导出。保留上一份迁移前备份，新备份成功后清理更早备份。

**允许修改文件**：

- 新增 `jotbill-app/android/app/src/main/java/com/jotbill/app/data/DatabaseMigrationGuard.kt`
- `jotbill-app/android/app/src/main/java/com/jotbill/app/data/JotBillDatabaseProvider.kt`
- `jotbill-app/android/app/src/main/java/com/jotbill/app/data/JotBillDatabase.kt`（仅版本升级时按需）
- `jotbill-app/android/app/src/main/java/com/jotbill/app/runtime/RuntimeModule.kt`（暴露 lastMigrationRecovered 状态）

**必须遵守**：

- SQLCipher 数据库文件复制必须是字节级复制，不通过明文导出。
- 迁移失败恢复路径不得删除用户原有数据库；只有验证旧文件完整后才替换。
- 恢复状态只报告，不自动重试迁移。
- 本任务不新增公开迁移事件。

**提交要求**：中文提交信息 `阶段七任务T3：接入数据库迁移失败回滚`。

**交接产物**：状态机图、文件路径清单、失败注入方式、恢复后启动行为、未做真机验证声明。

<a id="task-t4"></a>

### T4. 性能索引与实测抽样

**目标**：核对 10 万笔账单查询路径与索引；补齐缺失索引（如 `(occurred_at, type, amount_minor)` 需要时）；为导入执行提供批次大小、行处理耗时和内存峰值采样口径；写 1 万行合成样本的本地 JVM 性能测试（合成样本只用于开发，不得标记为官方样本）。

**允许修改文件**：

- `jotbill-app/android/app/src/main/java/com/jotbill/app/data/entity/BillEntity.kt`（仅索引）
- `jotbill-app/android/app/src/main/java/com/jotbill/app/data/JotBillDatabase.kt`（仅版本升级与迁移）
- `jotbill-app/android/app/src/test/**` 性能测试
- `docs/qa/` 下性能口径说明（开发抽样，不是真机验收）

**必须遵守**：

- 索引增加必须走 Room 版本迁移，不允许只改实体不迁移。
- 合成性能样本不进入 T8/T9 官方证据目录。
- 性能抽样必须注明设备、数据量、批次大小、耗时与内存口径；缺真机数据时写"未执行"。

**提交要求**：中文提交信息 `阶段七任务T4：补齐性能索引与抽样测试`。

**交接产物**：索引 diff、迁移脚本、抽样口径表、未做真机验证声明。

<a id="task-t5"></a>

### T5. 自发布 APK 升级与签名校验

**目标**：把更新检查从旧后端 `/app-version/check` 改为只读更新清单 JSON（HTTPS）；下载后校验 SHA-256 与版本码；安装前调用 `InstallApk.verifyApk` 检查包名、版本、签名一致性；更新入口展示版本、更新日志、强制更新标记；下载/安装互斥与临时文件清理。

**允许修改文件**：

- `docs/types/Native.d.ts`（契约变更独立提交）
- `jotbill-app/src/services/update/appVersion.ts`、`client.ts`
- `jotbill-app/src/hooks/useAppUpdate.ts`
- `jotbill-app/android/app/src/main/java/com/jotbill/app/utils/InstallApkModule.kt`
- `jotbill-app/android/app/src/main/AndroidManifest.xml`（若权限/FileProvider 需要调整）
- `jotbill-app/src/config/env.ts`（更新清单地址配置）
- 相关设置页（仅展示更新状态的既有页面）

**必须遵守**：

- 更新清单必须 HTTPS；下载不允许重定向；URL 不得含用户名/密码/查询参数。
- SHA-256 不匹配时不得安装，临时文件必须清理。
- 签名比较只在同包名且当前应用已安装时执行；签名不一致必须阻断并提示手动卸载后安装。
- 版本码必须单调递增，不能只比较版本名。

**提交要求**：契约提交 `阶段七任务T5：登记APK升级校验契约`；实现提交 `阶段七任务T5：接入自发布APK升级与签名校验`。

**交接产物**：更新清单 schema、校验流程图、错误码表、签名比较逻辑说明、未做真机验证声明。

<a id="task-t6"></a>

### T6. 运行状态真实化与服务自愈

**目标**：`RuntimeModule.getRuntimeHealth` 返回真实状态：无障碍服务是否启用、通知监听是否授权、悬浮窗权限、电池优化忽略状态、孤儿导入批次清理状态；页面提供重新授权/跳转入口；通知服务和无障碍服务断开时写状态并可被运行状态页看到。

**允许修改文件**：

- `docs/types/Native.d.ts`（契约变更独立提交）
- `jotbill-app/android/app/src/main/java/com/jotbill/app/runtime/RuntimeModule.kt`
- `jotbill-app/src/native/runtime.ts`
- `jotbill-app/src/screens/settings/RuntimeStatusScreen.tsx`
- `jotbill-app/android/app/src/main/java/com/jotbill/app/notification/PaymentNotificationService.kt`（状态写入）
- `jotbill-app/android/app/src/main/java/com/jotbill/app/recognition/CheckoutAccessibilityService.kt`（状态写入）

**必须遵守**：

- 状态值必须来自系统 API 查询，不得继续硬编码 false。
- 服务自愈只做可见状态与跳转，不绕过用户授权。
- 不新增常驻后台任务；开机接收器继续只负责提示/拉起系统已授权服务。

**提交要求**：契约提交 `阶段七任务T6：登记运行状态真实化契约`；实现提交 `阶段七任务T6：接入运行状态真实化与服务自愈`。

**交接产物**：状态字段映射表、系统 API 清单、状态写入时机、未做真机验证声明。

<a id="task-t7"></a>

### T7. 隐私与日志审计

**目标**：核查并修正所有日志路径：导入密码、AI API Key、截图/Base64、通知原文、账单原文不得进入 Log、Sentry、崩溃上报、数据库或剪贴板；`SentryLogger` 增加统一脱敏入口；删除/替换调试 `console.log` 中敏感输出；为密码和 API Key 增加禁止匹配测试。

**允许修改文件**：

- `jotbill-app/android/app/src/main/java/com/jotbill/app/utils/SentryLogger.kt`
- 涉及日志/崩溃上报的 Kotlin 文件（按实际核查清单登记）
- `jotbill-app/src/utils/logger.ts` 及调用点
- `jotbill-app/android/app/src/test/**` 脱敏测试
- `jotbill-app/__tests__/**` 脱敏测试

**必须遵守**：

- 脱敏不能只替换首尾，必须整值不落日志。
- 测试用固定密码/密钥假值，不提交真实凭据。
- 不得为了审计把敏感数据先写入日志再删除。

**提交要求**：中文提交信息 `阶段七任务T7：接入隐私与日志脱敏审计`。

**交接产物**：敏感字段清单、修改点清单、禁止匹配测试结果、未做真机验证声明。

<a id="task-t8"></a>

### T8. 真机稳定性证据采集

**角色**：独立证据角色，不属于开发验收。开发者可提供采集清单，不能宣称样本已通过。

**依赖**：T1~T7 分支审查通过；需要指定设备、系统版本、应用版本。

**允许修改/新增文件**：

- `docs/qa/phase7/` 下样本、日志摘要、截图、录屏索引和采集报告
- 证据角色自己的中文采集报告和证据提交

**禁止触碰文件**：

- 不修改 Kotlin、TypeScript、`Native.d.ts` 或任务文档来"适配"证据
- 不提交未脱敏通知原文、完整卡号、真实姓名、密码或 API Key
- 不把手工合成性能数据标记为真机性能数据

**证据基线**：

| 场景 | 必需证据 |
|---|---|
| 10 万笔账单查询/列表滚动 | 设备/系统/耗时/内存抽样 |
| 1 万行账单导入 | 总耗时、批次进度、取消无残留 |
| 备份→清库→恢复 | 前后记录数与余额一致 |
| 数据库升级失败回滚 | 注入失败前后数据库文件与恢复提示 |
| 应用内检查更新→下载→SHA-256→签名一致安装 | 各步骤截图与最终版本码 |
| 服务被杀/断开后状态与重新授权 | 运行状态页前后截图 |
| 通知/无障碍关键页面不误触发 | 原有阶段四/六验收证据引用 |

**提交要求**：中文提交信息 `阶段七任务T8：提交真机稳定性证据样本`。

**交接产物**：证据索引、设备/系统/版本、脱敏说明、证据提交哈希。

<a id="task-t9"></a>

### T9. 阶段七真机验收

**角色**：独立验收角色。验收结果由证据和验收角色签署，开发对话不能自行勾选通过。

**依赖**：T1~T8；阶段六 T8/T9 证据也必须引用。

**允许修改/新增文件**：

- `docs/qa/phase7/` 下验收报告、测试矩阵和证据索引
- 验收角色的截图、UI dump、关键日志摘要和失败复现记录

**禁止触碰文件**：

- 不为通过验收修改业务源代码、契约或样本
- 不验证银行短信、云端同步等阶段七明确不做能力
- 不把开发分支的静态审查、构建结果或单元测试结果当作真机通过证据

**阶段七验收矩阵**：

| 场景 | 预期结果 | 必需证据 |
|---|---|---|
| 10 万笔账单列表/统计 | 基础查询可用，无明显卡死 | 设备与耗时 |
| 1 万行导入 | 小于 30 秒且可取消 | 批次与耗时 |
| 导入取消/失败 | 无半批残留，批次终态正确 | 计数对比 |
| 备份恢复演练 | 记录数与账户余额一致 | 前后对比 |
| 数据库迁移失败回滚 | 旧库可恢复，提示导出 | 文件与提示截图 |
| APK 更新安装 | SHA-256 与签名一致，升级后版本码正确 | 安装截图 |
| 通知/无障碍服务断开 | 状态页显示真实状态并可重新授权 | 前后截图 |
| 隐私日志 | 日志/崩溃上报无密码、密钥、通知原文 | 检索记录 |
| 阶段六导入验收矩阵 | 引用阶段六 T9 报告 | 报告链接/哈希 |

**验收报告必须逐项标记**：`通过`、`失败`、`未执行` 或 `阻塞`。未执行真机操作只能写"未执行"。

**提交要求**：中文提交信息 `阶段七任务T9：提交真机阶段七验收报告`。

**交接产物**：按矩阵逐项标记的验收报告、设备/应用版本、证据文件清单、失败与阻塞说明、验收提交哈希。

---

## 6. 不允许的捷径

- 用静态编译或单元测试通过替代真机验收。
- 用合成性能样本冒充真机性能证据。
- 为通过测试修改生产语义。
- 迁移失败恢复删除用户原数据库或明文导出 SQLCipher 数据。
- 升级下载不校验 HTTPS、SHA-256、包名、版本码或签名。
- 运行状态页继续返回硬编码状态。
- 密码、API Key、通知原文或截图进入日志、Sentry、崩溃上报、数据库或剪贴板。
- 阶段七开发分支代替 T8/T9 证据与验收提交。

---

## 7. 完成定义

开发任务 T1~T7 只有在以下材料齐全时才可交给审查：

- 分支、中文提交哈希、修改文件清单。
- 静态验证命令与结果（`tsc --noEmit`、`compileDebugKotlin`、可运行的测试范围）。
- 未做真机验证的明确声明。
- 契约变更 diff 与逐项说明（T5/T6）。
- 隐私审计敏感字段清单与禁止匹配测试结果。

阶段七 MVP 只有在 T8/T9 报告逐项非"失败"且引用证据提交哈希后才视为完成；开发对话不能自行签署。
