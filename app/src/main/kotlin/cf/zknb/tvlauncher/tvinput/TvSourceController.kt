package cf.zknb.tvlauncher.tvinput

import android.app.ActivityManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * TV信号源控制器
 * 
 * 负责电视信号源的启动和管理，支持多品牌电视适配
 * 
 * 支持的品牌：
 * - TCL电视 - 已测试通过
 * - 康佳电视（KONKA） - 代码已实现，待测试
 * - 海信电视（HISENSE） - 代码已实现，待测试
 * 
 * @author JustTvLauncher
 */
class TvSourceController(private val context: Context) {
    
    companion object {
        private const val TAG = "TvSourceController"
        
        /** 康佳品牌标识 */
        private const val BRAND_KONKA = "KONKA"
        
        /** 海信品牌标识 */
        private const val BRAND_HISENSE = "HISENSE"
        
        /** TCL品牌标识 */
        private const val BRAND_TCL = "TCL"
        
        /** 康佳TV应用包名 */
        private const val PKG_KONKA = "com.konka.tvsettings"
        
        /** 海信UI应用包名 */
        private const val PKG_HISENSE_UI = "com.hisense.ui"
        
        /** 海信直播TV应用包名 */
        private const val PKG_HISENSE_LIVETV = "com.jamdeo.tv.livetv"
        
        /** TCL TV应用包名 */
        private const val PKG_TCL = "com.tcl.tv"
        
        /**
         * 检测设备是否支持TV信号源功能
         * 
         * @param context 上下文
         * @return 是否支持
         */
        fun isDeviceSupported(context: Context): Boolean {
            val manufacturer = Build.MANUFACTURER.uppercase()
            val brand = Build.BRAND.uppercase()
            
            // 检查是否是已知的TV品牌
            val isKnownBrand = manufacturer.contains("KONKA") || brand.contains("KONKA") ||
                    manufacturer.contains("HISENSE") || brand.contains("HISENSE") ||
                    manufacturer.contains("TCL") || brand.contains("TCL")
            
            if (isKnownBrand) {
                return true
            }
            
            // 检查是否安装了常见的TV应用
            val tvPackages = listOf(PKG_TCL, PKG_KONKA, PKG_HISENSE_UI, PKG_HISENSE_LIVETV)
            return tvPackages.any { isPackageInstalled(context, it) }
        }
        
        /**
         * 检查应用是否已安装（静态方法）
         */
        private fun isPackageInstalled(context: Context, packageName: String): Boolean {
            return try {
                context.packageManager.getPackageInfo(packageName, 0)
                true
            } catch (e: PackageManager.NameNotFoundException) {
                false
            }
        }
    }
    
    /** 检测到的TV品牌 */
    private val tvBrand: String
    
    /** 当前启动的TV应用包名 */
    private var currentTvPackage: String? = null
    
    /** Activity管理器 */
    private val activityManager: ActivityManager? = 
        context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
    
    init {
        tvBrand = detectTvBrand()
        Log.d(TAG, "检测到电视品牌: $tvBrand")
    }
    
    /**
     * 检测电视品牌
     * @return 电视品牌标识
     */
    private fun detectTvBrand(): String {
        val manufacturer = Build.MANUFACTURER.uppercase()
        val brand = Build.BRAND.uppercase()
        
        Log.d(TAG, "Manufacturer: $manufacturer, Brand: $brand")
        
        return when {
            manufacturer.contains("KONKA") || brand.contains("KONKA") -> BRAND_KONKA
            manufacturer.contains("HISENSE") || brand.contains("HISENSE") -> BRAND_HISENSE
            manufacturer.contains("TCL") || brand.contains("TCL") -> BRAND_TCL
            else -> manufacturer
        }
    }
    
