# 招财猫猫 ·「柔光 (soft)」UI 设计规格

> **状态**：设计稿评审中（未改应用代码）｜**分支**：`feat/ui-soft`（切自 `V2`）｜**日期**：2026-11-23
> **交互原型**：[prototype.html](./ui-soft/prototype.html)（浏览器直接打开即可点击体验 6 个核心页面 + 浅/深色切换）

---

## 1. 定位与边界

- **视觉方向**：现代柔和风（类似 iOS / Material You 的干净记账质感）——大圆角、去粗描边、多层柔和阴影与表面层次、金额作为视觉焦点。
- **与现有主题的关系**：作为**第 5 套可选主题**注册为 `soft`（展示名：`柔光`），包含浅色 / 深色两套完整色板。
  - 默认主题保持为现有 `blocks`（积木蓝）不变；
  - 现有 4 套积木风主题（`blocks` / `mint` / `sunset` / `ink`）外观、描边、阴影和交互**不做任何变更**。
- **当前阶段范围**：只产出可交互 HTML 高保真原型与本设计规格文档，**不改动 `jotbill-app/` 业务代码**，待视觉与交互拍板后再按分批路线图实施。

---

## 2. 设计原则

| 原则 | 说明 | 在招财猫猫中的体现 |
|---|---|---|
| **层次代替描边** | 去掉 2–4px 黑描边和实心硬偏移阴影，用「底色 → 卡片 → 浮层」三级明度阶梯 + 柔和模糊阴影表达层级 | 浅色卡片 0px 描边 + 轻阴影；深色模式因阴影不可见，改用 1px 低对比发丝线 (`#232731`) |
| **金额是主角** | 每屏只保留一个最大数字；所有金额使用等宽数字 (`tabular-nums`)，小数部分与 `¥` 降一级字号 | 首页 Hero 本月支出 36/700；记账页输入区 44/700；流水行右对齐 16/600 |
| **自动化看得见** | 把「本地多源自动记账 + 待确认缓冲」做成可感知的信任体验 | 自动入账流水带「自动」浅底徽章；待确认卡片常驻首页暖色提示；冲突原因以中文短句直述 |
| **拇指友好** | 高频动作落在屏幕下半区，触控热区 ≥ 40dp | 悬浮胶囊底栏 + 中央渐变 FAB；记账改为底部弹层 + 内置 `+ / −` 计算键盘 |

---

## 3. 设计 Token 规格

### 3.1 色彩 Token（完整映射 `ThemePalette`）

> 金额语义遵守全仓硬约束：**收入 = 绿系（100°–170°）、支出 = 红系（≤15° 或 ≥340°）**。
> 以下所有 7 组关键文字/背景对比度均已按 `jotbill-app/__tests__/themeIntegrity.test.tsx` 同款公式实测通过 **WCAG AA（≥ 4.5:1）**。

