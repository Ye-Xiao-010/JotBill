# 分类彩色图标（Fluent Emoji Flat）

用于默认分类的彩色实体图标。每个图标提供：

- `<code>.svg`：源文件（Fluent Emoji Flat，彩色矢量）
- `<code>.png`：512×512 透明底导出
- `categories.json`：21 个默认分类 → 图标文件的映射
- `overview.png`：21 个分类总览
- `style-compare.png`：Fluent / OpenMoji / Twemoji 三套风格对比

## 文件命名

`food / transport / shopping / entertainment / housing / medical / education /
communication / daily / social / travel / pet / other / salary / part-time /
investment / bonus / red-packet / refund / fee`

共 20 个独立图标。支出的「其他」与收入的「其他」共用 `other`，因此 `categories.json`
有 21 条分类映射、20 个图标文件。

## 映射字段

```json
{
  "categoryId": "default-category-expense-food",
  "name": "餐饮",
  "type": "expense",
  "code": "food",
  "lucide": "utensils",
  "fluent": "steaming-bowl"
}
```

- `code`：对应 `<code>.svg` / `<code>.png`
- `lucide`：当前分类种子使用的线性图标名，便于后续替换/回退
- `fluent`：Fluent Emoji 中的原始图标名，便于更新资产

## 来源与授权

- 图标来源：Microsoft Fluent Emoji（Flat 风格），MIT License。
- 通过 [Iconify](https://iconify.design/) 的 `fluent-emoji-flat` 图标集获取 SVG。
- 可商用，建议在产品文档中保留 Fluent Emoji / MIT 来源说明。

## 接入状态

当前 App 仍使用 lucide 线性分类图标；本目录是彩色图标资产与映射，**未修改 App 代码或原生资源**。
后续接入时可把 `categories.json` 作为 `categoryId → code` 的映射源，按需选择 RN `Image`
或 Android drawable 渲染。
