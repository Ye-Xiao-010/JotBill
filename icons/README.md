# 图标库

本目录专门存放项目图标资产，与 App 代码解耦，便于复用、预览和后续扩充。

## 目录结构

```text
icons/
├── brand/                   微信 / 支付宝品牌图标
│   ├── wechat.svg           #07C160
│   ├── wechat.png           512×512 透明底
│   ├── alipay.svg           #1677FF
│   └── alipay.png           512×512 透明底
├── banks/                   银行图标（23 家）
│   ├── <code>.svg           方形银行徽标源文件
│   ├── <code>.png           512×512 透明底
│   ├── banks.json           code / 名称 / 简称 / 别名映射
│   └── overview.png         总览
├── categories/              分类彩色图标（Fluent Emoji Flat）
│   ├── <code>.svg           彩色矢量源文件
│   ├── <code>.png           512×512 透明底
│   ├── categories.json      21 个分类 → code / lucide / fluent 映射
│   ├── overview.png         21 个分类总览
│   └── style-compare.png    三套彩色风格对比
└── accounts/                账户相关预览/候选
    ├── account-icons-overview.html/png   品牌 / lucide / emoji 三层账户图标总览
    └── cash-candidates.html/png          现金账户图标候选
```

## 说明

- `brand/`：品牌图标来自 Simple Icons 的单色形状，颜色按官方品牌色烘焙。
- `banks/`：SVG 来自 [icongo/bank-logos](https://github.com/icongo/bank-logos)（MIT）；
  银行名称与标识是各银行商标，仅用于账户识别，不暗示合作或背书。
- `categories/`：彩色图标来自 Microsoft Fluent Emoji（Flat），MIT License；
  经 Iconify `fluent-emoji-flat` 图标集获取 SVG。映射见 `categories/README.md`。
- `accounts/`：账户图标的视觉参考与现金图标候选，不代表已完成代码接入。

## 接入状态

当前 App 仍使用原有的 lucide 线性分类图标、原生 `ic_lucide_*` 图标与
`ic_brand_*` 品牌资源；本目录是资产与映射源，**未修改 App 代码或原生资源**。
后续接入时按需选择 RN `Image` / Android drawable 渲染，并保留 SVG + 512×512 PNG。

新增图标时，建议同时保留 SVG 与 512×512 透明底 PNG，并在对应 JSON 映射中登记。