| `ThemePalette` 字段 | 用途 | `softLight` | `softDark` |
|---|---|---|---|
| `primary` | 主色：主按钮 / 选中态 / FAB | `#4F46E5` | `#8B8DFB` |
| `primaryLight` | 主色浅底：选中底 / 胶囊底 / 信息卡 | `#EEF0FF` | `#23264A` |
| `primaryDark` | 主色加深：按下态 / 浅底上的文字 | `#4338CA` | `#A5A7FF` |
| `secondary` | 辅助色（AI / 扩展标签） | `#8B5CF6` | `#A78BFA` |
| `accent` | 招财金点缀（预算进度 / 新标记 / 目标） | `#F5A524` | `#F7B955` |
| `success` / `successLight` | 成功状态 / 浅底 | `#16A34A` / `#E3F6EA` | `#4ADE80` / `#143222` |
| `warning` | 警示（待确认 / 账户缺失） | `#F59E0B` | `#FBBF24` |
| `error` | 错误 / 危险操作（删除、批量忽略） | `#DC2626` | `#F87171` |
| `pink` / `lime` / `cyan` / `orange` | 分类与图表扩展色 | `#EC4899` / `#84CC16` / `#0EA5E9` / `#F97316` | `#F472B6` / `#A3E635` / `#38BDF8` / `#FB923C` |
| `background` | 页面背景底色 | `#F4F5F9` | `#0E1014` |
| `surface` / `cardBackground` | 卡片主表面 | `#FFFFFF` | `#171A21` |
| `surfaceMuted` | 次级表面（分段槽 / 键盘按键 / 输入框） | `#EEF0F6` | `#1E222B` |
| `textPrimary` | 主文字 | `#12141A` | `#F3F4F7` |
| `textSecondary` | 次文字 | `#454A57` | `#C5C9D3` |
| `textTertiary` | 辅助文字 | `#646A78` | `#8D93A1` |
| `textQuaternary` | 占位符 / 禁用文字 | `#A0A5B1` | `#5C6270` |
| `divider` | 列表内部分隔线 | `#ECEEF3` | `#252932` |
| `stroke` / `cardBorder` | 发丝线 / 深色卡片描边 | `#E4E7EE` | `#232731` |
| `strokeLight` | 弱分隔线 / 虚线参考线 | `#D5D9E2` | `#2A2F3A` |
| `income` / `expense` | 收入 / 支出金额 | `#15803D` / `#DC2626` | `#4ADE80` / `#F87171` |
| `incomeLight` / `expenseLight` | 收支徽章浅底 | `#E3F6EA` / `#FDECEC` | `#143222` / `#3A1A1E` |
| `incomeStrong` / `expenseStrong` | 收支徽章深字 | `#166534` / `#B91C1C` | `#4ADE80` / `#F87171` |
| `onPrimary` | 主色块上的主文字 | `#FFFFFF` | `#0E1014` |
| `onPrimaryMuted` | 主色块上的次文字 | `rgba(255,255,255,0.85)` | `rgba(14,16,20,0.78)` |
| `onPrimarySubtle` | 主色块上的弱文字 | `rgba(255,255,255,0.72)` | `rgba(14,16,20,0.62)` |
| `onPrimaryDivider` | 主色块上的分隔线 | `rgba(255,255,255,0.22)` | `rgba(14,16,20,0.20)` |
| `shadow` / `shadowDark` | 柔和阴影基底色 | `#12141A` / `#12141A` | `#000000` / `#000000` |
| `overlay` / `overlayLight` | 弹层遮罩 | `rgba(18,20,26,0.38)` / `rgba(18,20,26,0.20)` | `rgba(0,0,0,0.60)` / `rgba(0,0,0,0.36)` |
| `gradientPrimaryStart` / `End` | Hero 渐变卡片起止色 | `#5850EC` / `#6052E5` | `#3B36B8` / `#5B4FD9` |

#### WCAG AA 实测对比度（对应 `themeIntegrity.test.tsx` 全 7 项）

| 检测项 | `softLight` | `softDark` | 标准 |
|---|---:|---:|---|
| `textPrimary` / `background` | **16.90 : 1** | **17.31 : 1** | ≥ 4.5 |
| `textTertiary` / `background` | **4.97 : 1** | **6.18 : 1** | ≥ 4.5 |
| `income` / `surface` | **5.02 : 1** | **9.99 : 1** | ≥ 4.5 |
| `expense` / `surface` | **4.83 : 1** | **6.29 : 1** | ≥ 4.5 |
| `onPrimary` / `primary` | **6.29 : 1** | **6.62 : 1** | ≥ 4.5 |
| `incomeStrong` / `incomeLight` | **6.33 : 1** | **7.99 : 1** | ≥ 4.5 |
| `expenseStrong` / `expenseLight` | **5.66 : 1** | **5.64 : 1** | ≥ 4.5 |

> 附：`primaryDark` / `primaryLight`（浅底按钮/标签）实测浅色 **6.98:1**、深色 **6.61:1**；Hero 渐变白字最浅处 (`#6052E5`) 实测 **5.47:1**。

---

### 3.2 形状、层级与字重 Token（主题态差异）

| 维度 | 积木风 (`blocks / mint / sunset / ink`) | 柔光 (`soft`) |
|---|---|---|
| **圆角** | `small 8` / `medium 12` / `card 16` / `button 12` | `tag 8` / `tileSm 12` / `button 14` / `card 20` / `hero/sheet 28` / `pill 999` |
| **卡片描边** | `2–3px solid colors.stroke` | 浅色 `0px`；深色 `1px solid colors.cardBorder` |
| **阴影 / 高度** | 0 模糊实心偏移阴影 (`offset 2–6, radius 0`) | 4 级柔光高度：`e0`（平铺）/ `e1`（卡片轻阴影）/ `e2`（浮层・底栏）/ `e3`（Hero 与 FAB 品牌投影） |
| **按压反馈** | `BrutalPressable` 平移沉入 `(offset, offset)` | `scale(0.97)` + 底色微加深（120ms 轻弹） |
| **字重阶梯** | 偏向 800 / 900 粗黑 | 标题 `700`、小标题/金额 `600`、正文 `500`、辅助 `400`；金额强制 `fontVariant: ['tabular-nums']` |
| **图标线宽** | `strokeWidth={2.5}` | 常规 `2.0`，选中态 `2.3` |

---

## 4. 核心页面设计说明（6 屏对照原型）

