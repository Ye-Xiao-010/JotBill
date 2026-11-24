package com.jotbill.app.theme

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log

/**
 * 动态桌面图标切换器（基于 AndroidManifest 的 activity-alias）。
 *
 * 工程保护：
 * 1. MainActivity 本身始终保持 enabled=true，不参与禁用，避免通知/服务的显式 Intent 失效；
 * 2. 切换顺序严格「先启用目标 alias、再禁用其余 alias」，防止中途中断导致桌面图标丢失；
 * 3. 幂等校验：目标 alias 已启用且其余均禁用时直接跳过，不重复触发系统桌面重载；
 * 4. 在 MainActivity.onStop()（退回后台/桌面）时执行，避免 MIUI/HyperOS 切图标时当场杀进程闪退。
 */
internal object LauncherIconManager {
    private const val TAG = "LauncherIconManager"

    const val ICON_DEFAULT = "default"
    const val ICON_HP_CLASSIC = "hp_classic"
    const val ICON_HP_GRYFFINDOR = "hp_gryffindor"
    const val ICON_HP_SLYTHERIN = "hp_slytherin"
    const val ICON_HP_RAVENCLAW = "hp_ravenclaw"
    const val ICON_HP_HUFFLEPUFF = "hp_hufflepuff"

    private const val BASE_PACKAGE = "com.jotbill.app"

    private val aliasMap: Map<String, String> = linkedMapOf(
        ICON_DEFAULT to "$BASE_PACKAGE.LauncherDefault",
        ICON_HP_CLASSIC to "$BASE_PACKAGE.LauncherHogwartsClassic",
        ICON_HP_GRYFFINDOR to "$BASE_PACKAGE.LauncherHogwartsGryffindor",
        ICON_HP_SLYTHERIN to "$BASE_PACKAGE.LauncherHogwartsSlytherin",
        ICON_HP_RAVENCLAW to "$BASE_PACKAGE.LauncherHogwartsRavenclaw",
        ICON_HP_HUFFLEPUFF to "$BASE_PACKAGE.LauncherHogwartsHufflepuff"
    )

    /** 根据当前主题、学院与动态图标开关计算目标桌面图标键。 */
    fun resolveDesiredIconKey(
        themeName: String,
        house: String,
        dynamicIconEnabled: Boolean
    ): String {
        if (!dynamicIconEnabled || themeName != ThemePreferences.THEME_HOGWARTS) {
            return ICON_DEFAULT
        }
        return when (house) {
            "gryffindor" -> ICON_HP_GRYFFINDOR
            "slytherin" -> ICON_HP_SLYTHERIN
            "ravenclaw" -> ICON_HP_RAVENCLAW
            "hufflepuff" -> ICON_HP_HUFFLEPUFF
            else -> ICON_HP_CLASSIC
        }
    }

    fun aliasClassNameForKey(iconKey: String): String =
        aliasMap[iconKey] ?: aliasMap.getValue(ICON_DEFAULT)

    fun allAliasClassNames(): List<String> = aliasMap.values.toList()

    /**
     * 按偏好设置同步桌面图标（在 MainActivity.onStop 时调用）。
     * @return true 表示实际触发了 PackageManager 变更，false 表示已是目标状态无需改动
     */
    fun applyPendingFromPreferences(context: Context): Boolean {
        val desiredKey = resolveDesiredIconKey(
            themeName = ThemePreferences.getThemeName(context),
            house = ThemePreferences.getHogwartsHouse(context),
            dynamicIconEnabled = ThemePreferences.isDynamicIconEnabled(context)
        )
        return applyIfNeeded(context, desiredKey)
    }

    fun applyIfNeeded(context: Context, iconKey: String): Boolean {
        val appContext = context.applicationContext
        val pm = appContext.packageManager
        val pkgName = appContext.packageName
        val targetAlias = aliasClassNameForKey(iconKey)
        val defaultAlias = aliasMap.getValue(ICON_DEFAULT)

        return runCatching {
            val isAlreadyApplied = aliasMap.values.all { aliasClass ->
                val state = pm.getComponentEnabledSetting(ComponentName(pkgName, aliasClass))
                val effectiveEnabled = when (state) {
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> true
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED -> false
                    else -> aliasClass == defaultAlias // Manifest 默认仅 LauncherDefault 为 enabled
                }
                effectiveEnabled == (aliasClass == targetAlias)
            }
            if (isAlreadyApplied) {
                return false
            }

            // 先启用目标 alias，再禁用其余 alias，确保任何时刻至少有一个桌面入口存活
            pm.setComponentEnabledSetting(
                ComponentName(pkgName, targetAlias),
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                PackageManager.DONT_KILL_APP
            )
            for (aliasClass in aliasMap.values) {
                if (aliasClass == targetAlias) continue
                pm.setComponentEnabledSetting(
                    ComponentName(pkgName, aliasClass),
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.DONT_KILL_APP
                )
            }
            Log.i(TAG, "桌面图标已切换至: $iconKey ($targetAlias)")
            true
        }.onFailure { error ->
            Log.w(TAG, "切换桌面图标失败: $iconKey", error)
        }.getOrDefault(false)
    }
}
