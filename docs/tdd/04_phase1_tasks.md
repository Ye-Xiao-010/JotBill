# 04. 阶段一任务拆分

> 技术实现文档(TDD)第四部分
>
> 关联文档:NEW_PROJECT_KICKOFF_V2.md 第19节阶段一、01_ai_provider_adapter.md
>
> 作用:将"AI 能力验证"拆为可执行的具体任务,含文件清单、依赖、测试用例和验收脚本
>
> 状态:阶段一已由项目负责人完成。本文件保留实现和验收参考,后续开发不重复执行阶段一。

---

## 1. 阶段一目标

**验证目标**:真实购物结算页截图能否稳定返回平台、商户和金额。

**最小实现范围**:
- 不需要无障碍服务
- 不需要通知监听
- 不需要 Room 数据库
- 不需要 RN 前端
- 只需要一个 Kotlin 测试程序:手动选图 → 调用 AI → 输出结果

---

## 2. 任务清单

### 任务 1:创建测试项目骨架

**文件清单:**

| 文件 | 说明 |
|---|---|
| `app/build.gradle` | 配置:Kotlin、OkHttp、协程、Android 13 |
| `app/src/main/AndroidManifest.xml` | 最小权限:INTERNET |
| `app/src/main/java/com/jotbill/test/MainActivity.kt` | 简单 UI:选图按钮 + 结果显示 |
| `app/src/main/res/layout/activity_main.xml` | 布局:按钮 + 图片预览 + JSON 结果文本 |

**依赖:**

```gradle
dependencies {
    implementation "org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1"
    implementation "com.squareup.okhttp3:okhttp:4.12.0"
    implementation "androidx.activity:activity-ktx:1.9.0"
    implementation "androidx.lifecycle:lifecycle-runtime-ktx:2.8.0"
}
```

### 任务 2:实现 Prompt 模板

**文件清单:**

| 文件 | 说明 |
|---|---|
| `ai/PromptManager.kt` | System Prompt + User Prompt + 版本号 |

**实现内容:** 直接复制 [01_ai_provider_adapter.md](01_ai_provider_adapter.md) 第3节的 Prompt 文本。

### 任务 3:实现图片预处理

**文件清单:**

| 文件 | 说明 |
|---|---|
| `ai/ImagePreprocessor.kt` | 压缩 + Base64 转换 |

**实现内容:**
- 接收 Uri → 解码为 Bitmap
- 压缩为 JPEG 质量 85,最大边 1920px
- 转换为 Base64(不含 data URL 前缀)
- 释放 Bitmap

### 任务 4:实现响应校验器

**文件清单:**

| 文件 | 说明 |
|---|---|
| `ai/AiResponseValidator.kt` | JSON 解析 + 字段校验 |

**实现内容:**
- 从 AI 返回的 content 中提取 JSON(可能包含 markdown 代码块,需提取)
- 校验 confidence 必填
- 校验 amountMinor 为正整数(若存在)
- 裁剪字符串长度
- 返回 `AiRecognitionResult` data class

### 任务 5:实现三个适配器

**文件清单:**

| 文件 | 说明 |
|---|---|
| `ai/AiProviderAdapter.kt` | 接口定义 |
| `ai/OpenAICompatibleAdapter.kt` | 基类(公共逻辑) |
| `ai/QwenAdapter.kt` | 通义千问(主用) |
| `ai/GlmAdapter.kt` | 智谱 GLM(备用) |
| `ai/SiliconFlowAdapter.kt` | 硅基流动(调试) |

**实现内容:** 直接复制 [01_ai_provider_adapter.md](01_ai_provider_adapter.md) 第4-5节的代码结构。

### 任务 6:实现主测试 UI

**文件清单:**

| 文件 | 说明 |
|---|---|
| `MainActivity.kt` | 完整测试界面 |

**UI 功能:**