1. **01 首页 (`DashboardScreen`)**
   - 顶栏：左「11月账本 ⌄」切换月份，右「搜索」「待确认铃铛（带红点）」。
   - Hero 主卡：28px 大圆角靛蓝渐变，展示本月支出（带眼睛隐藏开关 + 明细胶囊入口）、收入与结余并排；预算进度条直接内嵌在 Hero 底部半透明容器内（由「设置 → 显示 → 首页预算卡片」开关控制），减少首页卡片堆叠感。
   - 待确认提示卡：有待确认交易时常驻 Hero 下方，暖琥珀浅底 + 数量角标，一键直达待确认中心。
   - 快捷入口：4 个 52×52 大圆角白底块（导入账单 / AI 记账 / 预算 / 自动记账）。
   - 近期交易：按天分组（`今天` 靛蓝浅底徽章、`昨天/周几` 中性灰徽章 + 当日收支小计），每条流水用低饱和彩色圆角图块承托分类图标，自动识别流水追加「⚡自动」小徽章。
   - 底部导航：悬浮胶囊 + 毛玻璃底，选中态为 52×30 浅底药丸；中央 `+` 为 54×54 圆形渐变 FAB，向上微凸带 4px 背景描边与品牌色投影。

2. **02 记一笔 (`CreateBillScreen`)**
   - 形态：从底部升起的 30px 圆角弹层，顶部「关闭 + 滑动分段（支出 / 收入 / 转账 / 还款）+ 语音」。
   - 金额区：右对齐 44/700 等宽大字 + 算式回显行（支持 `+ / −` 连续计算），支出红、收入绿。
   - 元信息条：横向滚动胶囊（日期时间 / 账户 / 备注 / 拍票据）。
   - 中间区域：支出/收入显示 5 列大图标分类网格（选中反白高亮 + 同色柔光投影）；转账/还款切换为「转出 → 转入」双账户卡片 + 快捷互换按钮，还款明确标注「限负债账户」与待还金额。
   - 底部键盘：内置 4×4 柔和数字键盘，含退格、`+`、`−`、「再记（保存并继续）」和主色「保存」。

3. **03 统计 (`ReportsScreen`)**
   - 顶栏右侧「收支 / 资产」胶囊分段切换。
   - **收支视图**：期间切换（`2026年11月` + `月 / 年`）+ 三列汇总卡（支出 / 收入 / 结余）+ 趋势面板（月视图内「趋势柱图 / 热力日历」二合一，柱图含日均虚线与大额封顶点标记，点击任意日期/月份实时刷新读数）+ 支出构成卡（圆环图 + 图例 + 分类进度条排行）。
   - **资产视图**：深碳色大圆角净资产主卡（含环比涨跌胶囊、资产/负债拆分、右上角折线走势）+ 「资产账户 / 负债账户」分组卡。

4. **04 待确认中心 (`ReviewScreen`)**
   - 顶部 3 枚轻指标卡：「待确认」「今日自动入账」「AI 状态（绿点空闲）」。
   - 待确认卡片：顶部「来源胶囊（支付通知 / 银行短信 / 页面规则 / AI 对话）+ 时间」，中部「分类图块 + 商户 + 大金额」，下方「原因提示框（暖琥珀=账户不明/规则冲突；靛蓝浅底=疑似重复/AI 生成）」，再下方明确列出「付款账户（缺失时警示高亮）+ 分类」，底部「忽略 / 编辑 / 确认入账」。
   - 多选批量忽略：点右上角「多选」进入勾选态，隐藏单卡操作区，底部弹出悬浮操作条「已选 X / Y + 红色忽略选中」。

5. **05 AI 助手 (`AIChatScreen`)**
   - 顶栏标题旁展示当前服务商胶囊（绿点 + 通义千问），右侧历史会话入口。
   - 欢迎引导 + 3 条快捷提问卡（查花销 / 对话记账 / 环比对比）。
   - 对话气泡支持两类结构化卡片：账单汇总表、待确认预览卡（附「去待确认中心 →」按钮，恪守「AI 不直接改账本」的约束）。
   - 底部悬浮胶囊输入栏：拍照识别 + 文本输入 + 语音 + 圆形主色发送按钮。

6. **06 我的 (`SettingsScreen`)**
   - 顶部「本地账本」卡片：标注「数据仅保存在此设备 · 全库加密」+ 记账天数 / 账单数 / 账户数 3 项概览。
   - 完整保留现有 5 组设置项（财务管理 / 识别与自动化 / 权限与提醒 / AI 与数据 / 显示），用 32×32 圆角彩色图块替代原糖果黑框图标。
   - 「显示」组内含 5 套主题可视化预览卡（`柔光` 带「新」徽章，其余 4 套直观呈现积木黑框风格），外加深色模式与首页预算卡片开关。

---

## 5. 现有代码耦合分析与架构方案

### 5.1 现状测量（2026-11-23 全仓扫描 `jotbill-app/src`）

