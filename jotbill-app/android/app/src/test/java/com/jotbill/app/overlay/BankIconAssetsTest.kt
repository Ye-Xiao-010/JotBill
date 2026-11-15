package com.jotbill.app.overlay

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import java.io.File
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 银行图标资产一致性：`assets/banks.json`（RN 与原生共用的单一事实源）
 * 与原生 `PopupIconAssets` 的硬编码 drawable 表必须同步。
 *
 * drawable 映射因 `R.drawable` 必须静态登记，无法从 JSON 派生 ⇒ 用本测试锁漂移：
 * 新增/改名银行时漏改任一侧都会红。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class BankIconAssetsTest {

    private val context: Application = ApplicationProvider.getApplicationContext()

    private fun loadBanks(): JSONArray {
        val asset = File("src/main/assets/banks.json")
        assertTrue("banks.json 不存在: ${asset.absolutePath}", asset.isFile)
        return JSONArray(asset.readText())
    }

    /** 显式 `bank:<code>` 的解析结果 —— 即该 code 的 drawable 基准。 */
    private fun iconFor(code: String): Int? =
        PopupIconAssets.forAccount(context, "custom-account", null, null, "bank:$code")

    @Test
    fun `每个银行 code 都能解析出图标`() {
        val banks = loadBanks()
        assertTrue("银行数量不应少于 20", banks.length() >= 20)
        for (i in 0 until banks.length()) {
            val bank = banks.getJSONObject(i)
            val code = bank.getString("code")
            assertNotNull(
                "银行 $code（${bank.getString("name")}）缺少 drawable ic_bank_$code",
                iconFor(code),
            )
        }
    }

    @Test
    fun `银行全称与别名都能按名称匹配到同一图标`() {
        val banks = loadBanks()
        for (i in 0 until banks.length()) {
            val bank = banks.getJSONObject(i)
            val code = bank.getString("code")
            val expected = iconFor(code)
            val candidates = buildList {
                add(bank.getString("name"))
                bank.optString("shortName").takeIf { it.isNotBlank() }?.let { add(it) }
                val aliases = bank.optJSONArray("aliases") ?: JSONArray()
                for (j in 0 until aliases.length()) add(aliases.getString(j))
            }
            for (alias in candidates) {
                assertEquals(
                    "别名「$alias」应匹配到 $code 的图标",
                    expected,
                    PopupIconAssets.forAccount(context, "custom-account", "我的$alias", null, null),
                )
            }
        }
    }

    @Test
    fun `最长别名优先 — 中国银行不抢中国工商银行`() {
        assertEquals(
            "中国工商银行应命中工行",
            iconFor("icbc"),
            PopupIconAssets.forAccount(context, "x", "中国工商银行储蓄卡", null, null),
        )
        assertEquals(
            "中国银行应命中中行",
            iconFor("boc"),
            PopupIconAssets.forAccount(context, "x", "中国银行卡", null, null),
        )
    }

    @Test
    fun `显式 icon 指定优先于名称匹配`() {
        assertEquals(
            "显式 bank:ccb 应覆盖名称里的招商银行",
            iconFor("ccb"),
            PopupIconAssets.forAccount(context, "x", "招商银行", "bank_debit", "bank:ccb"),
        )
    }

    @Test
    fun `无法识别的账户回退 subtype 线性图标`() {
        val cashFallback = PopupIconAssets.forAccount(context, "x", "日常零花钱", "cash", null)
        assertNotNull("cash 类型应有线性图标兜底", cashFallback)
        assertEquals(
            "旧 emoji 图标不应影响 subtype 兜底",
            cashFallback,
            PopupIconAssets.forAccount(context, "x", null, "cash", "💵"),
        )
    }
}