```
┌─────────────────────────────────┐
│  JotBill AI 阶段一验证            │
├─────────────────────────────────┤
│                                 │
│  [选择图片]  (图片预览区域)       │
│                                 │
│  服务商: [Qwen] [GLM] [Silicon] │
│  API Key: [______________]      │
│                                 │
│  [发送识别请求]                  │
│                                 │
│  结果:                          │
│  {                              │
│    "amountMinor": 9900,         │
│    "platform": "京东",           │
│    "merchant": null,            │
│    "confidence": 0.95           │
│  }                              │
│                                 │
│  耗时: 3.2s  Token: 1500/200   │
│                                 │
│  [保存结果到日志]                │
└─────────────────────────────────┘
```

### 任务 7:实现结果日志记录

**文件清单:**

| 文件 | 说明 |
|---|---|
| `util/TestLogger.kt` | 结果记录到本地文件 |

**日志格式(CSV):**

```csv
timestamp,provider,model,imageName,amountMinor,platform,merchant,productDescription,confidence,latencyMs,tokensIn,tokensOut,success,errorMessage
2026-07-31T10:30:00,qwen,qwen-vl-plus,jd_checkout_01.jpg,9900,京东,,手机壳×1,0.95,3200,1500,200,true,
```

---

## 3. 测试用例

### 3.1 测试样本要求

阶段一需要至少 **10 张真实结算页截图**,覆盖:

| # | 平台 | 场景 | 商户预期 | 金额预期 |
|---|---|---|---|---|
| 1 | 京东 | 电商单品 | 可空 | 有 |
| 2 | 京东 | 电商多品 | 可空 | 有 |
| 3 | 淘宝 | 电商单品 | 可空 | 有 |
| 4 | 美团 | 外卖 | 有 | 有 |
| 5 | 美团 | 到店扫码 | 有 | 有 |
| 6 | 拼多多 | 电商单品 | 可空 | 有 |
| 7 | 滴滴 | 出行 | 可空 | 有 |
| 8 | 饿了么 | 外卖 | 有 | 有 |
| 9 | 淘宝 | 电商含优惠 | 可空 | 有 |
| 10 | 美团 | 外卖第二场景 | 有 | 有 |

样本采集方案见 [05_test_samples.md](05_test_samples.md)。

### 3.2 对每张样本执行的三家对比

对每张样本,分别用三个服务商识别,记录:
- 金额是否正确
- 平台是否正确
- 商户是否正确(仅外卖/到店场景)
- 置信度
- 延迟
- Token 用量
- 是否报错

### 3.3 验收标准

| # | 验收项 | 通过标准 |
|---|---|---|
| 1 | 三家 API 连通性 | 三家都能返回 success=true |
| 2 | OpenAI 兼容格式 | 三家至少两家正确返回 JSON |
| 3 | 金额准确率(主用 Qwen) | 8/10 张正确(≥80%) |
| 4 | 金额准确率(备用 GLM) | 7/10 张正确(≥70%) |
| 5 | 平台准确率(主用) | 8/10 张正确 |
| 6 | 商户场景差异 | 外卖场景 3/4 张提取到商户;电商场景允许为空 |
| 7 | 核心字段合法性 | 10/10 张返回正整数 amountMinor 和非空 platform |
| 8 | 延迟 | 主用模型中位数 ≤ 5 秒 |
| 9 | 图片压缩 | 压缩后准确率 ≥ 原图的 90% |
| 10 | 成本估算 | Qwen 单次成本 ≤ 0.05 元 |

### 3.4 验收失败的处理

| 失败项 | 处理方案 |
|---|---|
| 1-2 失败 | 检查 API Key 和地址,确认 OpenAI 兼容格式 |
| 3 失败(金额 < 80%) | 调整 Prompt(强调"应付/实付"字段),或换主用模型为 GLM |
| 5 失败(平台 < 80%) | 调整 Prompt(增加平台识别引导) |
| 6 失败(商户) | 调整 Prompt(明确外卖必填),或接受当前表现 |
| 7 失败(核心字段) | 调整 Prompt(强调结算页平台和应付/实付金额字段),或更换视觉模型 |
| 8 失败(延迟 > 5s) | 调整 timeout 或换更快的模型 |
| 9 失败(压缩后准确率下降) | 提高压缩质量到 90 或分辨率到 2560px |

