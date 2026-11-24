package com.jotbill.app.theme

import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import com.jotbill.app.R

/**
 * 悬浮付款确认弹窗的「魔法羊皮纸」原生视觉主题助手。
 *
 * 色板数值与前端 `src/theme/colors.ts` 中的 5 组霍格沃茨浅色色板（classic + 四学院）
 * 一一对齐，确保应用外弹出的系统悬浮窗与 App 内羊皮纸/学院视觉完全统一。
 * 非 hogwarts 主题不调用本类，原粗野主义 XML 外观零改动。
 */
internal object HogwartsOverlayTheme {

    data class Palette(
        val houseKey: String,
        val primary: Int,
        val primaryLight: Int,
        val accent: Int,
        val onPrimary: Int = 0xFFFCF7EA.toInt(),
        val cardBg: Int = 0xFFFCF7EA.toInt(),
        val cardBorder: Int = 0xFFCBB894.toInt(),
        val surfaceBg: Int = 0xFFF3E9D2.toInt(),
        val textPrimary: Int = 0xFF26190D.toInt(),
        val textSecondary: Int = 0xFF4A3621.toInt(),
        val textHint: Int = 0xFF634C32.toInt(),
        val expenseColor: Int = 0xFFA62323.toInt(),
        val successColor: Int = 0xFF1F6B3A.toInt(),
        val successBg: Int = 0xFFDFEFE4.toInt(),
        val errorColor: Int = 0xFFA62626.toInt(),
        val errorBg: Int = 0xFFF7DCDC.toInt()
    )

    const val OVERLAY_TITLE = "🦉 猫头鹰确认入账"

    private val CLASSIC = Palette(
        houseKey = "classic",
        primary = 0xFF735318.toInt(),
        primaryLight = 0xFFEFE3C2.toInt(),
        accent = 0xFFC9A227.toInt()
    )

    private val GRYFFINDOR = Palette(
        houseKey = "gryffindor",
        primary = 0xFF821B1F.toInt(),
        primaryLight = 0xFFF3DADA.toInt(),
        accent = 0xFFC9A227.toInt()
    )

    private val SLYTHERIN = Palette(
        houseKey = "slytherin",
        primary = 0xFF195434.toInt(),
        primaryLight = 0xFFD8ECE0.toInt(),
        accent = 0xFF8D99AE.toInt()
    )

    private val RAVENCLAW = Palette(
        houseKey = "ravenclaw",
        primary = 0xFF1D3975.toInt(),
        primaryLight = 0xFFDCE4F4.toInt(),
        accent = 0xFFB0793A.toInt()
    )

    private val HUFFLEPUFF = Palette(
        houseKey = "hufflepuff",
        primary = 0xFF7D510D.toInt(),
        primaryLight = 0xFFF4E5C1.toInt(),
        accent = 0xFFD99B26.toInt()
    )

    /** 纯函数解析 5 组学院色板；未知值安全回退到 classic。 */
    fun resolvePalette(house: String?): Palette = when (house?.lowercase()) {
        "gryffindor" -> GRYFFINDOR
        "slytherin" -> SLYTHERIN
        "ravenclaw" -> RAVENCLAW
        "hufflepuff" -> HUFFLEPUFF
        else -> CLASSIC
    }

    fun allPalettes(): List<Palette> = listOf(CLASSIC, GRYFFINDOR, SLYTHERIN, RAVENCLAW, HUFFLEPUFF)