    /**
     * 打开电视信号源
     * @return 是否成功打开
     */
    fun openTvSource(): Boolean {
        Log.d(TAG, "尝试打开电视信号源，品牌: $tvBrand")
        
        return try {
            when (tvBrand) {
                BRAND_KONKA -> openKonkaTvSource()
                BRAND_HISENSE -> openHisenseTvSource()
                BRAND_TCL -> openTclTvSource()
                else -> openGenericTvSource()
            }
        } catch (e: Exception) {
            Log.e(TAG, "打开电视信号源失败", e)
            false
        }
    }
    
    /**
     * 打开康佳电视信号源
     * @return 是否成功打开
     */
    private fun openKonkaTvSource(): Boolean {
        return try {
            if (isPackageInstalled(PKG_KONKA)) {
                val intent = Intent().apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                    setClassName(PKG_KONKA, "$PKG_KONKA.RootActivity")
                }
                context.startActivity(intent)
                currentTvPackage = PKG_KONKA
                Log.d(TAG, "成功启动康佳电视信号源: $currentTvPackage")
                true
            } else {
                false
            }
        } catch (e: ActivityNotFoundException) {
            Log.e(TAG, "康佳电视应用未找到", e)
            false
        }
    }
    
    /**
     * 打开海信电视信号源
     * @return 是否成功打开
     */
    private fun openHisenseTvSource(): Boolean {
        return try {
            // 方法1: 尝试启动海信TV应用
            if (isPackageInstalled(PKG_HISENSE_UI)) {
                Log.d(TAG, "检测到海信UI应用")
                // 海信需要通过广播启动
                val intent = Intent("com.hisense.livetvhome_START_LIVETV_HOME").apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.sendBroadcast(intent)
                currentTvPackage = PKG_HISENSE_UI
                Log.d(TAG, "发送海信电视启动广播")
                return true
            }
            
            // 方法2: 尝试直接启动直播TV
            if (isPackageInstalled(PKG_HISENSE_LIVETV)) {
                val intent = context.packageManager.getLaunchIntentForPackage(PKG_HISENSE_LIVETV)
                if (intent != null) {
                    intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    context.startActivity(intent)
                    currentTvPackage = PKG_HISENSE_LIVETV
                    Log.d(TAG, "成功启动海信直播TV: $currentTvPackage")
                    return true
                }
            }
            false
        } catch (e: Exception) {
            Log.e(TAG, "海信电视启动失败", e)
            false
        }
    }
    
    /**
     * 打开TCL电视信号源
     * @return 是否成功打开
     */
    private fun openTclTvSource(): Boolean {
        return try {
            if (isPackageInstalled(PKG_TCL)) {
                val intent = Intent().apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                    setClassName(PKG_TCL, "$PKG_TCL.TVActivity")
                }
                context.startActivity(intent)
                currentTvPackage = PKG_TCL
                Log.d(TAG, "成功启动TCL电视信号源: $currentTvPackage")
                true
            } else {
                // 尝试通过包名启动
                launchPackage(PKG_TCL)
            }
        } catch (e: ActivityNotFoundException) {
            Log.e(TAG, "TCL电视应用未找到", e)
            false
        }
    }
    
    /**
     * 打开通用电视信号源
     * 尝试启动常见的电视应用包
     * @return 是否成功打开
     */
    private fun openGenericTvSource(): Boolean {
        // 尝试一些通用的电视应用包名
        val commonTvPackages = arrayOf(
            "com.android.tv",
            "com.google.android.tv",
            "android.media.tv",
            "com.mediatek.tv",
            "com.mstar.tv"
        )
        
        for (packageName in commonTvPackages) {
            if (launchPackage(packageName)) {
                currentTvPackage = packageName
                Log.d(TAG, "成功启动通用电视应用: $currentTvPackage")
                return true
            }
        }
        
        Log.w(TAG, "未找到支持的电视应用")
        return false
    }
    
    /**
     * 恢复TV信号源
     * 关闭当前前台应用，然后重新启动TV信号源，通常在应用从后台回到前台时调用
     */
    fun resumeTvSource() {
        Log.d(TAG, "恢复信号源 - 关闭其他应用并重启TV")
        
        // 先尝试关闭当前可能在运行的其他应用
        killForegroundApps()
        
        // 延迟一下再启动TV应用，确保之前的应用已经关闭
        Handler(Looper.getMainLooper()).postDelayed({
            // 重新启动TV应用
            if (currentTvPackage != null) {
                launchPackage(currentTvPackage!!)
                Log.d(TAG, "重新启动TV应用: $currentTvPackage")
            } else {
                openTvSource()
            }
        }, 300)
    }
    
    /**
     * 暂停TV信号源
     * 应用进入后台时调用，TV应用继续运行
     */
    fun pauseTvSource() {
        Log.d(TAG, "进入后台 - TV应用继续运行")
        // 不做任何操作，让TV应用继续在前台运行
    }
    
    /**
     * 停止TV信号源
     * 完全关闭时调用，杀死TV应用进程
     */
    fun stopTvSource() {
        Log.d(TAG, "停止信号源 - 杀死TV应用")
        if (currentTvPackage != null && activityManager != null) {
            try {
                activityManager.killBackgroundProcesses(currentTvPackage!!)
                Log.d(TAG, "已杀死TV应用: $currentTvPackage")
            } catch (e: Exception) {
                Log.e(TAG, "杀死TV进程失败", e)
            }
        }
        currentTvPackage = null
    }
    
    /**
     * 关闭前台运行的应用
     * 关闭除系统应用和本应用外的所有前台应用
     */
    private fun killForegroundApps() {
        if (activityManager == null) {
            return
        }
        
        try {
            val processes = activityManager.runningAppProcesses ?: return
            val myPid = android.os.Process.myPid()
            val myPackage = context.packageName
            
            for (process in processes) {
                // 跳过系统进程和本应用
                if (process.pid == myPid ||
                    process.processName.contains("android.system") ||
                    process.processName == myPackage) {
                    continue
                }
                
                // 只关闭前台进程
                if (process.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND) {
                    for (pkg in process.pkgList) {
                        // 不关闭系统关键应用
                        if (!pkg.startsWith("com.android.") &&
                            !pkg.startsWith("android.") &&
                            pkg != myPackage) {
                            try {
                                activityManager.killBackgroundProcesses(pkg)
                                Log.d(TAG, "关闭前台应用: $pkg")
                            } catch (e: Exception) {
                                Log.e(TAG, "无法关闭应用: $pkg", e)
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "关闭前台应用失败", e)
        }
    }
    
    /**
     * 关闭常见的TV应用进程
     * 杀死已知的TV应用进程
     */
    private fun killCommonTvProcesses() {
        val commonTvPackages = arrayOf(
            PKG_KONKA,
            PKG_HISENSE_UI,
            PKG_HISENSE_LIVETV,
            PKG_TCL,
            "com.android.tv",
            "com.google.android.tv"
        )
        
        for (packageName in commonTvPackages) {
            if (isPackageInstalled(packageName)) {
                try {
                    activityManager?.killBackgroundProcesses(packageName)
                    Log.d(TAG, "尝试杀死TV应用: $packageName")
                } catch (e: Exception) {
                    Log.e(TAG, "杀死进程失败: $packageName", e)
                }
            }
        }
    }
    
    /**
     * 检查应用包是否已安装
     * @param packageName 应用包名
     * @return 是否已安装
     */
    private fun isPackageInstalled(packageName: String): Boolean {
        return try {
            context.packageManager.getPackageInfo(packageName, 0)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
    }
    
    /**
     * 通过包名启动应用
     * @param packageName 应用包名
     * @return 是否成功启动
     */
    private fun launchPackage(packageName: String): Boolean {
        if (!isPackageInstalled(packageName)) {
            return false
        }
        
        return try {
            val intent = context.packageManager.getLaunchIntentForPackage(packageName)
            if (intent != null) {
                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                context.startActivity(intent)
                currentTvPackage = packageName
                true
            } else {
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "启动应用失败: $packageName", e)
            false
        }
    }
}
