package com.jotbill.app.utils

import android.content.Context
import android.util.Log
import io.sentry.Breadcrumb
import io.sentry.Sentry
import io.sentry.SentryLevel
import io.sentry.android.core.SentryAndroid
import io.sentry.protocol.User

/**
 * Sentry 日志工具类
 *
 * 功能：
 * 1. 统一的日志接口，同时输出到 Logcat 和 Sentry
 * 2. 支持添加面包屑（Breadcrumbs）追踪用户行为
 * 3. 支持添加上下文信息（Context）
 * 4. 支持捕获异常和错误
 *
 * 使用方式：
 * ```kotlin
 * // 初始化（在 Application.onCreate 中调用）
 * SentryLogger.init(context, "YOUR_SENTRY_DSN")
 *
 * // 记录日志
 * SentryLogger.i("TAG", "Info message")
 * SentryLogger.w("TAG", "Warning message")
 * SentryLogger.e("TAG", "Error message", exception)
 *
 * // 添加面包屑
 * SentryLogger.addBreadcrumb("User clicked button", mapOf("button_id" to "submit"))
 *
 * // 添加上下文
 * SentryLogger.setContext("payment", mapOf("amount" to 100.0, "source" to "alipay"))
 * ```
 *
 * @author JotBill
 * @since 1.0.0
 */
object SentryLogger {

    private const val TAG = "SentryLogger"

    /** 是否已初始化 */
    private var isInitialized = false

    /** 是否启用 Sentry（可通过配置控制） */
    private var isEnabled = true

    private val sensitivePatterns = listOf(
        Regex("""(?i)Bearer\s+[A-Za-z0-9._~+/-]+=*"""),
        Regex("""(?i)(password|passwd|pwd|api[_-]?key|apikey|authorization|access[_-]?token|secret|signature)\s*[:=]\s*([^\s,;]+)"""),
        Regex("""\b[0-9]{13,19}\b"""),
        Regex("""data:image/[^;]+;base64,[A-Za-z0-9+/=]+""")
    )

    /**
     * 阶段七 T7：统一敏感信息脱敏。
     * 密码、API Key、Token、Secret、长数字（疑似卡号）与 Base64 图片整值替换为 ***，
     * 不保留首尾字符。
     */
    fun redact(value: String): String {
        var result = value
        for (pattern in sensitivePatterns) {
            result = pattern.replace(result, "***")
        }
        return result
    }

    /**
     * 初始化 Sentry
     *
     * @param context 应用上下文
     * @param dsn Sentry DSN（Data Source Name）
     *            获取方式：登录 Sentry -> 创建项目 -> 获取 DSN
     *            格式：https://[key]@[organization].ingest.sentry.io/[project-id]
     * @param environment 环境标识（development/production）
     * @param enabled 是否启用 Sentry（默认 true）
     */
    fun init(
        context: Context,
        dsn: String,
        environment: String = "production",
        enabled: Boolean = true
    ) {
        if (isInitialized) {
            Log.w(TAG, "Sentry 已经初始化，跳过重复初始化")
            return
        }

        isEnabled = enabled

        if (!isEnabled) {
            Log.i(TAG, "Sentry 已禁用，不进行初始化")
            return
        }

        try {
            SentryAndroid.init(context) { options ->
                // 设置 DSN
                options.dsn = dsn

                // 设置环境
                options.environment = environment

                // 设置发布版本（从 BuildConfig 获取）
                options.release = "${context.packageName}@${com.jotbill.app.BuildConfig.VERSION_NAME}"

                // 2026-10-31 节电优化：生产关性能追踪（纯遥测，与识别链路无关），
                // 崩溃上报保留。调试构建仍 100% 采样便于本地排查。
                options.tracesSampleRate = if (environment == "production") 0.0 else 1.0

                // 启用自动面包屑收集
                options.isEnableAutoSessionTracking = true

                // 设置日志级别（只发送 WARNING 及以上级别到 Sentry）
                options.setDiagnosticLevel(SentryLevel.WARNING)

                // 启用 ANR（Application Not Responding）检测
                options.isAnrEnabled = true

                // 设置 ANR 超时时间（毫秒）
                options.anrTimeoutIntervalMillis = 5000

                // 附加上下文信息
                options.setBeforeSend { event, hint ->
                    // 可以在这里过滤或修改事件
                    // 返回 null 表示不发送该事件
                    event
                }
            }

            isInitialized = true
            Log.i(TAG, "✓ Sentry 初始化成功 - 环境: $environment")

        } catch (e: Exception) {
            Log.e(TAG, "❌ Sentry 初始化失败", e)
            isEnabled = false
        }
    }

    /**
     * ⚠️ 2026-10-12 删除 `setUser()` / `clearUser()`：零调用点。
     * 精简版没有用户体系（单用户本地记账），Sentry 的用户标识无从产生 ——
     * 留着只会让人以为已经接入了。
     */

    /**
     * 添加面包屑（Breadcrumb）
     *
     * 面包屑用于追踪用户行为路径，帮助重现问题
     *
     * @param message 面包屑消息
     * @param data 附加数据（可选）
     * @param category 分类（可选，如 "navigation", "user_action", "network"）
     * @param level 级别（默认 INFO）
     */
    fun addBreadcrumb(
        message: String,
        data: Map<String, Any>? = null,
        category: String? = null,
        level: SentryLevel = SentryLevel.INFO
    ) {
        if (!isEnabled) return

        try {
            val breadcrumb = Breadcrumb().apply {
                this.message = redact(message)
                this.category = category?.let { redact(it) }
                this.level = level
                data?.forEach { (key, value) ->
                    setData(redact(key), redact(value.toString()))
                }
            }
            Sentry.addBreadcrumb(breadcrumb)
            Log.d(TAG, "🍞 面包屑: ${redact(message)}")
        } catch (e: Exception) {
            Log.e(TAG, "添加面包屑失败", e)
        }
    }