    /** 将整张悬浮确认弹窗重绘为羊皮纸 + 当前学院配色。 */
    fun applyOverlayTheme(rootView: View, palette: Palette) {
        val density = rootView.resources.displayMetrics.density
        val strokeThin = (1f * density).toInt().coerceAtLeast(1)
        val strokeAccent = (1.5f * density).toInt().coerceAtLeast(2)

        rootView.findViewById<View>(R.id.overlay_card)?.background = roundedRect(
            fillColor = palette.cardBg,
            strokeColor = palette.accent,
            strokeWidthPx = strokeAccent,
            radiusPx = 18f * density
        )

        rootView.findViewById<TextView>(R.id.tv_overlay_title)?.apply {
            text = OVERLAY_TITLE
            setTextColor(palette.textPrimary)
        }

        rootView.findViewById<ImageButton>(R.id.btn_close_x)?.apply {
            background = roundedRect(
                fillColor = palette.surfaceBg,
                strokeColor = palette.cardBorder,
                strokeWidthPx = strokeThin,
                radiusPx = 17f * density
            )
            imageTintList = ColorStateList.valueOf(palette.textSecondary)
        }

        rootView.findViewById<TextView>(R.id.tv_source)?.apply {
            background = roundedRect(
                fillColor = palette.primaryLight,
                strokeColor = palette.accent,
                strokeWidthPx = strokeThin,
                radiusPx = 10f * density
            )
            setTextColor(palette.textPrimary)
        }

        rootView.findViewById<TextView>(R.id.tv_merchant)?.setTextColor(palette.textSecondary)
        rootView.findViewById<TextView>(R.id.tv_account_label)?.setTextColor(palette.textPrimary)
        rootView.findViewById<TextView>(R.id.tv_category_label)?.setTextColor(palette.textPrimary)

        rootView.findViewById<EditText>(R.id.et_amount)?.apply {
            background = roundedRect(
                fillColor = palette.surfaceBg,
                strokeColor = palette.cardBorder,
                strokeWidthPx = strokeThin,
                radiusPx = 12f * density
            )
            setTextColor(palette.expenseColor)
        }

        rootView.findViewById<EditText>(R.id.et_remark)?.apply {
            background = roundedRect(
                fillColor = palette.surfaceBg,
                strokeColor = palette.cardBorder,
                strokeWidthPx = strokeThin,
                radiusPx = 12f * density
            )
            setTextColor(palette.textPrimary)
            setHintTextColor(palette.textHint)
        }

        rootView.findViewById<Button>(R.id.btn_close)?.apply {
            background = roundedRect(
                fillColor = palette.surfaceBg,
                strokeColor = palette.cardBorder,
                strokeWidthPx = strokeThin,
                radiusPx = 12f * density
            )
            setTextColor(palette.textPrimary)
        }

        val primaryButtonBg = {
            roundedRect(
                fillColor = palette.primary,
                strokeColor = palette.accent,
                strokeWidthPx = strokeAccent,
                radiusPx = 12f * density
            )
        }

        rootView.findViewById<Button>(R.id.btn_confirm)?.apply {
            background = primaryButtonBg()
            setTextColor(palette.onPrimary)
        }

        rootView.findViewById<Button>(R.id.btn_retry)?.apply {
            background = primaryButtonBg()
            setTextColor(palette.onPrimary)
        }

        // 成功 / 失败反馈视图同步适配羊皮纸墨色
        rootView.findViewById<TextView>(R.id.tv_success_icon)?.apply {
            background = roundedRect(
                fillColor = palette.successBg,
                strokeColor = palette.successColor,
                strokeWidthPx = strokeAccent,
                radiusPx = 32f * density
            )
            setTextColor(palette.successColor)
        }
        rootView.findViewById<TextView>(R.id.tv_success_title)?.setTextColor(palette.textPrimary)
        rootView.findViewById<TextView>(R.id.tv_success_detail)?.setTextColor(palette.textSecondary)

        rootView.findViewById<TextView>(R.id.tv_error_icon)?.apply {
            background = roundedRect(
                fillColor = palette.errorBg,
                strokeColor = palette.errorColor,
                strokeWidthPx = strokeAccent,
                radiusPx = 32f * density
            )
            setTextColor(palette.errorColor)
        }
        rootView.findViewById<TextView>(R.id.tv_error_title)?.setTextColor(palette.textPrimary)
        rootView.findViewById<TextView>(R.id.tv_error_message)?.setTextColor(palette.textSecondary)
    }

    /** 统一样式化分类与账户 chip：选中态使用学院浅底 + 鎏金边 + 学院深主色。 */
    fun styleChip(view: TextView, selected: Boolean, palette: Palette) {
        val density = view.resources.displayMetrics.density
        val strokeWidth = if (selected) {
            (1.5f * density).toInt().coerceAtLeast(2)
        } else {
            (1f * density).toInt().coerceAtLeast(1)
        }
        view.background = roundedRect(
            fillColor = if (selected) palette.primaryLight else palette.surfaceBg,
            strokeColor = if (selected) palette.accent else palette.cardBorder,
            strokeWidthPx = strokeWidth,
            radiusPx = 10f * density
        )
        view.setTextColor(if (selected) palette.primary else palette.textPrimary)
    }

    private fun roundedRect(
        fillColor: Int,
        strokeColor: Int,
        strokeWidthPx: Int,
        radiusPx: Float
    ): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(fillColor)
        setStroke(strokeWidthPx, strokeColor)
        cornerRadius = radiusPx
    }
}
