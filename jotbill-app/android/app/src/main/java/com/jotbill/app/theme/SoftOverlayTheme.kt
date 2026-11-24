package com.jotbill.app.theme

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import com.jotbill.app.R

/**
 * 柔光（soft）主题原生付款确认悬浮窗调色板与动态换肤器。
 *
 * 色值与 JS 侧 `src/theme/colors.ts` 的 `softLight` / `softDark` 保持 1:1 严格一致；
 * 仅在当前主题为 `soft` 时生效，其余 4 套积木风主题维持原有 XML drawable 零改动。
 */
internal data class SoftOverlayPalette(
    val scrim: Int,
    val surface: Int,
    val surfaceMuted: Int,
    val cardBorder: Int,
    val hasCardBorder: Boolean,
    val primary: Int,
    val primaryLight: Int,
    val primaryDark: Int,
    val onPrimary: Int,
    val textPrimary: Int,
    val textSecondary: Int,
    val textTertiary: Int,
    val expense: Int,
    val expenseLight: Int,
    val expenseStrong: Int,
    val income: Int,
    val incomeLight: Int,
    val warningBg: Int,
    val warningText: Int,
)

internal object SoftOverlayTheme {
    val lightPalette = SoftOverlayPalette(
        scrim = 0x6B12141A,
        surface = 0xFFFFFFFF.toInt(),
        surfaceMuted = 0xFFEEF0F6.toInt(),
        cardBorder = 0xFFE4E7EE.toInt(),
        hasCardBorder = false,
        primary = 0xFF4F46E5.toInt(),
        primaryLight = 0xFFEEF0FF.toInt(),
        primaryDark = 0xFF4338CA.toInt(),
        onPrimary = 0xFFFFFFFF.toInt(),
        textPrimary = 0xFF12141A.toInt(),
        textSecondary = 0xFF454A57.toInt(),
        textTertiary = 0xFF646A78.toInt(),
        expense = 0xFFDC2626.toInt(),
        expenseLight = 0xFFFDECEC.toInt(),
        expenseStrong = 0xFFB91C1C.toInt(),
        income = 0xFF15803D.toInt(),
        incomeLight = 0xFFE3F6EA.toInt(),
        warningBg = 0x26F59E0B,
        warningText = 0xFFB45309.toInt(),
    )

    val darkPalette = SoftOverlayPalette(
        scrim = 0x99000000.toInt(),
        surface = 0xFF171A21.toInt(),
        surfaceMuted = 0xFF1E222B.toInt(),
        cardBorder = 0xFF232731.toInt(),
        hasCardBorder = true,
        primary = 0xFF8B8DFB.toInt(),
        primaryLight = 0xFF23264A.toInt(),
        primaryDark = 0xFFA5A7FF.toInt(),
        onPrimary = 0xFF0E1014.toInt(),
        textPrimary = 0xFFF3F4F7.toInt(),
        textSecondary = 0xFFC5C9D3.toInt(),
        textTertiary = 0xFF8D93A1.toInt(),
        expense = 0xFFF87171.toInt(),
        expenseLight = 0xFF3A1A1E.toInt(),
        expenseStrong = 0xFFF87171.toInt(),
        income = 0xFF4ADE80.toInt(),
        incomeLight = 0xFF143222.toInt(),
        warningBg = 0x33FBBF24,
        warningText = 0xFFFBBF24.toInt(),
    )

    fun paletteFor(isDark: Boolean): SoftOverlayPalette =
        if (isDark) darkPalette else lightPalette