    /**
     * ⚠️ 2026-10-12 删除 `setContext()`：零调用点（KDoc 里的示例不算）。
     * 需要上下文时用 `setTag(key, value)`，那个有活跃调用方
     * （`MainApplication.onCreate`）。
     */

    /**
     * 设置标签
     *
     * 标签用于分类和过滤事件
     *
     * @param key 标签键
     * @param value 标签值
     */
    fun setTag(key: String, value: String) {
        if (!isEnabled) return

        try {
            Sentry.setTag(redact(key), redact(value))
            Log.d(TAG, "✓ 已设置标签: ${redact(key)}=${redact(value)}")
        } catch (e: Exception) {
            Log.e(TAG, "设置标签失败", e)
        }
    }

    /**
     * Info 级别日志
     *
     * 用于记录一般信息，不会发送到 Sentry
     *
     * @param tag 日志标签
     * @param message 日志消息
     */
    fun i(tag: String, message: String) {
        Log.i(redact(tag), redact(message))

        // Info 级别只添加面包屑，不发送事件
        if (isEnabled) {
            addBreadcrumb(message, category = tag, level = SentryLevel.INFO)
        }
    }

    /**
     * Debug 级别日志
     *
     * 用于调试信息，不会发送到 Sentry
     *
     * @param tag 日志标签
     * @param message 日志消息
     */
    fun d(tag: String, message: String) {
        Log.d(redact(tag), redact(message))

        // Debug 级别只添加面包屑，不发送事件
        if (isEnabled) {
            addBreadcrumb(message, category = tag, level = SentryLevel.DEBUG)
        }
    }

    /**
     * Warning 级别日志
     *
     * 用于警告信息，会发送到 Sentry
     *
     * @param tag 日志标签
     * @param message 日志消息
     * @param data 附加数据（可选）
     */
    fun w(tag: String, message: String, data: Map<String, Any>? = null) {
        Log.w(redact(tag), redact(message))

        if (!isEnabled) return

        try {
            // 添加面包屑
            addBreadcrumb(message, data, category = tag, level = SentryLevel.WARNING)

            // 发送警告事件到 Sentry
            Sentry.captureMessage(redact(message), SentryLevel.WARNING)
        } catch (e: Exception) {
            Log.e(TAG, "发送警告到 Sentry 失败", e)
        }
    }

    /**
     * Error 级别日志
     *
     * 用于错误信息，会发送到 Sentry
     *
     * @param tag 日志标签
     * @param message 日志消息
     * @param throwable 异常对象（可选）
     * @param data 附加数据（可选）
     */
    fun e(tag: String, message: String, throwable: Throwable? = null, data: Map<String, Any>? = null) {
        Log.e(redact(tag), redact(message), throwable)

        if (!isEnabled) return

        try {
            // 添加面包屑
            addBreadcrumb(message, data, category = tag, level = SentryLevel.ERROR)

            // 发送错误到 Sentry
            if (throwable != null) {
                Sentry.captureException(throwable) { scope ->
                    if (data != null) {
                        data.forEach { (k, v) ->
                            scope.setExtra(redact("error_$k"), redact(v.toString()))
                        }
                    }
                }
            } else {
                // ⚠️ 此前这里是 `captureMessage(message, ERROR)` —— 同名方法 w 分支
                // 走 redact 而 ERROR 分支不脱敏，属于同一出口的两条路径口径不一致。
                Sentry.captureMessage(redact(message), SentryLevel.ERROR)
            }
        } catch (e: Exception) {
            Log.e(TAG, "发送错误到 Sentry 失败", e)
        }
    }

    /**
     * ⚠️ 2026-10-12 删除 `captureException()`：零调用点（`Sentry.captureException`
     * 是 SDK 直调，与本包装函数是两回事）。本文件实际在用的是 `i/w/e/addBreadcrumb/
     * setTag/captureMessage`，它们都有活跃调用方。
     */

    /**
     * 手动发送消息到 Sentry
     *
     * @param message 消息内容
     * @param level 日志级别
     * @param data 附加数据（可选）
     */
    fun captureMessage(message: String, level: SentryLevel = SentryLevel.INFO, data: Map<String, Any>? = null) {
        Log.i(TAG, "发送消息到 Sentry: ${redact(message)}")

        if (!isEnabled) return

        try {
            // ⚠️ message 与 data 此前全部未脱敏（脱敏的第四条绕过路径）。
            Sentry.captureMessage(redact(message), level) { scope ->
                if (data != null) {
                    data.forEach { (k, v) ->
                        scope.setExtra(redact("message_$k"), redact(v.toString()))
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "发送消息到 Sentry 失败", e)
        }
    }

    /**
     * 刷新 Sentry 缓存
     *
     * 确保所有待发送的事件都被发送
     * 通常在应用退出前调用
     *
     * @param timeoutMillis 超时时间（毫秒）
     */
    fun flush(timeoutMillis: Long = 2000) {
        if (!isEnabled) return

        try {
            Sentry.flush(timeoutMillis)
            Log.d(TAG, "✓ Sentry 缓存已刷新")
        } catch (e: Exception) {
            Log.e(TAG, "刷新 Sentry 缓存失败", e)
        }
    }

    /**
     * 关闭 Sentry
     *
     * 通常在应用退出时调用
     */
    fun close() {
        if (!isEnabled) return

        try {
            Sentry.close()
            isInitialized = false
            Log.i(TAG, "✓ Sentry 已关闭")
        } catch (e: Exception) {
            Log.e(TAG, "关闭 Sentry 失败", e)
        }
    }
}
