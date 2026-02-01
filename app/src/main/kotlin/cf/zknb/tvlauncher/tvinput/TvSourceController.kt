package cf.zknb.tvlauncher.tvinput

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log

/**
 * TV信号源控制器
 * 
 * 负责电视信号源的启动和管理，支持多品牌电视适配
 * 
 * 支持的品牌：
 * - TCL电视
 * - 康佳电视（KONKA）
 * - 海信电视（HISENSE）
 * 
 * @author JustTvLauncher
 */
class TvSourceController(private val context: Context) {
    
    companion object {
        private const val TAG = "TvSourceController"
        
        // 品牌标识
        private const val BRAND_KONKA = "KONKA"
        private const val BRAND_HISENSE = "HISENSE"
        private const val BRAND_TCL = "TCL"
        
        // TV应用包名
        private const val PKG_KONKA = "com.konka.tvsettings"
        private const val PKG_HISENSE_UI = "com.hisense.ui"
        private const val PKG_HISENSE_LIVETV = "com.jamdeo.tv.livetv"
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
    
    private val tvBrand: String = detectTvBrand()
    
    /**
     * 检测电视品牌
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
     */
    private fun openKonkaTvSource(): Boolean {
        return try {
            if (isPackageInstalled(PKG_KONKA)) {
                val intent = Intent().apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                    setClassName(PKG_KONKA, "$PKG_KONKA.RootActivity")
                }
                context.startActivity(intent)
                Log.d(TAG, "成功启动康佳电视信号源")
                true
            } else {
                Log.w(TAG, "康佳电视应用未安装")
                false
            }
        } catch (e: ActivityNotFoundException) {
            Log.e(TAG, "康佳电视应用未找到", e)
            false
        }
    }
    
    /**
     * 打开海信电视信号源
     */
    private fun openHisenseTvSource(): Boolean {
        // 优先尝试海信UI应用
        if (isPackageInstalled(PKG_HISENSE_UI)) {
            return try {
                val intent = context.packageManager.getLaunchIntentForPackage(PKG_HISENSE_UI)
                if (intent != null) {
                    intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                    context.startActivity(intent)
                    Log.d(TAG, "成功启动海信电视UI")
                    true
                } else {
                    false
                }
            } catch (e: ActivityNotFoundException) {
                Log.e(TAG, "海信电视UI启动失败", e)
                false
            }
        }
        
        // 尝试海信直播TV应用
        if (isPackageInstalled(PKG_HISENSE_LIVETV)) {
            return try {
                val intent = context.packageManager.getLaunchIntentForPackage(PKG_HISENSE_LIVETV)
                if (intent != null) {
                    intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                    context.startActivity(intent)
                    Log.d(TAG, "成功启动海信直播TV")
                    true
                } else {
                    false
                }
            } catch (e: ActivityNotFoundException) {
                Log.e(TAG, "海信直播TV启动失败", e)
                false
            }
        }
        
        Log.w(TAG, "海信电视应用未找到")
        return false
    }
    
    /**
     * 打开TCL电视信号源
     */
    private fun openTclTvSource(): Boolean {
        return try {
            if (isPackageInstalled(PKG_TCL)) {
                val intent = context.packageManager.getLaunchIntentForPackage(PKG_TCL)
                if (intent != null) {
                    intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                    context.startActivity(intent)
                    Log.d(TAG, "成功启动TCL电视")
                    true
                } else {
                    // 尝试通过反射API启动
                    openTclByReflection()
                }
            } else {
                Log.w(TAG, "TCL电视应用未安装")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "TCL电视启动失败", e)
            false
        }
    }
    
    /**
     * 通过反射API打开TCL电视信号源
     */
    private fun openTclByReflection(): Boolean {
        return try {
            val managerClass = Class.forName("com.tcl.tvmanager.TTvCommonManager")
            val managerInstance = managerClass
                .getMethod("getInstance", Context::class.java)
                .invoke(null, context)
            
            val inputSourceClass = Class.forName("com.tcl.tvmanager.vo.EnTCLInputSource")
            val enumConstants = inputSourceClass.enumConstants
            
            if (enumConstants != null && enumConstants.isNotEmpty()) {
                // 切换到TV输入源（通常是索引0或1）
                managerClass.getMethod("setInputSource", inputSourceClass)
                    .invoke(managerInstance, enumConstants[0])
                Log.d(TAG, "TCL: 通过反射API成功切换到TV输入源")
                true
            } else {
                Log.w(TAG, "TCL: 枚举常量为空")
                false
            }
        } catch (e: ClassNotFoundException) {
            Log.d(TAG, "TCL: 未找到TTvCommonManager类")
            false
        } catch (e: Exception) {
            Log.e(TAG, "TCL: 反射API调用失败", e)
            false
        }
    }
    
    /**
     * 打开通用电视信号源
     */
    private fun openGenericTvSource(): Boolean {
        // 尝试查找常见的TV应用
        val tvPackages = listOf(PKG_TCL, PKG_KONKA, PKG_HISENSE_UI, PKG_HISENSE_LIVETV)
        
        for (pkg in tvPackages) {
            if (isPackageInstalled(pkg)) {
                return try {
                    val intent = context.packageManager.getLaunchIntentForPackage(pkg)
                    if (intent != null) {
                        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                        context.startActivity(intent)
                        Log.d(TAG, "成功启动TV应用: $pkg")
                        return true
                    }
                    false
                } catch (e: ActivityNotFoundException) {
                    Log.e(TAG, "TV应用启动失败: $pkg", e)
                    continue
                }
            }
        }
        
        Log.w(TAG, "未找到可用的TV应用")
        return false
    }
    
    /**
     * 检查应用是否已安装
     */
    private fun isPackageInstalled(packageName: String): Boolean {
        return try {
            context.packageManager.getPackageInfo(packageName, 0)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
    }
}
