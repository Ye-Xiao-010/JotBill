package com.jotbill.app.bookkeeping

/**
 * 内置「商户/平台 → 分类」词典 —— 小米把这件事放在服务端，本地模式用关键词
 * 词典达到同等效果（用户自写的分类规则优先级更高，见 AutoBookkeeper 应用顺序）。
 *
 * 命中不了就返回 null 落「未分类」—— 词典宁可保守也不猜错大类。
 * 分类 ID 与 DefaultDataInitializer 种子分类一致；用户删掉种子分类时由
 * 调用方校验兜底（词典结果同样过存在性校验）。
 */
object BookkeepingCategoryHints {

    /** 关键词按序匹配（先命中先赢），文本范围 = 商户 + 平台 + 规则备注。 */
    private val KEYWORDS: List<Pair<List<String>, String>> = listOf(
        // 餐饮
        (listOf(
            "美团", "外卖", "饿了么", "肯德基", "麦当劳", "星巴克", "瑞幸", "库迪",
            "蜜雪", "喜茶", "奈雪", "奶茶", "咖啡", "火锅", "烧烤", "餐厅", "小吃",
            "汉堡", "披萨", "面馆", "米粉", "食堂", "饮料", "甜品"
        ) to "default-category-expense-food"),
        // 交通
        (listOf(
            "滴滴", "高德", "哈啰", "出行", "打车", "公交", "地铁", "铁路", "12306",
            "航空", "机票", "火车票", "加油", "充电桩", "停车", "顺风车", "代驾"
        ) to "default-category-expense-transport"),
        // 购物（渠道名"云闪付/支付宝"不参与购物判定，只匹配具体商户词）
        (listOf(
            "京东", "淘宝", "天猫", "拼多多", "唯品会", "苏宁", "便利店", "超市",
            "百货", "商城", "旗舰店", "专卖店"
        ) to "default-category-expense-shopping"),
        // 娱乐
        (listOf(
            "抖音", "游戏", "电影", "影院", "爱奇艺", "哔哩", "腾讯视频", "芒果",
            "网易云音乐", "QQ音乐", "会员", "直播", "KTV", "密室"
        ) to "default-category-expense-entertainment"),
        // 居住
        (listOf(
            "房租", "物业", "水电", "水费", "电费", "燃气", "宽带", "供暖", "房贷"
        ) to "default-category-expense-housing"),
        // 医疗
        (listOf(
            "医院", "药房", "药店", "诊所", "体检", "医药", "牙科", "眼科"
        ) to "default-category-expense-medical"),
        // 教育
        (listOf(
            "学费", "教育", "培训", "书店", "得到", "知识付费", "网课", "考试"
        ) to "default-category-expense-education"),
        // 通讯
        (listOf(
            "话费", "流量", "中国移动", "中国联通", "中国电信", "运营商"
        ) to "default-category-expense-communication"),
        // 日用
        (listOf("日用", "百货日化", "纸巾", "洗护") to "default-category-expense-daily")
    )

    /** 收入侧按规则语义直判：红包 → 红包分类；转账/收款 → 其他收入。 */
    fun guess(
        merchant: String?,
        platform: String?,
        ruleNote: String?,
        type: String
    ): String? {
        if (type == "income") {
            val text = "$merchant $ruleNote"
            return when {
                text.contains("红包") -> "default-category-income-red-packet"
                text.contains("退款") || text.contains("退货") -> "default-category-income-refund"
                else -> "default-category-income-other"
            }
        }
        val haystack = "$merchant $platform $ruleNote"
        for ((keywords, categoryId) in KEYWORDS) {
            if (keywords.any { haystack.contains(it) }) {
                return categoryId
            }
        }
        return null
    }
}
