package com.jotbill.app.rules

import com.jotbill.app.data.JotBillDatabase
import com.jotbill.app.data.entity.RuleEntity

/**
 * 改分类自学习规则建议器：
 * - 校验商户名与账单/分类类型是否适合沉淀为个人规则；
 * - 结合现有启用规则判断是「无需提示（already_covered）」「更新既有精确规则（update）」还是「新建规则（create）」；
 * - 智能计算建议优先级，避免新建的精确商户规则被既有更宽泛的 `merchant_contains` 规则压住；
 * - 精确统计将被 `applyRulesToExisting` 同步更新的历史账单数（剔除当前账单、已同分类、不兼容类型及用户已锁定的账单）。
 */
internal class CategoryRuleLearningService(
    private val database: JotBillDatabase
) {
    data class Suggestion(
        val status: String,
        val merchant: String,
        val categoryId: String,
        val categoryName: String? = null,
        val existingRuleId: String? = null,
        val existingRuleName: String? = null,
        val existingCategoryId: String? = null,
        val existingCategoryName: String? = null,
        val matchingHistoricalCount: Int = 0,
        val lockedSkippedCount: Int = 0,
        val suggestedPriority: Int = DEFAULT_LEARNED_PRIORITY
    )

    suspend fun preview(
        rawMerchant: String?,
        categoryId: String,
        billType: String,
        excludeBillId: String? = null
    ): Suggestion {
        val merchant = normalizeMerchant(rawMerchant)
            ?: return Suggestion(
                status = STATUS_INELIGIBLE,
                merchant = rawMerchant?.trim().orEmpty(),
                categoryId = categoryId
            )
        val category = database.categoryDao().getById(categoryId)
            ?: return Suggestion(
                status = STATUS_INELIGIBLE,
                merchant = merchant,
                categoryId = categoryId
            )
        if (!RuleEngine.isCategoryCompatibleWithBillType(category.type, billType)) {
            return Suggestion(
                status = STATUS_INELIGIBLE,
                merchant = merchant,
                categoryId = categoryId,
                categoryName = category.name
            )
        }

        val activeRules = database.ruleDao().getActive()
        val ruleEngine = RuleEngine(database)
        val subject = RuleEngine.Subject(merchant = merchant)
        val currentDecision = ruleEngine.evaluate(subject, activeRules)

        // 若既有启用规则对本商户已自动归类到同一目标分类，不再打扰用户
        if (currentDecision.patch.categoryId == categoryId) {
            val coveringHit = currentDecision.hits.firstOrNull {
                it.action == RuleEngine.ACTION_SET_CATEGORY &&
                    it.actionValue?.trim() == categoryId
            }
            return Suggestion(
                status = STATUS_ALREADY_COVERED,
                merchant = merchant,
                categoryId = categoryId,
                categoryName = category.name,
                existingRuleId = coveringHit?.ruleId,
                existingRuleName = coveringHit?.ruleName
            )
        }

        val existingExactRule = activeRules.firstOrNull { rule ->
            rule.condition == RuleEngine.CONDITION_MERCHANT_EQUALS &&
                rule.conditionValue.trim().equals(merchant, ignoreCase = true) &&
                rule.action == RuleEngine.ACTION_SET_CATEGORY
        }

        val blockingPriority = currentDecision.hits
            .asSequence()
            .filter { hit ->
                hit.action == RuleEngine.ACTION_SET_CATEGORY &&
                    hit.ruleId != existingExactRule?.id
            }
            .minOfOrNull { it.priority }

        val suggestedPriority = computeSuggestedPriority(
            existingPriority = existingExactRule?.priority,
            blockingPriority = blockingPriority
        )

        val (matchingHistoricalCount, lockedSkippedCount) = countAffectedHistoricalBills(
            merchant = merchant,
            targetCategoryId = categoryId,
            targetCategoryType = category.type,
            excludeBillId = excludeBillId
        )

        if (existingExactRule != null) {
            val existingCategory = existingExactRule.actionValue?.let {
                database.categoryDao().getById(it)
            }
            return Suggestion(
                status = STATUS_UPDATE,
                merchant = merchant,
                categoryId = categoryId,
                categoryName = category.name,
                existingRuleId = existingExactRule.id,
                existingRuleName = existingExactRule.name,
                existingCategoryId = existingExactRule.actionValue,
                existingCategoryName = existingCategory?.name,
                matchingHistoricalCount = matchingHistoricalCount,
                lockedSkippedCount = lockedSkippedCount,
                suggestedPriority = suggestedPriority
            )
        }

        return Suggestion(
            status = STATUS_CREATE,
            merchant = merchant,
            categoryId = categoryId,
            categoryName = category.name,
            matchingHistoricalCount = matchingHistoricalCount,
            lockedSkippedCount = lockedSkippedCount,
            suggestedPriority = suggestedPriority
        )
    }

    private suspend fun countAffectedHistoricalBills(
        merchant: String,
        targetCategoryId: String,
        targetCategoryType: String,
        excludeBillId: String?
    ): Pair<Int, Int> {
        var matchingCount = 0
        var lockedSkippedCount = 0
        val bills = database.billDao().getMutableBills()
        for (bill in bills) {
            if (excludeBillId != null && bill.id == excludeBillId) continue
            if (!RuleEngine.isCategoryCompatibleWithBillType(targetCategoryType, bill.type)) continue
            if (bill.categoryId == targetCategoryId) continue
            if (bill.merchant?.trim()?.equals(merchant, ignoreCase = true) != true) continue

            val locks = FieldPriorityResolver.parseLocks(bill.fieldLocks)
            if ("categoryId" in locks) {
                lockedSkippedCount += 1
            } else {
                matchingCount += 1
            }
        }
        return matchingCount to lockedSkippedCount
    }

    companion object {
        const val STATUS_INELIGIBLE = "ineligible"
        const val STATUS_ALREADY_COVERED = "already_covered"
        const val STATUS_CREATE = "create"
        const val STATUS_UPDATE = "update"

        const val DEFAULT_LEARNED_PRIORITY = 100
        private const val MIN_MERCHANT_LENGTH = 2
        private const val MAX_MERCHANT_LENGTH = 64

        /**
         * 泛化渠道/占位词黑名单：这些词本身不是具体商户，
         * 若沉淀为规则会导致后续大量不相关账单被误分类。
         */
        private val GENERIC_MERCHANTS = setOf(
            "未知", "未知商户", "商户", "商家", "收款方", "付款方", "个人", "个人商户",
            "转账", "红包", "微信", "微信支付", "微信零钱", "微信红包", "微信转账",
            "支付宝", "余额宝", "花呗", "云闪付", "银联", "银行", "银行卡", "储蓄卡",
            "信用卡", "现金", "自动记账", "消费", "支出", "收入", "退款", "其他",
            "扫码付款", "二维码收款", "收钱码", "商家收款", "付款", "收款",
            "wechat", "alipay", "unknown"
        )

        fun normalizeMerchant(rawMerchant: String?): String? {
            val trimmed = rawMerchant?.trim() ?: return null
            if (trimmed.length !in MIN_MERCHANT_LENGTH..MAX_MERCHANT_LENGTH) return null
            if (trimmed.any { it == '\n' || it == '\r' }) return null
            if (!trimmed.any { it.isLetter() }) return null
            if (trimmed.lowercase() in GENERIC_MERCHANTS) return null
            return trimmed
        }

        fun computeSuggestedPriority(
            existingPriority: Int?,
            blockingPriority: Int?
        ): Int {
            val base = existingPriority ?: DEFAULT_LEARNED_PRIORITY
            if (blockingPriority == null || blockingPriority > base) {
                return base
            }
            return if (blockingPriority <= 10) {
                (blockingPriority - 1).coerceAtLeast(0)
            } else {
                blockingPriority - 10
            }
        }
    }
}
