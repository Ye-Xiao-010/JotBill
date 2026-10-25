# JotBill 图标体系（2026-10-25 定稿）

> 适用范围：付款确认弹窗（原生 View）与 RN 侧分类/账户渲染。
> 决策人：LXF（2026-10-24/25 标注图反馈 + 裁定）。
> 关联提交：`0a80e72`（RN 分类 lucide + 后端种子）、本批（原生弹窗接入 + 账户品牌）。

## 一、三层图标体系（回退链）

```
品牌图标（账户：微信/支付宝，固定品牌色）
  ↕ 未匹配
lucide 线性图标（分类按 icon 名；账户按 subtype；strokeWidth 2.5，颜色随文字色）
  ↕ 未匹配
emoji 文字（本地首启种子 / 用户自建数据，原样渲染）
```

原则：**数据驱动** —— icon 字段存什么就渲染什么层，任何一层匹配不上自动降级，
不存在"数据到位还要改代码"的耦合。用户自建分类/账户的 emoji 永远有效。

## 二、分类图标

| 层 | 数据 | 渲染 |
|---|---|---|
| lucide | 后端种子 13 枚（`0a80e72`）：utensils / bus / shopping-bag / gamepad-2 / pill / book-open / house / circle-ellipsis / banknote / trophy / chart-line / laptop / circle-dollar-sign | 原生 `ic_lucide_*` vector drawable（PopupIconAssets 按 icon 名映射）；RN `CategoryIcon` 组件 |
| emoji | Android 本地首启种子（`DefaultDataInitializer`）与自定义分类 | 原样文字 |

**注意**：两套种子并存 —— 后端（litenote-backend，lucide 名）与 Android 本地（emoji）。
用户执行 `prisma db push` + 重跑 `POST /categories/init-defaults` 并同步后，App 内分类
icon 变为 lucide 名，原生弹窗自动升级为矢量图标；此前弹窗显示 emoji（现状不变）。

## 三、账户图标

| 优先级 | 匹配 | 图标 | 颜色 |
|---|---|---|---|
| 1 | 账户 id（default-account-wechat / default-account-alipay）或名称包含"微信/支付宝" | 品牌品牌：`ic_brand_wechat`（#07C160）/ `ic_brand_alipay`（#1677FF） | 品牌色烘焙进资源，**不随选中变色** |
| 2 | subtype：cash → `banknote`；e_wallet → `wallet`；credit_card → `credit-card` | lucide 线性 | 跟随文字色（选中蓝 #1D4ED8 / 常态 #1A1A1A） |
| 3 | 其他（自定义账户） | 用户 emoji 文字 | — |

品牌来源：Simple Icons（simpleicons.org，CC0）单色形状 + 官方品牌色填充；
lucide 本身不含品牌图标（设计原则排除品牌）。自用无商标风险；若上架分发需遵循各家品牌规范。

## 四、资产生成与扩展

**lucide vector drawable 生成**（一次性脚本，已在 2026-10-25 执行）：
数据源 `jotbill-app/node_modules/lucide-react-native@0.563.0/dist/esm/icons/<name>.js`
（`["path"|"line"|"circle"|"rect", {attrs}]` 数组），转换为 `res/drawable/ic_lucide_<name>.xml`：

- `path` → `<path android:pathData="{d}">`
- `line` → `M{x1},{y1}L{x2},{y2}`
- `circle` → 两段圆弧 `M{cx},{cy}m-{r},0a{r},{r} 0 1,0 …`
- `rect` → 圆角矩形 pathData（h/v + 四段弧）
- 统一 `strokeColor #FF000000` + `strokeWidth 2.5`（与 RN `CategoryIcon` 同参）+ `fillColor #00000000`
- 运行时 `Drawable.setTint(文字色)` 着色 —— 选中态变色时由
  `selectCategory` / `selectAccount` 里的 `view.tag`(资源 id) 重着色

**新增一个分类图标**（后端种子加新 lucide 名时）：从 node_modules 提取 →
生成 `ic_lucide_<name>.xml` → `PopupIconAssets.categoryIcons` 加一行 → RN
`CategoryIcon` 的映射加一行。**新增一个账户品牌**：Simple Icons 取 SVG →
`ic_brand_<name>.xml`（fillColor 烘焙品牌色）→ `PopupIconAssets` 两个品牌 map 加行。

## 五、代码接线点

| 位置 | 职责 |
|---|---|
| `overlay/PopupIconAssets.kt` | 全部映射与 `apply()`（16dp compound drawable + 间距 6dp + tint；品牌跳过 tint） |
| `PendingConfirmOverlayManager.createCategoryView` | 分类 chip 双模渲染，`tag` 存资源 id |
| `PendingConfirmOverlayManager.createAccountView` | 账户 chip 双模渲染 |
| `selectCategory` / `selectAccount` | 选中态变色时按 tag 重着色 |

RN 侧：`components/business/CategoryIcon.tsx`（lucide + emoji 回退，9 处渲染点）。
**账户品牌在 RN 侧尚未接入**（各账户渲染点独立，为后续项；本批只覆盖原生弹窗）。

## 六、验证

- `compileDebugKotlin` + `testDebugUnitTest` 233/0/0 全绿
- 真机（MIUI 4a6e283b，release 包）：弹窗分类/账户 chips 图标人工核验
- 渲染对照：viz 会话目录 `payment-popup-annotator.html`（品牌/lucide/emoji 三态可点标注）
