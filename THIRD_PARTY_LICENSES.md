# JotBill 第三方许可证登记

本文件用于记录 JotBill 复用代码、依赖和资源的许可证状态。发布 APK 前必须完成依赖级别的核验，并随发布物保留所需版权和许可证文本。

## 已确认

| 来源 | 用途 | 许可证 | 当前状态 |
|---|---|---|---|
| LiteNote | React Native 页面、组件和部分业务代码的迁移基线 | MIT | 项目文档已记录；复用代码时需保留原版权声明 |
| JotBill 自有代码 | 本项目新增的 Kotlin、TypeScript 和文档 | MIT（沿用项目声明） | 随项目发布 |

## 发布前必须核验

- `litenote-mobile-app/package-lock.json` 中所有生产依赖及其传递依赖。
- Android Gradle 依赖，包括 Room、SQLCipher、Hilt、OkHttp 和 WorkManager。
- `docs/screenshots/` 中的 Logo、字体、图标和演示素材是否具有可再分发授权。
- 阶段二 CSV/XLSX/ZIP 导入依赖及其许可证兼容性。
- 所有许可证文本、NOTICE 和原作者版权声明是否随 APK 或源码发布。

## 禁止纳入仓库

- API Key、服务端密钥、签名密钥和生产环境配置。
- 未确认授权的商标、Logo、字体和图片。
- 真实未脱敏截图、通知、短信和账单数据。

本文件中的“已确认”只表示项目决策记录已有结论，不替代正式的依赖扫描或法律审核。

