# 招财猫猫

本地优先的 Android 智能记账应用。它读懂支付通知、购物结算页和银行短信，自动把每一笔消费记进你的账本；所有数据只保存在你自己的手机上。

## 项目简介

招财猫猫是一款完全运行在 Android 设备本地的个人记账应用。账单、账户、分类、预算、规则和 AI 配置全部保存在本机加密数据库中，不需要注册账号，也不依赖云端后端。

它要解决的是「记账太麻烦」：支付完成之后，应用会从多个渠道自动收集交易信号——支付通知、购物 App 的结算页与支付结果页、银行短信、导入的微信/支付宝官方账单，以及手动或 AI 记录的账目。证据完整的交易直接入账；证据不完整或存在冲突的交易进入「待确认中心」，由你一键确认、忽略或补全。同一笔交易即使被多个渠道同时看到，也只会记一次。

- Android 7.0+（minSdk 24），当前版本 **2.1.3**（versionCode 109）
- 本地存储，不联网也能完成记账、查账与统计；AI 对话、语音识别等在线能力需自行配置服务商
- 代码以 Kotlin 原生层为数据与识别核心，React Native 负责界面

## 功能特性

### 自动记账：六路信号，一个账本

| 信号渠道 | 工作方式 |
|---|---|
| 支付通知 | 监听微信、支付宝的支付 / 退款 / 转账通知，提取金额、订单号、时间与商户 |
| 结算页观察 | 无障碍服务只读识别购物 App 的结算页与支付结果页：淘宝、天猫、京东、美团、拼多多、滴滴、抖音、小红书；得物、唯品会、苏宁易购支持支付结果页 |
| 银行短信 | 通过系统通知读取银行短信，**不申请短信权限**；默认关闭，可在渠道设置中开启 |
| 页面规则引擎 | 8 个记账渠道的可配置规则，按「包名 / 类名」匹配页面，抽取字段后自动入账或转待确认 |
| 账单导入 | 导入微信、支付宝官方账单文件（CSV / XLSX），退款行单独核验 |
| AI 与手动 | AI 对话、语音、拍照生成待确认账单；也可以随时手动记账 |

- **全局静默记账**：开启后自动记账不再弹窗，只保留待确认与渠道开关
- **提醒可控**：待确认交易支持悬浮窗与系统通知提醒，均可关闭
- **可选后台守护**：应对部分厂商系统的后台清理，保证识别服务存活

### 同一笔不双记，不同笔不误并

自动记账最难的不是识别，而是去重。招财猫猫采用分层归并：

- **订单号强键归并**：同一订单号且金额 / 类型一致，2 分钟内跨渠道合并
- **同包同额 15 秒窗、跨包同额 15 秒窗**：命中后合并为一张卡并标记疑似重复，由用户一次决策
- **L1–L4 四级判重**：订单号查重、事件幂等键、导入批次号、金额 + 时间 + 辅助因子相似提示
- **N-2 强证据吸收**：通知携带订单号且账户可解析时，24 小时内吸收同名待确认卡并直接入账
- **转账跨腿查重**：同包、同额、同收款人的两条转账信号合并为一笔
- **订单号冲突护栏**：同单号但金额 / 类型不一致时绝不合并，宁可多出一张可删的卡

所有归并都会在卡片上留痕（记录被合并的信号键），方便追溯；疑似重复的卡必须二次确认后才会入账。

### 待确认中心

- 自动化的缓冲带：账户不明、规则冲突、疑似重复、语义不安全的交易都会进入这里
- 冲突原因以中文可读文案展示，不再是一串内部错误码
- 支持多选与批量忽略（2.1.3 新增）
- 确认后走正常入账流程，并继续参与后续判重

### 账户管理

- **证据分级自动建卡**：根据短信 / 通知中的银行名称与卡尾号，同银行、同尾号且卡类型相容时自动建立银行卡
- **来源账户与实际记账账户分开**：退款优先回到原支付账户
- **银行与品牌图标库**：账户列表、账单、首页使用同一套图标口径
- **余额一致性抽检**：启动时核对账户余额与流水，发现漂移在账户页提示

### 预算与财务目标

- 六种周期：月、周、双周、季、年、单次；月 / 周 / 双周支持自定义起始日
- 退款按发生期冲减预算（净额口径），收入不参与预算
- 预算进度、超额提醒与提醒掩码
- 首页预算卡片可开关；支持财务目标与净资产视图

### 统计报表

- 日 / 月 / 年视图与收支分析
- 分类趋势、资产视图、全部账单与账单详情
- 分类管理支持自定义分类与图标

### AI 助手

- 多服务商：通义千问、智谱 GLM、SiliconFlow，以及任意 OpenAI 兼容的自定义服务商
- 文字、语音、图片多模态输入
- AI 可通过工具查询账单、汇总统计，或生成**待确认**账单——不会直接修改或删除已有账单
- 本地真实分类清单会注入提示词，降低乱分类
- API Key 只保存在设备 Keystore，不写入备份

### 数据与隐私

- Room + SQLCipher 全库加密，密钥由 Android Keystore 保护
- 无障碍服务只读：不点击、不输入、不截图；密码 / 验证码 / 付款码等敏感页面零文本采集
- 备份可选加密，支持预览校验与覆盖恢复；备份不包含 API Key、待确认交易、AI 对话历史与导入批次，恢复前并排显示「备份记录数 / 本机记录数」
- 支持 CSV 导出
- 发布包禁止明文网络（release 仅 HTTPS），debug 包保留本地回环调试能力

