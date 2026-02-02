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
 * 前台监控服务（备用）
 * 
 * 持续监控应用是否在前台，自动控制TV信号源状态
 * 
 * @author JustTvLauncher
 */
class ForegroundMonitorService : Service() {
    
    companion object {
        private const val TAG = "ForegroundMonitor"
        
        /** 检查间隔时间（毫秒） */
        private const val CHECK_INTERVAL = 2000L
    }
    
    /** Handler处理器 */
    private val handler = Handler(Looper.getMainLooper())
    
    /** 服务运行状态 */
    private var isRunning = false
    
    /** 上次检查是否在前台 */
    private var wasInForeground = true
    
    /** TV信号源控制器 */
    private lateinit var tvSourceController: TvSourceController
    
    /** 监控任务 */
    private val monitorRunnable = object : Runnable {
        override fun run() {
            if (isRunning) {
                checkForegroundStatus()
                handler.postDelayed(this, CHECK_INTERVAL)
            }
        }
    }
    
    /**
     * 服务创建回调
     * 初始化Handler和TV信号源控制器
     */
    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "ForegroundMonitorService onCreate")
        
        tvSourceController = TvSourceController(this)
    }
    
    /**
     * 服务启动命令回调
     * 
     * @param intent 启动意图
     * @param flags 启动标志
     * @param startId 启动ID
     * @return 服务重启模式
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "ForegroundMonitorService onStartCommand")
        
        if (!isRunning) {
            isRunning = true
            handler.post(monitorRunnable)
        }
        
        return START_STICKY
    }
    
    /**
     * 检查应用前台状态
     * 当状态发生变化时，自动恢复或暂停TV信号源
     */
    private fun checkForegroundStatus() {
        val isInForeground = isAppInForeground()
        
        if (isInForeground != wasInForeground) {
            Log.d(TAG, "前台状态变化: $wasInForeground -> $isInForeground")
            
            if (isInForeground) {
                // 回到前台，恢复信号源
                tvSourceController.resumeTvSource()
            } else {
                // 进入后台，暂停/静音信号源
                tvSourceController.pauseTvSource()
            }
            
            wasInForeground = isInForeground
        }
    }
    
    /**
     * 检查应用是否在前台
     * 
     * @return 应用是否在前台运行
     */
    private fun isAppInForeground(): Boolean {
        val activityManager = getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            ?: return false
        
        val appProcesses = activityManager.runningAppProcesses ?: return false
        
        val packageName = packageName
        for (appProcess in appProcesses) {
            if (appProcess.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND &&
                appProcess.processName == packageName) {
                return true
            }
        }
        
        return false
    }
    
    /**
     * 服务销毁回调
     * 停止监控任务
     */
    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "ForegroundMonitorService onDestroy")
        
        isRunning = false
        handler.removeCallbacks(monitorRunnable)
    }
    
    /**
     * 服务绑定回调
     * 
     * @param intent 绑定意图
     * @return 返回绑定对象，此服务不支持绑定
     */
    override fun onBind(intent: Intent?): IBinder? {
        return null
    }
}
