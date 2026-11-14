package com.google.android.accessibility.selecttospeak

import com.jotbill.app.recognition.CheckoutAccessibilityService

/**
 * 无障碍采集服务的**注册入口组件**（W-1：微信节点树可见性）。只做继承，不含任何采集逻辑。
 *
 * ## 为什么单独开一个入口类
 *
 * 微信 8.0.52+ 只对「已知的系统读屏类服务」暴露页面节点树，判据是**无障碍组件的类名**
 * 是否落在 `…selecttospeak…` / `…talkback…` 这类系统读屏服务的位置上。
 * 用本应用自己的类名注册时，微信读到的是 0×0 空根（日志 `children=1, pageTextLength=0`）。
 *
 * 这一点在 2026-10-28 的真机日志里有直接对应：`OrderHandlerUI`（微信支付订单处理页）
 * 起来了，但 `pkg=com.tencent.mm … children=1, pageTextLength=0` —— 事件到了、节点是空的。
 * **节点读不到时规则写得再对也没用**，这一层比 mipay 归因的「基类 key 打不中子类」更靠前。
 *
 * 把注册组件的类名放到与系统「随选朗读」一致的位置，只解决「节点可见性」这一层兼容性问题。
 * 实测依据：ControlECO 同一份采集代码对照实验（微信 8.0.79 / Android 16 / HyperOS，
 * 系统读屏全关、只授权自己）：旧类名 `collect empty pkg=com.tencent.mm`；
 * 换本入口类名后会话列表 457 节点 / 91 行、钱包 144 节点、银行卡详情 30 节点。
 *
 * ## 它不带来任何额外能力
 *
 * - 不改变 `applicationId`，不改任何业务包名，不申请系统签名 / 系统 UID / 隐藏 API；
 * - **不声明** `android:isAccessibilityTool`（那是真正的「伪装成无障碍工具」，刻意不做）；
 * - 系统无障碍设置里显示的名称、图标与说明仍是本应用自己（`@string/app_name` +
 *   `@string/accessibility_service_description`），不冒称系统或 Google 官方服务；
 * - 事件接收、节点读取、页面解析、规则引擎与入账链路**全部**由父类
 *   [CheckoutAccessibilityService] 承担；本类不重写任何方法，APK 内只有这一套采集逻辑。
 *
 * ## 迁移注意（真机必读）
 *
 * 换了注册组件名后，系统侧 `enabled_accessibility_services` 里记录的还是旧组件
 * `…/recognition.CheckoutAccessibilityService` ⇒ **用户必须去系统无障碍设置里重新开启一次**。
 * HyperOS 上 `adb shell am force-stop` 也会清空无障碍授权（ControlECO 实测），排查「服务不在线」
 * 时先确认授权还在。
 *
 * ## 尚未验证（如实记录）
 *
 * 上述实测只覆盖了「节点可见性」这一层（会话列表 / 钱包 / 银行卡详情）；
 * **微信支付成功页那一轮没有走**。本改动对支付结果页是否同样有效，需真机跑一笔才能确认。
 */
class SelectToSpeakService : CheckoutAccessibilityService()