### 外观与体验

- 四套主题：积木蓝、薄荷账本、落日橙、墨黑，均含浅色 / 深色
- 首页按天分组，记账表单滑动切换类型、账户弹窗选择
- 账单列表统一分类图标，状态颜色与百分比口径全端一致

## 技术架构

| 层级 | 技术 |
|---|---|
| UI | React Native 0.81 · TypeScript · React Navigation 7 · TanStack Query 5 · MobX · React Native Paper |
| 图表与图标 | react-native-gifted-charts · react-native-svg · lucide-react-native |
| 本地数据 | Kotlin · Room · SQLCipher 4.5.4 · Android Keystore |
| 系统能力 | NotificationListenerService · AccessibilityService · Foreground Service · 悬浮窗 · 开机自启 |
| AI | 原生 SSE 流式客户端，OpenAI 兼容协议；服务商与 Key 由用户配置 |
| 构建 | Gradle 8.14.3 · JDK 17+（验证环境 19）· Android SDK 35 |

界面与原生层通过 Native Module 桥接；账单、账户、分类、规则、预算、AI 会话全部落在本地数据库，应用运行时不需要后端服务。

## 项目结构

```
.
├── jotbill-app/                    # Android 应用（当前主要代码）
│   ├── src/
│   │   ├── screens/                # 首页、记账、报表、待确认、设置、AI
│   │   ├── components/             # 通用组件与图表
│   │   ├── services/               # 通知、热更新、语音、版本更新
│   │   ├── native/                 # RN ↔ Kotlin 桥接封装
│   │   └── theme/                  # 四套主题与设计 token
│   └── android/app/src/main/java/com/jotbill/app/
│       ├── recognition/            # 结算页识别、支付匹配、归并去重
│       ├── notification/           # 支付通知与银行短信解析
│       ├── bookkeeping/            # 规则引擎与入账
│       ├── pending/                # 待确认中心
│       ├── rules/                  # 判重、字段优先级、订单号归一
│       ├── data/                   # Room + SQLCipher、仓库层
│       ├── budget/                 # 预算周期、进度与提醒
│       ├── importing/              # 微信 / 支付宝账单导入
│       ├── ai/                     # AI 对话与工具调用
│       ├── backup/                 # 备份与恢复
│       ├── overlay/                # 待确认悬浮窗
│       ├── keepalive/              # 识别保活
│       ├── security/               # Keystore
│       └── hotupdate/              # 热更新
├── docs/                           # 现状规格、决策日志与开发文档
├── litenote-backend/               # 历史遗留后端，不参与当前应用运行
└── THIRD_PARTY_LICENSES.md         # 第三方许可证登记
```

> 包名 `com.jotbill.app` 为历史沿用，不影响应用功能。

## 开发与构建

### 环境要求

- Node.js >= 20
- JDK 17+（本项目验证使用 19）
- Android SDK 35 / Build Tools 35.0.0
- Android Studio（可选）

### 安装依赖

```bash
cd jotbill-app
npm install
```

### 开发运行

```bash
cd jotbill-app
npm run android:dev   # 启动 Metro 并安装 debug 包
```

应用的核心记账流程不依赖后端；`.env.development` 中的 `API_BASE_URL` 仅用于可选的版本更新 / 热更新通道。

### 构建安装包

```bash
cd jotbill-app
npm run build:dev     # Debug APK
npm run build:prod    # Release APK，产物 android/app/build/outputs/apk/release/app-release.apk
```

Release 签名说明：未配置正式签名时，构建会回退到仓库内的 `debug.keystore`（仅供内部安装验证）；正式签名在 `~/.gradle/gradle.properties` 中配置 `RELEASE_STORE_FILE`、`RELEASE_STORE_PASSWORD`、`RELEASE_KEY_ALIAS`、`RELEASE_KEY_PASSWORD`。

一键发版脚本 `jotbill-app/scripts/release.js` 会在递增版本、打包 APK 后上传到更新服务并创建 Git 标签，使用前需要在 `jotbill-app/.env.production` 中配置 `API_BASE_URL`。

### 测试

```bash
# Kotlin 单元测试
cd jotbill-app/android
./gradlew :app:testDebugUnitTest

# JavaScript / TypeScript 测试
cd jotbill-app
npm test
```

## 文档

- [现状规格](docs/CURRENT_SYSTEM.md)：识别、归并与入账链路的唯一现状说明
- [文档导航](docs/README.md)：全部开发文档的分层索引
- [开发指南](docs/DEVELOPMENT.md) / [测试指南](docs/TESTING.md)：环境与验证流程
- [决策日志](docs/DECISIONS.md)：判重、字段锁定等历史裁定
- [AI 协作说明](CLAUDE.md)：工程纪律、构建验证命令与改动约定

## 版本

当前版本 **2.1.3**（versionCode 109）。近期更新：

- 待确认中心支持多选与批量忽略
- 预算支持自定义周期与退款净额口径

## 开源协议

MIT。第三方依赖与素材的许可状态见 [THIRD_PARTY_LICENSES.md](THIRD_PARTY_LICENSES.md)。