- `borderWidth.(thin|medium|thick)`：**319 处**（静态导入自 `theme/spacing.ts`）
- `...shadow.*`：**56 处**（实心偏移硬阴影）
- `colors.(stroke|cardBorder)`：**316 处**
- `BrutalPressable`：**11 个文件**
- 现状限制：`ThemeProvider` 和 `useStyles(createStyles)` 目前只向组件传递 `colors: ThemeColors`，形状（圆角、描边、阴影、按压效果）写死在静态常量中。因此仅靠在 `colors.ts` 新增色板无法把粗描边和硬阴影变成柔光风格。

### 5.2 推荐架构（零破坏、可渐进迁移）

1. **新增 `jotbill-app/src/theme/shape.ts`**：定义 `ThemeShape` 接口及两套预设 `brutalShape`（数值与原 `spacing.ts` 100% 一致）与 `softShape`（本规格 §3.2 的圆角/描边/柔光阴影/按压策略）。
2. **扩展 `ThemeProvider`**：
   - `themes` 新增 `soft: { light: softLight, dark: softDark }`，`THEME_LABELS` 新增 `soft: '柔光'`。
   - Context 新增 `styleVariant: 'brutal' | 'soft'` 与 `shape: ThemeShape`（`soft` 映射 `softShape`，其余 4 套映射 `brutalShape`）。
3. **平滑升级 `useStyles`**：
   ```ts
   type StyleCreator<T> = (colors: ThemeColors, shape: ThemeShape, isDark: boolean) => T;
   ```
   因为 TypeScript 允许函数忽略多余参数，**所有现有 `(colors) => StyleSheet.create(...)` 调用零改动即可通过编译**；迁移页面时再按需解构第二个参数 `shape` 即可。
4. **原生阴影注意（Android）**：React Native 在 Android 上对 `shadowRadius` 支持有限（主要走 `elevation`，且 9+ 支持 `shadowColor`）。柔光卡片在 Android 上用低 `elevation: 2` + `shadowColor: 'rgba(18,20,26,0.16)'` + 1px 极淡边界（或纯白卡片对 `#F4F5F9` 底色的明度差）即可获得干净的柔光层次，不会发脏发灰；Hero 渐变卡引入 `react-native-svg`（项目已安装）做背景渐变，无需新增原生依赖。

---

## 6. 建议落地分批路线图（待确认后执行）

| 批次 | 范围 | 主要文件 | 验证方式 |
|---|---|---|---|
| **P0 主题底座** | 注册 `soft` 色板 + 新增 `shape.ts` + 扩展 `ThemeProvider` / `useStyles` + 设置页出现「柔光」选项 | `theme/colors.ts`, `theme/shape.ts`, `providers/ThemeProvider.tsx`, `hooks/useStyles.ts`, `__tests__/themeIntegrity.test.tsx` | `npm test`（10 块色板 AA 全绿） |
| **P1 通用组件与导航** | 适配悬浮胶囊底栏 + 柔光基础组件（`Card` / `Button` / `BrutalPressable` / `SegmentedControl` / `Badge` / `Modal`） | `navigation/AppNavigator.tsx`, `components/ui/*` | `npx tsc --noEmit` + `npm test` + 真机切主题对比 |
| **P2 核心 5 屏** | 首页、我的、待确认中心、统计、记一笔（与 AI 助手）接入 `shape` 与柔光专属布局 | `screens/dashboard/*`, `screens/SettingsScreen.tsx`, `screens/review/*`, `screens/reports/*`, `screens/finance/CreateBillScreen.tsx`, `screens/ai/*` | 真机浅/深 × 柔光/积木蓝回归 |
| **P3 二级页面收尾** | 账户、预算、目标、账单详情、全部账单、导入、规则等页面将静态 `borderWidth`/`shadow` 替换为 `shape` | `screens/settings/*`, `screens/finance/*`, `screens/BudgetScreen.tsx`, `screens/FinancialGoalScreen.tsx` | 全页走查 + `npm test` |

---

## 7. 待确认决策项

落地前请你拍板以下 4 点：

1. **主色调**：柔光主色当前用 **靛蓝 `#4F46E5` + 招财金 `#F5A524`**，是否合眼缘？还是想调整为其他色系（如鼠尾草绿 / 暖橘 / 海洋蓝）？
2. **记账交互**：柔光主题下是否启用原型中的「底部弹层 + 内置 `+ / −` 计算键盘」，还是先保持现有整页表单、只做视觉换肤？
3. **统计结构**：是否采用原型中把「日 / 月 / 年」收口为「月（内含趋势/日历切换）/ 年」的布局，还是保持现有三子页？
4. **落地节奏**：确认后你希望先落地到 **P0 + P1 + 首页/我的** 快速上真机看效果，还是直接推进到 **P2 全部核心屏**？