---

## 4. 执行步骤

### Step 1:创建项目(1个任务)
- 创建 Android 项目,配置 Gradle 依赖
- 确认能在小米17u上运行

### Step 2:实现核心代码(4个任务)
- PromptManager
- ImagePreprocessor
- AiResponseValidator
- 三个 Adapter

### Step 3:实现测试 UI(1个任务)
- 选图、配置 API Key、发送请求、显示结果

### Step 4:采集样本(1个任务)
- 在真机上采集 10 张结算页截图(见 05_test_samples.md)

### Step 5:执行测试(1个任务)
- 对 10 张样本 × 3 家服务商 = 30 次识别
- 记录结果到 CSV

### Step 6:分析结果(1个任务)
- 计算准确率/延迟/成本
- 对照验收标准判断是否通过
- 若不通过,按失败处理方案调整

### Step 7:输出验证报告
- 汇总三家对比数据
- 确认主备策略
- 决定是否进入阶段二

---

## 5. 项目结构

```
phase1-test/
├── app/
│   ├── build.gradle
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/com/jotbill/test/
│       │   ├── MainActivity.kt
│       │   ├── ai/
│       │   │   ├── PromptManager.kt
│       │   │   ├── ImagePreprocessor.kt
│       │   │   ├── AiResponseValidator.kt
│       │   │   ├── AiProviderAdapter.kt
│       │   │   ├── OpenAICompatibleAdapter.kt
│       │   │   ├── QwenAdapter.kt
│       │   │   ├── GlmAdapter.kt
│       │   │   └── SiliconFlowAdapter.kt
│       │   └── util/
│       │       └── TestLogger.kt
│       └── res/
│           └── layout/activity_main.xml
├── samples/                    (测试样本图片)
│   ├── jd_checkout_01.jpg
│   ├── jd_checkout_02.jpg
│   ├── taobao_checkout_01.jpg
│   ├── meituan_waimai_01.jpg
│   ├── meituan_dadian_01.jpg
│   ├── pdd_checkout_01.jpg
│   ├── didi_checkout_01.jpg
│   ├── eleme_waimai_01.jpg
│   ├── taobao_checkout_02.jpg
│   └── meituan_waimai_02.jpg
├── results/                    (测试结果)
│   └── test_results.csv
└── build.gradle
```

---

## 6. API Key 配置

阶段一测试时,三家 API Key 在测试 UI 中手动输入,不持久化:

| 服务商 | 申请地址 | 模型名 |
|---|---|---|
| 通义千问 | https://bailian.console.aliyun.com/ | qwen-vl-plus |
| 智谱 GLM | https://open.bigmodel.cn/ | glm-4v-flash |
| 硅基流动 | https://cloud.siliconflow.cn/ | Qwen/Qwen3-VL-8B-Instruct |

**注意:**
- 智谱 GLM-4V-Flash 当前阶段一验证可用,具体价格以服务商当前页面为准
- 硅基流动 Qwen3-VL-8B-Instruct 已在阶段一验证可用,具体价格以服务商当前页面为准
- 通义千问需要付费,但单次成本约 0.01-0.02 元,10 次测试约 0.2 元

---

## 7. 风险与应对

| 风险 | 概率 | 影响 | 应对 |
|---|---|---|---|
| qwen-vl-plus 已下线 | 中 | 主用模型不可用 | 按 DECISIONS.md 重新验证可用的多模态模型 |
| GLM 免费政策变化 | 低 | 备用模型需付费 | 成本极低(单次<0.02元),可接受 |
| 三家都不兼容 OpenAI 格式 | 低 | 需写多个适配器 | 已在适配器设计中预留差异处理 |
| 结算页识别准确率 < 80% | 中 | 需调整 Prompt 或换模型 | 阶段一的目的就是验证这点 |
| 图片压缩后文字模糊 | 低 | 识别准确率下降 | 提高压缩质量或分辨率 |
| 网络延迟过高 | 低 | 影响测试体验 | 在 WiFi 环境下测试 |
