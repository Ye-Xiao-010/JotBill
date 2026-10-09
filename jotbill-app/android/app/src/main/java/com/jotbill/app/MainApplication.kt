package com.jotbill.app

import android.app.Application
import android.content.Context
import com.facebook.react.PackageList
import com.facebook.react.ReactApplication
import com.facebook.react.ReactHost
import com.facebook.react.ReactNativeApplicationEntryPoint.loadReactNative
import com.facebook.react.ReactNativeHost
import com.facebook.react.ReactPackage
import com.facebook.react.defaults.DefaultReactHost.getDefaultReactHost
import com.facebook.react.defaults.DefaultReactNativeHost
import com.jotbill.app.notification.PaymentNotificationPackage
import com.jotbill.app.overlay.PendingConfirmOverlayManager
import com.jotbill.app.utils.InstallApkPackage
import com.jotbill.app.utils.SentryLogger
import com.jotbill.app.hotupdate.HotUpdatePackage
import com.jotbill.app.hotupdate.HotUpdateModule
import com.jotbill.app.bridge.DataPackage
import com.jotbill.app.ai.AiPackage
import com.jotbill.app.runtime.RuntimePackage
import com.jotbill.app.backup.BackupPackage
import com.jotbill.app.overlay.OverlayPackage
import com.jotbill.app.bridge.RecognitionPackage
import com.jotbill.app.pending.PendingReminderNotifier
import com.jotbill.app.budget.BudgetReminderNotifier
import com.facebook.react.ReactInstanceManager
import com.facebook.react.bridge.JSBundleLoader
import java.io.File

class MainApplication : Application(), ReactApplication {

  // 缓存热更新路径，避免重复调用导致 crash_count 多次递增
  @Volatile
  private var hotBundlePathCache: String? = null
  private var hotBundlePathChecked = false

  /**
   * 获取热更新 bundle 路径（含崩溃检测）
   * 结果会被缓存，整个 App 生命周期只计算一次
   */
  private fun getHotBundlePath(): String? {
      if (hotBundlePathChecked) {
          return hotBundlePathCache
      }
      hotBundlePathChecked = true

      val hotBundlePath = File(filesDir, "hot_update/index.android.bundle")
      val hotUpdateDir = hotBundlePath.parentFile
      val verified = hotUpdateDir != null && HotUpdateModule.isVerifiedBundle(hotUpdateDir)
      android.util.Log.i("HotUpdate", "检查热更新 bundle: verified=$verified, size=${if (hotBundlePath.exists()) hotBundlePath.length() else 0}")

      if (verified) {
          val prefs = getSharedPreferences("hot_update", Context.MODE_PRIVATE)
          val crashCount = prefs.getInt("crash_count", 0)
          android.util.Log.i("HotUpdate", "当前 crash_count=$crashCount")

          if (crashCount < 2) {
              // ⚠️ 必须 commit()，不能用 apply()。
              // 这个计数的**全部意义**就是"进程崩溃/被杀后仍能读到累加值"——
              // apply() 是异步落盘，进程被硬杀或 ANR 时根本没写进磁盘，
              // 下次启动读到的 crash_count 偏低甚至恒为 0 ⇒ 永远触发不了
              // `crashCount < 2` 的删除回退 ⇒ 崩溃循环没有自愈。
              // 这里每进程只写一次、同步写，开销可接受。
              prefs.edit().putInt("crash_count", crashCount + 1).commit()
              android.util.Log.i("HotUpdate", "使用热更新 bundle (crash_count: ${crashCount + 1})")
              hotBundlePathCache = hotBundlePath.absolutePath
          } else {
              android.util.Log.w("HotUpdate", "崩溃次数过多 ($crashCount)，尝试删除热更新并回退内置 bundle")
              val deleted = hotUpdateDir == null ||
                  (!hotUpdateDir.exists() || (hotUpdateDir.deleteRecursively() && !hotUpdateDir.exists()))
              if (!deleted) {
                  android.util.Log.e("HotUpdate", "热更新目录删除失败，保留目录并回退内置 bundle")
              }
              if (deleted) {
                  prefs.edit().clear().apply()
              }
              hotBundlePathCache = null
          }
      } else {
          if (hotUpdateDir?.exists() == true) {
              android.util.Log.w("HotUpdate", "热更新 bundle 缺少有效 manifest 或 SHA-256，保留现场并使用内置")
          } else {
              android.util.Log.i("HotUpdate", "无热更新 bundle，使用内置")
          }
          hotBundlePathCache = null
      }
      return hotBundlePathCache
  }