    fun apply(root: View, isDark: Boolean) {
        val palette = paletteFor(isDark)
        val density = root.resources.displayMetrics.density
        fun dp(value: Float): Int = (value * density).toInt()

        root.setBackgroundColor(palette.scrim)

        root.findViewById<LinearLayout>(R.id.overlay_card)?.apply {
            background = roundedDrawable(
                fillColor = palette.surface,
                radiusPx = 26f * density,
                strokeWidthPx = if (palette.hasCardBorder) dp(1f) else 0,
                strokeColor = palette.cardBorder,
            )
            elevation = 12f * density
        }

        root.findViewById<TextView>(R.id.tv_overlay_title)?.apply {
            text = "确认入账"
            setTextColor(palette.textPrimary)
            typeface = Typeface.DEFAULT_BOLD
        }

        root.findViewById<ImageButton>(R.id.btn_close_x)?.apply {
            background = roundedDrawable(
                fillColor = palette.surfaceMuted,
                radiusPx = 17f * density,
            )
            imageTintList = ColorStateList.valueOf(palette.textSecondary)
        }

        root.findViewById<TextView>(R.id.tv_source)?.apply {
            background = roundedDrawable(
                fillColor = palette.warningBg,
                radiusPx = 14f * density,
            )
            setTextColor(palette.warningText)
        }

        root.findViewById<TextView>(R.id.tv_merchant)?.apply {
            setTextColor(palette.textSecondary)
            typeface = Typeface.DEFAULT
        }

        root.findViewById<EditText>(R.id.et_amount)?.apply {
            background = roundedDrawable(
                fillColor = palette.surfaceMuted,
                radiusPx = 16f * density,
            )
            setTextColor(palette.expense)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(12f), dp(10f), dp(12f), dp(10f))
        }

        root.findViewById<TextView>(R.id.tv_account_label)?.setTextColor(palette.textSecondary)
        root.findViewById<TextView>(R.id.tv_category_label)?.setTextColor(palette.textSecondary)

        root.findViewById<EditText>(R.id.et_remark)?.apply {
            background = roundedDrawable(
                fillColor = palette.surfaceMuted,
                radiusPx = 14f * density,
            )
            setTextColor(palette.textPrimary)
            setHintTextColor(palette.textTertiary)
            typeface = Typeface.DEFAULT
        }

        root.findViewById<Button>(R.id.btn_close)?.apply {
            background = roundedDrawable(
                fillColor = palette.surfaceMuted,
                radiusPx = 14f * density,
            )
            setTextColor(palette.textSecondary)
            stateListAnimator = null
            elevation = 0f
        }

        root.findViewById<Button>(R.id.btn_confirm)?.apply {
            background = roundedDrawable(
                fillColor = palette.primary,
                radiusPx = 14f * density,
            )
            setTextColor(palette.onPrimary)
            stateListAnimator = null
            elevation = 0f
        }

        root.findViewById<TextView>(R.id.tv_success_icon)?.apply {
            background = roundedDrawable(
                fillColor = palette.incomeLight,
                radiusPx = 32f * density,
            )
            setTextColor(palette.income)
        }
        root.findViewById<TextView>(R.id.tv_success_title)?.setTextColor(palette.textPrimary)
        root.findViewById<TextView>(R.id.tv_success_detail)?.apply {
            setTextColor(palette.textSecondary)
            typeface = Typeface.DEFAULT_BOLD
        }

        root.findViewById<TextView>(R.id.tv_error_icon)?.apply {
            background = roundedDrawable(
                fillColor = palette.expenseLight,
                radiusPx = 32f * density,
            )
            setTextColor(palette.expense)
        }
        root.findViewById<TextView>(R.id.tv_error_title)?.setTextColor(palette.textPrimary)
        root.findViewById<TextView>(R.id.tv_error_message)?.setTextColor(palette.textSecondary)
        root.findViewById<Button>(R.id.btn_retry)?.apply {
            background = roundedDrawable(
                fillColor = palette.primary,
                radiusPx = 14f * density,
            )
            setTextColor(palette.onPrimary)
            stateListAnimator = null
            elevation = 0f
        }
    }

    fun styleChip(chip: TextView, selected: Boolean, isDark: Boolean) {
        val palette = paletteFor(isDark)
        val density = chip.resources.displayMetrics.density
        val strokePx = if (selected) (1.5f * density).toInt().coerceAtLeast(1) else 0
        chip.background = roundedDrawable(
            fillColor = if (selected) palette.primaryLight else palette.surfaceMuted,
            radiusPx = 12f * density,
            strokeWidthPx = strokePx,
            strokeColor = if (selected) palette.primary else Color.TRANSPARENT,
        )
        chip.setTextColor(if (selected) palette.primaryDark else palette.textPrimary)
    }

    private fun roundedDrawable(
        fillColor: Int,
        radiusPx: Float,
        strokeWidthPx: Int = 0,
        strokeColor: Int = Color.TRANSPARENT,
    ): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = radiusPx
        setColor(fillColor)
        if (strokeWidthPx > 0) {
            setStroke(strokeWidthPx, strokeColor)
        }
    }
}
