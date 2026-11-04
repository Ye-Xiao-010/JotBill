package com.jotbill.app.recognition

/**
 * 识别层包名白名单 —— **包名的单一事实源**（B2 收敛）。
 *
 * ## 为什么收敛在这里
 *
 * 此前包名边界散落三处各写一份：`CheckoutPageDetector` 的两个常量、
 * `PaymentResultNormalizer.SUPPORTED_PACKAGES`、`BookkeepingChannels.CHANNELS`。
 * 本对象收口为唯一出处，三处全部改为引用/校验：
 * - `CheckoutPageDetector.DEFAULT_SUPPORTED_SHOPPING_PACKAGES` = [SHOPPING_PACKAGES]；
 * - `CheckoutPageDetector.DEFAULT_SUPPORTED_PAYMENT_PACKAGES` = [PAYMENT_PACKAGES]；
 * - `PaymentResultNormalizer.SUPPORTED_PACKAGES` = [NOTIFICATION_PACKAGES]；
 * - `BookkeepingChannels.CHANNELS`（规则引擎渠道表）通过 `ChannelInfo.kind` 与本
 *   对象的集合一一对应，**一致性由 `BookkeepingChannelsTest` 锁定**（渠道表只覆盖
 *   有规则资产的包，是本白名单的真子集视图 ⇒ 无法编译期互相派生，用测试防漂移）。
 *
 * ## 扩展纪律
 *
 * **改包名只改这里**；调用方（RN/桥接层）仍不得传入任意包名 —— 原
 * `CheckoutPageDetector` 的"包名边界不能由调用方扩展"约束继续成立，只是边界
 * 的定义位置移到了本对象。
 */
internal object RecognitionPackages {

    /**
     * 购物应用·核心档（CORE）：进结算页观察腿（collect → 角色判定 → 结算门禁）
     * **且**进站内结果页扫描腿（B1）。页面角色词表（结算类名/浏览兜底等）按这批
     * 包的真机 dump 校准过。
     */
    val SHOPPING_PACKAGES_CORE: Set<String> = setOf(
        "com.taobao.taobao",
        "com.tmall.wireless",
        "com.jingdong.app.mall",
        "com.sankuai.meituan",
        "com.xunmeng.pinduoduo",
        "com.sdu.didi.psnger",
        "com.ss.android.ugc.aweme",
        "com.xingin.xhs"
    )

    /**
     * 购物应用·降级档（RESULT_ONLY）：B2 新增（得物/唯品会/苏宁易购）。
     *
     * **只进站内结果页扫描腿**（`CheckoutPageDetector.detect` 对它们返回
     * `RESULT_PAGE_ONLY_PACKAGE`，不进结算页观察/会话语义）——这批包的页面
     * 角色词表未经真机校准，全量放开会搅浑结算页门禁与敏感锁存判定；
     * 而支付成功页的语义（`paymentResultTerms` + 金额）跨 App 通用，先吃到
     * B1 站内结果页腿的收益。词表校准后再考虑升 CORE。
     */
    val SHOPPING_PACKAGES_RESULT_ONLY: Set<String> = setOf(
        "com.shizhuang.duapp",      // 得物
        "com.achievo.vipshop",      // 唯品会
        "com.suning.mobile.ebuy"    // 苏宁易购
    )

    /** 全部购物包：事件归属推断（resolveEffectiveShoppingPackage）与敏感页锁存用。 */
    val SHOPPING_PACKAGES: Set<String> = SHOPPING_PACKAGES_CORE + SHOPPING_PACKAGES_RESULT_ONLY

    /**
     * 支付工具包：支付结果页文本解析腿（observePaymentResultIfApplicable）。
     * B2 新增云闪付 —— 此前它只有页面规则引擎一条腿，结果页文本解析进不来。
     */
    val PAYMENT_PACKAGES: Set<String> = setOf(
        "com.tencent.mm",
        "com.eg.android.AlipayGphone",
        "com.unionpay"
    )

    /**
     * 有**通知解析规则**的包（`PaymentResultNormalizer` 四类 normalize）。
     * 是 [PAYMENT_PACKAGES] 的真子集：云闪付是支付包但暂无通知文案解析规则
     * （其通知只记"无解析规则"日志，不产记录）⇒ 不得混入本集合。
     */
    val NOTIFICATION_PACKAGES: Set<String> = setOf(
        "com.tencent.mm",
        "com.eg.android.AlipayGphone"
    )
}