  override val reactNativeHost: ReactNativeHost =
      object : DefaultReactNativeHost(this) {
        override fun getPackages(): List<ReactPackage> =
            PackageList(this).packages.apply {
              add(PaymentNotificationPackage())
              add(InstallApkPackage())
              add(HotUpdatePackage())
              add(DataPackage())
              add(AiPackage())
              add(RuntimePackage())
              add(BackupPackage())
              add(OverlayPackage())
              add(RecognitionPackage())
            }

        override fun getJSMainModuleName(): String = "index"

        override fun getUseDeveloperSupport(): Boolean = BuildConfig.DEBUG

        override val isNewArchEnabled: Boolean = BuildConfig.IS_NEW_ARCHITECTURE_ENABLED
        override val isHermesEnabled: Boolean = BuildConfig.IS_HERMES_ENABLED

        override fun getJSBundleFile(): String? {
            return getHotBundlePath()
        }

        override fun createReactInstanceManager(): ReactInstanceManager {
            val manager = super.createReactInstanceManager()

            val hotPath = hotBundlePathCache
            if (hotPath != null) {
                // 替换 BundleLoader：从文件加载 bundle，但 sourceURL 设为 "assets://"
                // 这样 React Native 图片解析仍走 APK 内置资源
                try {
                    val field = ReactInstanceManager::class.java.getDeclaredField("mBundleLoader")
                    field.isAccessible = true
                    field.set(manager, JSBundleLoader.createFileLoader(
                        hotPath, "assets://index.android.bundle", false
                    ))
                } catch (e: Exception) {
                    android.util.Log.w("HotUpdate", "替换 BundleLoader 失败: ${e.message}")
                }
            }

            return manager
        }
      }

  override val reactHost: ReactHost
    get() = getDefaultReactHost(applicationContext, reactNativeHost)

  override fun onCreate() {
    super.onCreate()
    // 诊断日志落盘最先初始化:越早开,崩溃/服务假死前的日志留得越多。
    // 无 UI 开关,debug 包自动生效;取文件见 JotBillLog.FILE_NAME 的 KDoc。
    com.jotbill.app.utils.JotBillLog.init(this)
    // 开发期数据库明文导出(仅 debug):让外部 sqlite3 能审计表内容,SQLCipher 的
    // passphrase 在 Keystore 内不可导出,没有这条路就只能靠肉眼点 UI。异步执行不拖慢启动。
    com.jotbill.app.utils.DiagDbDump.dumpAsync(this)
    // 待确认提醒先于 RN 初始化:通知监听/无障碍路径在 RN 未启动时也要能发系统通知
    PendingReminderNotifier.init(this)
    // 付款确认弹窗:订阅待确认创建事件,悬浮权限+非敏感帧门禁通过时就地弹窗
    PendingConfirmOverlayManager.init(this)
    // 识别能力开关:无障碍服务可能在 RN 进程未启动时被系统拉起,先于任何识别门禁
    // 把持久值读进内存镜像,避免两处读到不同的开关状态
    com.jotbill.app.recognition.RecognitionCapabilities.load(this)
    // 预算超支提醒:账单增删改后由 DataModule 触发检查,应用未启动也能发系统通知
    BudgetReminderNotifier.init(this)
    HotUpdateModule.recoverInterruptedSwap(this)
    loadReactNative(this)
    initializeSentry()
  }

  private fun initializeSentry() {
    val sentryDsn = BuildConfig.SENTRY_DSN
    if (sentryDsn.isBlank()) {
      android.util.Log.w("MainApplication", "SENTRY_DSN 未配置，跳过 Sentry 初始化")
      return
    }

    val environment = if (BuildConfig.DEBUG) "development" else "production"

    SentryLogger.init(
      context = this,
      dsn = sentryDsn,
      environment = environment,
      enabled = true
    )

    SentryLogger.setTag("app_module", "payment_notification")
    SentryLogger.setTag("platform", "android")

    if (BuildConfig.DEBUG) {
      SentryLogger.captureMessage("Sentry test message - app started", io.sentry.SentryLevel.INFO)
    }

    android.util.Log.i("MainApplication", "Sentry 日志监控已初始化")
  }
}
