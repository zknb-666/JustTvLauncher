package cf.zknb.tvlauncher.tvinput

import android.app.ActivityManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log

/**
 * TV输入源监控服务
 * 
 * 持续监控前台应用，当检测到TV应用在前台且本应用不在前台时，切换输入源停止音频
 * 
 * @author JustTvLauncher
 */
class TvMonitorService : Service() {
    
    companion object {
        private const val TAG = "TvMonitorService"
        
        /** 监控间隔时间（毫秒） */
        private const val MONITOR_INTERVAL = 2000L
        
        /** TV应用包名列表 */
        private val TV_PACKAGES = arrayOf(
            "com.tcl.tv",
            "com.konka.tvsettings",
            "com.hisense.ui",
            "com.jamdeo.tv.livetv"
        )
    }
    
    private val handler = Handler(Looper.getMainLooper())
    private var activityManager: ActivityManager? = null
    private var isRunning = false
    
    private val monitorTask = object : Runnable {
        override fun run() {
            if (isRunning) {
                checkAndSwitchInputSource()
                handler.postDelayed(this, MONITOR_INTERVAL)
            }
        }
    }
    
    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "TvMonitorService onCreate")
        activityManager = getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "TvMonitorService onStartCommand")
        
        if (!isRunning) {
            isRunning = true
            handler.post(monitorTask)
            Log.d(TAG, "开始监控TV应用")
        }
        
        return START_STICKY
    }
    
    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "TvMonitorService onDestroy")
        isRunning = false
        handler.removeCallbacks(monitorTask)
    }
    
    override fun onBind(intent: Intent?): IBinder? {
        return null
    }
    
    /**
     * 检查前台应用并切换输入源
     */
    private fun checkAndSwitchInputSource() {
        try {
            val manager = activityManager ?: return
            
            // 获取前台任务
            @Suppress("DEPRECATION")
            val tasks = manager.getRunningTasks(1)
            if (tasks.isNullOrEmpty()) {
                return
            }
            
            val topPackage = tasks[0].topActivity?.packageName ?: return
            val myPackage = packageName
            
            // 检测：如果前台不是我们的app
            if (myPackage != topPackage) {
                // 检查是否是TV应用在前台
                val isTvAppForeground = TV_PACKAGES.any { it == topPackage }
                
                if (isTvAppForeground) {
                    Log.d(TAG, "检测到TV应用在前台: $topPackage, 但我们的app不在前台，保持不操作")
                    // TV应用正常在前台，不需要切换
                } else {
                    // 前台是其他应用（桌面或其他app），切换输入源停止TV音频
                    Log.d(TAG, "检测到其他应用在前台: $topPackage, 切换TV输入源")
                    switchTvInputSource()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "检查前台应用失败", e)
        }
    }
    
    /**
     * 切换TV输入源停止音频
     */
    private fun switchTvInputSource() {
        TvInputSourceSwitcher.switchTvInputSource(this)
    }
}
