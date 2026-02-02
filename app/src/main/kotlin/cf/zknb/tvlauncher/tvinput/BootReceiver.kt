package cf.zknb.tvlauncher.tvinput

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * 开机启动接收器
 * 
 * 在系统启动完成后自动启动TV监控服务
 * 
 * @author JustTvLauncher
 */
class BootReceiver : BroadcastReceiver() {
    
    companion object {
        private const val TAG = "BootReceiver"
    }
    
    override fun onReceive(context: Context?, intent: Intent?) {
        if (intent?.action == Intent.ACTION_BOOT_COMPLETED) {
            Log.d(TAG, "系统开机完成")
            
            context?.let {
                // 检查设备是否支持TV信号源功能
                if (TvSourceController.isDeviceSupported(it)) {
                    // 启动TV监控服务
                    val serviceIntent = Intent(it, TvMonitorService::class.java)
                    it.startService(serviceIntent)
                    Log.d(TAG, "TV监控服务已启动")
                } else {
                    Log.d(TAG, "当前设备不支持TV信号源功能，跳过启动TV监控服务")
                }
            }
        }
    }
}
