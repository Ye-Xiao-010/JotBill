# JotBill

JotBill 是基于 LiteNote 前端改造的本地优先 AI 自动记账应用。目标运行时使用 Android 原生 Room 数据层，直接连接用户配置的多模态 AI 服务商，不在设备端运行 NestJS 后端。

## 开发状态

当前仓库同时包含两部分：

- `litenote-mobile-app/` 和 `litenote-backend/` 是 LiteNote 的迁移基线，便于运行现有界面和核对旧字段。
- JotBill 的 Room、SQLCipher、识别链路和 Native Module 正按阶段迁移，尚未全部实现。

JotBill 的目标架构、业务规则和完成标准以以下文档为准：

- [项目启动基线](docs/NEW_PROJECT_KICKOFF_V2.md)
- [决策日志](docs/DECISIONS.md)
- [迁移映射表](docs/MIGRATION_MAP.md)
- [Native 接口契约](docs/types/Native.d.ts)
- [TDD 文档](docs/tdd/)

其中 `docs/types/Native.d.ts` 是 React Native 与 Android 原生层之间的唯一接口边界；其他文档中的接口示例必须与它保持一致。
<p align="center">
 <img src="docs/screenshots/banner.png" alt="LiteNote Banner" width="100%" />
</p>

## 当前基线能力

### AI 智能记账

通过自然语言与 AI 助手对话，自动创建、查询、删除和统计账单，取决了你提供了哪些tool。支持多模型切换（Claude / OpenAI / DeepSeek / Qwen），保留原生llm能力，不局限于记账。
<div align="center">
  <img src="docs/screenshots/textChat1.png" width="48%" alt="AI 对话 1" />
  <img src="docs/screenshots/textChat2.png" width="48%" alt="AI 对话 2" />
</div>
<div align="center">
  <img src="docs/screenshots/aiConfig1.png" width="48%" alt="AI 模型配置 1" />
  <img src="docs/screenshots/aiConfig2.png" width="48%" alt="AI 模型配置 2" />
</div>

### 语音记账

按住录音按钮说出消费内容，AI 自动识别并创建账单。
<p align="center">
  <img src="docs/screenshots/asrChat.gif" alt="语音记账演示" width="70%" />
  <br />
</p>

### 拍照记账

拍摄或上传小票、发票，AI 识别自动解析金额、分类和日期,支持多张图片。
<p align="center">
  <img src="docs/screenshots/mutPhotoChat.png" width="80%" alt="拍照记账" />
</p>

### 支付通知自动记账

监听微信、支付宝等应用的支付通知，自动创建对应账单，无需手动录入。
<div align="center">
  <img src="docs/screenshots/autoNote1.gif" alt="支付通知自动记账 1" width="48%" />
  <img src="docs/screenshots/autoNote2.gif" alt="支付通知自动记账 2" width="48%" />
</div>

### 统计与预算

多维度收支统计、分类趋势图表、预算追踪与超额预警。
<div align="center">
  <img src="docs/screenshots/chart1.png" width="32%" alt="统计报表1" />
  <img src="docs/screenshots/chart2.png" width="32%" alt="统计报表2" />
  <img src="docs/screenshots/chart3.png" width="32%" alt="统计报表3" />
</div>

<p align="center">
  <img src="docs/screenshots/homeCard.gif" alt="主页卡片动态" width="70%" />
</p>




## 技术栈

| 层级 | 技术 |
|------|------|
| **目标本地层** | Kotlin · Room · SQLCipher · Keystore |
| **移动端基线** | React Native 0.81 · TypeScript · React Navigation 7 · React Query 5 |
| **目标 AI 视觉** | qwen-vl-plus（主用）· GLM-4V-Flash（备用）· Qwen3-VL-8B-Instruct（开发调试） |
| **迁移基线后端** | NestJS 10 · Prisma 6 · PostgreSQL · JWT · Swagger（仅用于核对和运行旧基线） |

## 项目结构

```
lite-note-app/
├── litenote-backend/        # NestJS REST API 后端
│   ├── src/
│   │   ├── auth/            # JWT 认证
│   │   ├── bills/           # 账单 CRUD + 统计
│   │   ├── categories/      # 分类管理
│   │   ├── accounts/        # 多账户管理
│   │   ├── budgets/         # 预算追踪
│   │   ├── ai/              # AI 对话 + 工具调用 + ASR
│   │   └── app-version/     # 移动端版本管理
│   └── prisma/              # 数据库 Schema
├── litenote-mobile-app/     # React Native 移动应用
│   ├── src/
│   │   ├── screens/         # 18 个屏幕
│   │   ├── components/      # UI / 业务 / 图表组件
│   │   ├── services/        # HTTP · API · 语音 · 通知
│   │   ├── hooks/           # 自定义 Hooks
│   │   └── theme/           # Light/Dark/System 主题
│   └── android/             # Android 原生层
└── package.json             # Monorepo 根配置
```

