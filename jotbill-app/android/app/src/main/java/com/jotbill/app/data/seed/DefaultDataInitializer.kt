package com.jotbill.app.data.seed

import androidx.room.withTransaction
import com.jotbill.app.data.JotBillDatabase
import com.jotbill.app.data.entity.AccountEntity
import com.jotbill.app.data.entity.AppSettingEntity
import com.jotbill.app.data.entity.CategoryEntity

/**
 * 负责首次启动时补齐默认分类和默认账户。
 *
 * 默认数据使用稳定 ID，兼容没有初始化标记的旧数据库升级场景。写入前还会
 * 按业务名称和类型检查已有记录，避免旧版本或用户数据被重复插入或覆盖。
 */
class DefaultDataInitializer(
    private val database: JotBillDatabase
) {
    suspend fun initializeIfFirstRun() {
        database.withTransaction {
            if (database.appSettingDao().get(INITIALIZED_KEY) == INITIALIZED_VALUE) {
                return@withTransaction
            }

            val now = System.currentTimeMillis()
            val existingCategories = database.categoryDao().getAll()
            defaultCategories.forEach { default ->
                val alreadyExists = existingCategories.any { existing ->
                    existing.id == default.id ||
                        (existing.name == default.name && existing.type == default.type)
                }
                if (!alreadyExists) {
                    // IGNORE：即使内存预检漏掉（如唯一键冲突的边界竞态），也不让
                    // 首启播种抛异常拖垮数据库初始化
                    database.categoryDao().insertIgnore(
                        CategoryEntity(
                            id = default.id,
                            name = default.name,
                            type = default.type,
                            icon = default.icon,
                            color = default.color,
                            isDefault = true,
                            sortOrder = default.sortOrder,
                            createdAt = now,
                            updatedAt = now
                        )
                    )
                }
            }

            val existingAccounts = database.accountDao().getAll()
            defaultAccounts.forEach { default ->
                val alreadyExists = existingAccounts.any { existing ->
                    existing.id == default.id ||
                        (existing.name == default.name && existing.subtype == default.subtype)
                }
                if (!alreadyExists) {
                    database.accountDao().insert(
                        AccountEntity(
                            id = default.id,
                            name = default.name,
                            subtype = default.subtype,
                            kind = default.kind,
                            initialBalanceMinor = 0L,
                            currentBalanceMinor = 0L,
                            currency = "CNY",
                            icon = default.icon,
                            color = default.color,
                            sortOrder = default.sortOrder,
                            isActive = true,
                            createdAt = now,
                            updatedAt = now
                        )
                    )
                }
            }

            database.appSettingDao().set(
                AppSettingEntity(
                    key = INITIALIZED_KEY,
                    value = INITIALIZED_VALUE,
                    updatedAt = now
                )
            )
        }
    }

    /**
     * 批 6:预设子分类(单次播种)。
     *
     * 为常用顶级分类补齐二级分类。以设置键 PRESET_CHILDREN_SEEDED 标记
     * "已播种"——只播一次,用户此后删除的预设子分类不会被复活。
     * 仅作用于 isDefault 的顶级分类;预设子分类同为 isDefault
     * (不可删除,可通过编辑改名)。
     */
    suspend fun ensurePresetSubCategories() {
        if (database.appSettingDao().get(PRESET_CHILDREN_SEEDED_KEY) == INITIALIZED_VALUE) {
            return
        }
        val presetChildren: List<Pair<String, List<String>>> = listOf(
            "default-category-expense-food" to listOf("早餐", "正餐", "外卖", "零食饮品"),
            "default-category-expense-transport" to listOf("打车", "公共交通", "加油", "停车"),
            "default-category-expense-shopping" to listOf("日用百货", "衣物", "数码家电"),
            "default-category-expense-entertainment" to listOf("电影演出", "游戏", "健身运动"),
            "default-category-expense-housing" to listOf("房租房贷", "水电燃气", "物业费"),
            "default-category-expense-medical" to listOf("门诊", "药品", "体检"),
            "default-category-expense-education" to listOf("学费培训", "书籍资料"),
        )
        val now = System.currentTimeMillis()
        database.withTransaction {
            if (database.appSettingDao().get(PRESET_CHILDREN_SEEDED_KEY) == INITIALIZED_VALUE) {
                return@withTransaction
            }
            var seeded = false
            for ((parentId, names) in presetChildren) {
                val parent = database.categoryDao().getById(parentId) ?: continue
                val existingChildren = database.categoryDao().getChildren(parentId)
                val existingNames = existingChildren.map { it.name }.toSet()
                // 若曾发生部分播种(设置键未写入但已插入部分预设),不能固定从 100 开始,
                // 否则会与已有预设子分类撞 sortOrder。统一排在现有子分类之后,且不低于 100。
                val maxExistingSort = existingChildren.maxOfOrNull { it.sortOrder } ?: 99
                var nextSort = maxOf(100, maxExistingSort + 1)
                for (name in names) {
                    if (name in existingNames) {
                        continue
                    }
                    // 同上：预设子分类播种用 IGNORE，ID 冲突只跳过该条
                    val inserted = database.categoryDao().insertIgnore(
                        CategoryEntity(
                            id = "$parentId-preset-$name",
                            name = name,
                            type = parent.type,
                            icon = parent.icon,
                            color = parent.color,
                            isDefault = true,
                            sortOrder = nextSort,
                            createdAt = now,
                            updatedAt = now,
                            parentId = parentId
                        )
                    )
                    if (inserted != -1L) {
                        nextSort += 1
                        seeded = true
                    }
                }
            }
            if (seeded) {
                database.appSettingDao().set(
                    AppSettingEntity(
                        key = PRESET_CHILDREN_SEEDED_KEY,
                        value = INITIALIZED_VALUE,
                        updatedAt = now
                    )
                )
                android.util.Log.i(
                    "DefaultDataInitializer",
                    "预设子分类播种完成"
                )
            } else {
                android.util.Log.i(
                    "DefaultDataInitializer",
                    "预设子分类已播种过,跳过"
                )
            }
        }
    }

    /**
     * 老库分类图标一次性迁移：把首启种子（emoji）升级为 lucide 图标名
     * （2026-10-27，单机版裁定 —— 本地播种器此前种 emoji，`initialized` 短路
     * 让老库永远拿不到新图标，0a80e72 的 lucide 渲染能力因数据是 emoji 而空转）。
     *
     * 单次执行，以设置键 [CATEGORY_ICON_SYNCED_KEY] 标记。判据（三者同时成立才动）：
     * - id 在默认分类清单内（自定义分类的 emoji 永不触碰）；
     * - 用户改过名 ⇒ name 不一致 ⇒ 不动（尊重用户数据，跳 ID 不可靠）；
     * - 当前 icon 与旧种子 emoji **精确相等**（用户若已自选 lucide 名/其他 emoji，
     *   与旧值不等，同样不动）。
     *
     * 子分类未列入：预设子分类的 icon 继承自父分类（播种时复制），父分类更新后
     * 子分类仍带旧 emoji —— 与父级不一致但无害（回退层正常渲染），不额外扫描。
     */
    suspend fun syncDefaultCategoryIcons() {
        if (database.appSettingDao().get(CATEGORY_ICON_SYNCED_KEY) == INITIALIZED_VALUE) {
            return
        }
        database.withTransaction {
            if (database.appSettingDao().get(CATEGORY_ICON_SYNCED_KEY) == INITIALIZED_VALUE) {
                return@withTransaction
            }
            var updated = 0
            for (default in defaultCategories) {
                val existing = database.categoryDao().getById(default.id) ?: continue
                val legacyEmoji = LEGACY_CATEGORY_ICONS[default.id] ?: continue
                if (existing.name == default.name && existing.icon == legacyEmoji) {
                    database.categoryDao().update(
                        existing.copy(icon = default.icon, updatedAt = System.currentTimeMillis())
                    )
                    updated += 1
                }
            }
            database.appSettingDao().set(
                AppSettingEntity(
                    key = CATEGORY_ICON_SYNCED_KEY,
                    value = INITIALIZED_VALUE,
                    updatedAt = System.currentTimeMillis()
                )
            )
            android.util.Log.i("DefaultDataInitializer", "默认分类图标迁移完成: updated=$updated")
        }
    }

    private data class DefaultCategory(
        val id: String,
        val name: String,
        val type: String,
        val icon: String,
        val color: String,
        val sortOrder: Int
    )

    private data class DefaultAccount(
        val id: String,
        val name: String,
        val subtype: String,
        val kind: String,
        val icon: String,
        val color: String,
        val sortOrder: Int
    )

    private companion object {
        const val INITIALIZED_KEY = "initialized"
        const val INITIALIZED_VALUE = "true"
        const val PRESET_CHILDREN_SEEDED_KEY = "preset_children_seeded"

        /** 分类图标一次性迁移标记（[syncDefaultCategoryIcons]）。 */
        const val CATEGORY_ICON_SYNCED_KEY = "category_icons_synced_to_lucide"

        /**
         * 旧种子 emoji（迁移前 [defaultCategories] 的 icon 值，按 id 索引）。
         * 迁移完成后本表与调用点一起保留 —— 二次迁移不可能发生（设置键短路），
         * 留着是判据取证。
         */
        val LEGACY_CATEGORY_ICONS = mapOf(
            "default-category-expense-food" to "🍜",
            "default-category-expense-transport" to "🚌",
            "default-category-expense-shopping" to "🛍️",
            "default-category-expense-entertainment" to "🎮",
            "default-category-expense-housing" to "🏠",
            "default-category-expense-medical" to "💊",
            "default-category-expense-education" to "📚",
            "default-category-expense-communication" to "📱",
            "default-category-expense-daily" to "🧻",
            "default-category-expense-social" to "🎉",
            "default-category-expense-travel" to "✈️",
            "default-category-expense-pet" to "🐾",
            "default-category-expense-other" to "📦",
            "default-category-income-salary" to "💰",
            "default-category-income-part-time" to "💼",
            "default-category-income-investment" to "📈",
            "default-category-income-bonus" to "🎁",
            "default-category-income-red-packet" to "🧧",
            "default-category-income-refund" to "↩️",
            "default-category-income-other" to "📦",
            "default-category-expense-fee" to "🧾"
        )

        // 以文档中的15个分类为基础，补充5个常用分类，保持总数为20个。
        // icon 统一为 lucide 图标名（2026-10-27 用户裁定，单机版对齐后端种子
        // 0a80e72 的数据驱动三层图标体系）：RN `CategoryIcon` 与原生弹窗
        // `PopupIconAssets` 按名字映射渲染矢量图标；匹配不上才回退 emoji 文字。
        val defaultCategories = listOf(
            DefaultCategory("default-category-expense-food", "餐饮", "expense", "utensils", "#FF6B6B", 0),
            DefaultCategory("default-category-expense-transport", "交通", "expense", "bus", "#4ECDC4", 1),
            DefaultCategory("default-category-expense-shopping", "购物", "expense", "shopping-bag", "#45B7D1", 2),
            DefaultCategory("default-category-expense-entertainment", "娱乐", "expense", "gamepad-2", "#96CEB4", 3),
            DefaultCategory("default-category-expense-housing", "居住", "expense", "house", "#98D8C8", 4),
            DefaultCategory("default-category-expense-medical", "医疗", "expense", "pill", "#F44336", 5),
            DefaultCategory("default-category-expense-education", "教育", "expense", "book-open", "#3F51B5", 6),
            DefaultCategory("default-category-expense-communication", "通讯", "expense", "smartphone", "#26A69A", 7),
            DefaultCategory("default-category-expense-daily", "日用", "expense", "shopping-basket", "#78909C", 8),
            DefaultCategory("default-category-expense-social", "社交", "expense", "party-popper", "#FFCA28", 9),
            DefaultCategory("default-category-expense-travel", "旅行", "expense", "plane", "#26C6DA", 10),
            DefaultCategory("default-category-expense-pet", "宠物", "expense", "paw-print", "#66BB6A", 11),
            DefaultCategory("default-category-expense-other", "其他", "expense", "package", "#90A4AE", 12),
            DefaultCategory("default-category-income-salary", "工资", "income", "banknote", "#4CAF50", 13),
            DefaultCategory("default-category-income-part-time", "兼职", "income", "briefcase", "#7E57C2", 14),
            DefaultCategory("default-category-income-investment", "理财", "income", "trending-up", "#009688", 15),
            DefaultCategory("default-category-income-bonus", "奖金", "income", "gift", "#FFB300", 16),
            DefaultCategory("default-category-income-red-packet", "红包", "income", "hand-coins", "#E91E63", 17),
            DefaultCategory("default-category-income-refund", "退款", "income", "rotate-ccw", "#546E7A", 18),
            DefaultCategory("default-category-income-other", "其他", "income", "package", "#2E7D32", 19),
            // 阶段五 T4：手续费独立分类（type=fee 计入支出，归属 expense 组）
            DefaultCategory("default-category-expense-fee", "手续费", "expense", "receipt", "#8D6E63", 20)
        )

        val defaultAccounts = listOf(
            DefaultAccount("default-account-cash", "现金", "cash", "asset", "💵", "#4CAF50", 0),
            DefaultAccount("default-account-alipay", "支付宝", "e_wallet", "asset", "💳", "#2196F3", 1),
            DefaultAccount("default-account-wechat", "微信零钱", "e_wallet", "asset", "💬", "#00A67E", 2),
            DefaultAccount("default-account-credit-card", "信用卡", "credit_card", "liability", "💳", "#FF7043", 3)
        )
    }
}
