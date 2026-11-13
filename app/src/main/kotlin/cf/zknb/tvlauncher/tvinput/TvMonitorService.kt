package cf.zknb.tvlauncher.tvinput

import android.app.ActivityManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.Log

/**
 * TV 输入源监控服务
 *
 * 性能优化：
 * - 旧实现挂在桌面进程的【主线程】Looper 上，每 2 秒执行一次 getRunningTasks(1)
 *   并可能触发 Class.forName 反射，会周期性打断 UI 线程、阻止 CPU 休眠。
 * - 现在放到独立 HandlerThread，间隔放宽到 10 秒，且只在前后台应用真正变化时
 *   才执行切换（状态去重）。
 */
class TvMonitorService : Service() {

    companion object {
        private const val TAG = "TvMonitorService"
        private const val MONITOR_INTERVAL = 10000L

        private val TV_PACKAGES = arrayOf(
            "com.tcl.tv",
            "com.iflytek.showcomesettings",
            "com.tcl.showmessage",
            "com.konka.tvsettings",
            "com.hisense.ui",
            "com.jamdeo.tv.livetv"
        )
    }

    private val handlerThread = HandlerThread("tv-monitor").apply { start() }
    private val handler by lazy { Handler(handlerThread.looper) }
    private var activityManager: ActivityManager? = null
    private var isRunning = false

    /** 只在顶层应用变化时做处理，避免周期性重复反射 */
    @Volatile
    private var lastTopPackage: String? = null

    private val monitorTask = object : Runnable {
        override fun run() {
            if (!isRunning) return
            try {
                checkAndSwitchInputSource()
            } catch (t: Throwable) {
                Log.e(TAG, "monitor task failed", t)
            }
            handler.postDelayed(this, MONITOR_INTERVAL)
        }
    }

    override fun onCreate() {
        super.onCreate()
        activityManager = getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!isRunning) {
            isRunning = true
            handler.post(monitorTask)
        }
        return START_STICKY
    }

    override fun onDestroy() {
        isRunning = false
        handler.removeCallbacksAndMessages(null)
        handlerThread.quit()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun checkAndSwitchInputSource() {
        try {
            val manager = activityManager ?: return
            @Suppress("DEPRECATION")
            val tasks = manager.getRunningTasks(1)
            if (tasks.isNullOrEmpty()) return

            val topPackage = tasks[0].topActivity?.packageName ?: return
            if (topPackage == lastTopPackage) return
            lastTopPackage = topPackage

            if (topPackage == packageName) return
            if (TV_PACKAGES.any { it == topPackage }) return

            Log.d(TAG, "检测到其他应用在前台: $topPackage, 切换TV输入源")
            switchTvInputSource()
        } catch (e: Exception) {
            Log.e(TAG, "检查前台应用失败", e)
        }
    }

    private fun switchTvInputSource() {
        TvInputSourceSwitcher.switchTvInputSource(this)
    }
}
