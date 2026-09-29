# JotBill 开发指南
> **状态**：[参考] —— 稳定
> 入口：`00-现状正本.md`

> 当前入口：阶段二数据层本地化。
>
> 阶段一 AI 能力验证已由项目负责人完成，本仓库后续开发不重复创建或运行阶段一测试工程。

## 文档优先级

发生冲突时按以下顺序处理：

1. `docs/types/Native.d.ts`：React Native 与 Android 原生层的唯一接口契约。
2. `docs/DECISIONS.md`：已经确认的产品和工程决策。
3. `docs/NEW_PROJECT_KICKOFF_V2.md`：目标架构、业务规则和验收标准。
4. `docs/tdd/02_room_schema.md`、`03_native_module.md`、`06_phase2_tasks.md`：阶段二技术实现细节。
5. `docs/MIGRATION_MAP.md`：LiteNote 文件迁移清单。

## 当前代码边界

- `jotbill-app/` 是 JotBill React Native 迁移基线，当前包名仍为 `com.jotbill.app`。
- `litenote-backend/` 只用于运行和核对 LiteNote 基线，不是 JotBill 的目标运行时。
- JotBill 的 Room、SQLCipher、Native Module 和本地识别链路尚未全部落地。
- 不得把旧后端 API 作为 JotBill 本地数据层的临时替代品。

## 开发前环境

- Node.js >= 18
- JDK 19
- Android Studio 和 Android SDK API 33+
- Git
- 可连接小米 17U / Android 16 的 adb 真机

检查基础环境：

```powershell
node --version
npm --version
java -version
adb version
```

阶段二开始前应确认 `java -version` 使用 JDK 19，并在真机上完成一次空白 Debug APK 构建和安装验证。

## 阶段二顺序

1. 按 `docs/tdd/06_phase2_tasks.md` 任务一建立 JotBill Android/RN 工程骨架，更新包名为 `com.jotbill.app`，加入 Room、SQLCipher、Hilt 和 Keystore 依赖。
2. 按 `docs/tdd/02_room_schema.md` 实现 Entity、DAO、数据库密钥派生和 Migration，先通过内存 Room 测试。
3. 按 `docs/tdd/03_native_module.md` 实现 Data、Recognition、AI、Runtime、Overlay 和 Backup 接口，并逐项对照 `docs/types/Native.d.ts`。
4. 将账单、分类、账户、预算、目标和报表页面的数据查询迁移到 Native Module。
5. 清理运行时对 NestJS、JWT、PostgreSQL 和旧热更新接口的依赖。
6. 通过阶段二验收后，再进入结算页检测和支付结果匹配。

## 不变量

- 金额在持久化层使用 Long 分；RN 契约使用 `amountMinor`。
- Room 内部时间使用毫秒时间戳；RN 账单和证据的 `occurredAt` 使用 ISO-8601 字符串。
- 所有写操作必须在原生事务中完成，并遵守版本号、幂等键和审计记录约束。
- 真实截图、API Key、签名文件和未脱敏账单不得进入 Git。
- AI 结果不能绕过支付证据和待确认规则直接创建正式账单。

## 当前基线命令

以下命令只用于运行迁移基线：

```powershell
npm run install:all
npm run backend:test
npm run backend:test:e2e
npm run mobile:test
npm run mobile:build:dev
```

基线命令通过不代表 JotBill 阶段二完成；阶段二必须增加 Room、Bridge 和真机验收证据。