## 快速开始

下面的命令用于运行当前 LiteNote 迁移基线，不代表 JotBill 的最终运行架构。

### 环境要求

- Node.js >= 18
- PostgreSQL（本地或云托管，如 [Neon](https://neon.tech)、[Supabase](https://supabase.com)）
- Android Studio（移动端开发）
- JDK 19

### 1. 安装迁移基线依赖

```bash
npm run install:all
```

### 2. 配置迁移基线后端

```bash
cp litenote-backend/.env.example litenote-backend/.env
```

编辑 `litenote-backend/.env`，至少配置：

```env
JWT_SECRET=your-random-secret-key
DATABASE_URL="postgresql://user:password@localhost:5432/litenote"
```

### 3. 初始化迁移基线数据库

```bash
npm run backend:prisma:migrate:dev
```

### 4. 启动迁移基线后端

```bash
npm run backend:start:dev
```

后端默认运行在 `http://localhost:3006`，API 文档位于 `http://localhost:3006/api-docs`。

### 5. 启动迁移基线移动端

移动端默认连接 `http://localhost:3006`（在 `litenote-mobile-app/.env.development` 中配置）。

```bash
npm run mobile:android:dev
```

## 环境变量参考

以下环境变量只适用于当前 LiteNote 迁移基线。JotBill 目标运行时将由 Native Module 管理本地数据和 AI 配置，不依赖 `DATABASE_URL` 或后端 API 地址。

### 后端 (`litenote-backend/.env`)

| 变量 | 必填 | 说明 |
|------|------|------|
| `PORT` | 否 | 服务端口，默认 `3006` |
| `JWT_SECRET` | **是** | JWT 签名密钥 |
| `DATABASE_URL` | **是** | PostgreSQL 连接字符串 |
| `API_BASE_URL` | 否 | 外部访问地址（留空则自动从请求获取） |
| `ASR_API_URL` | 否 | 语音识别 API 地址（兼容 OpenAI Whisper 协议） |
| `ASR_API_KEY` | 否 | 语音识别 API Key |
| `ASR_MODEL` | 否 | 语音识别模型名称 |

### 移动端 (`litenote-mobile-app/.env.development`)

| 变量 | 说明 |
|------|------|
| `API_BASE_URL` | 后端 API 地址 |
| `SENTRY_DSN` | Sentry 错误监控 DSN（留空则不初始化） |
| `SENTRY_DSN_ANDROID` | Android 原生层 Sentry DSN（留空则不初始化） |
<div align="center">
  <img src="docs/screenshots/log.png" width="48%" alt="日志" />
  <p>方便埋点定位一些刁钻问题</p>
</div>
## JotBill 开发顺序

### 阶段一：AI 能力验证（已完成）

阶段一已由项目负责人完成。本次开发不重复创建测试工程、不重复采集样本，也不重复调用三家视觉模型。阶段一文档和样本规范仅作为已完成能力的追溯依据。

阶段一结果以项目负责人保存的验证报告为准，真实截图和 API Key 不提交到 Git。

### 阶段二及以后

按 [阶段二任务](docs/tdd/06_phase2_tasks.md) 和 [迁移映射表](docs/MIGRATION_MAP.md) 依次实现 Room 数据层、Native Module、结算页识别、支付结果匹配、规则引擎和数据导入。

每个阶段完成后必须同时通过文档中的单元测试、Room/Bridge 集成测试和真机验收；不能仅以旧 LiteNote 页面能启动作为完成标准。

## 常用命令（迁移基线）

```bash
# 后端
npm run backend:start:dev          # 热重载开发
npm run backend:prisma:migrate:dev # 创建数据库迁移
npm run backend:prisma:studio      # Prisma Studio GUI

# 移动端
npm run mobile:android:dev         # Android 开发环境
npm run mobile:build:dev           # 构建 Debug APK
npm run mobile:build:prod          # 构建 Release APK
```

## CI/CD（迁移基线）

- `.github/workflows/build-android.yml` 当前构建 LiteNote 移动端基线。
- `.github/workflows/deploy-backend.yml` 当前部署 LiteNote 后端基线。
- JotBill 完成本地化后，后端部署工作流应删除或明确标记为历史迁移工具，不能作为目标发布流程。
<div align="center">
  <img src="docs/screenshots/update.png" width="48%" alt="自动升级" />
</div>


## License

MIT
