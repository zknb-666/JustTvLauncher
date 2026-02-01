package cf.zknb.tvlauncher.tvinput

import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * TV输入源切换工具类
 * 
 * 统一管理各品牌电视的输入源切换逻辑
 * 支持TCL、康佳、海信品牌
 * 
 * @author JustTvLauncher
 */
object TvInputSourceSwitcher {
    
    private const val TAG = "TvInputSourceSwitcher"
    
    /**
     * 切换TV输入源停止音频
     * 
     * @param context 上下文
     * @return 是否成功切换
     */
    fun switchTvInputSource(context: Context): Boolean {
        return try {
            val brand = android.os.Build.BRAND.uppercase()
            Log.d(TAG, "检测到电视品牌: $brand")
            
            var success = false
            
            // 尝试TCL
            if (brand.contains("TCL")) {
                success = switchTclInputSource(context)
            }
            
            // 尝试康佳
            if (brand.contains("KONKA") && !success) {
                success = switchKonkaInputSource(context)
            }
            
            // 尝试海信
            if ((brand.contains("HISENSE") || brand.contains("VIDAA")) && !success) {
                success = switchHisenseInputSource(context)
            }
            
            if (success) {
                Log.d(TAG, "已切换TV输入源")
            } else {
                Log.d(TAG, "当前品牌($brand)暂不支持自动切换输入源")
            }
            
            success
        } catch (e: Exception) {
            Log.e(TAG, "切换TV输入源失败", e)
            false
        }
    }
    
    /**
     * TCL电视切换输入源到STORAGE
     */
    private fun switchTclInputSource(context: Context): Boolean {
        return try {
            val managerClass = Class.forName("com.tcl.tvmanager.TTvCommonManager")
            val managerInstance = managerClass
                .getMethod("getInstance", Context::class.java)
                .invoke(null, context)
            
            val inputSourceClass = Class.forName("com.tcl.tvmanager.vo.EnTCLInputSource")
            val enumConstants = inputSourceClass.enumConstants
            
            if (enumConstants != null && enumConstants.size > 17) {
                managerClass.getMethod("setInputSource", inputSourceClass)
                    .invoke(managerInstance, enumConstants[17]) // STORAGE
                Log.d(TAG, "TCL: 已切换TV输入源到STORAGE")
                true
            } else {
                false
            }
        } catch (e: ClassNotFoundException) {
            Log.d(TAG, "TCL: 未找到TTvCommonManager类")
            false
        } catch (e: Exception) {
            Log.e(TAG, "TCL: 切换输入源失败", e)
            false
        }
    }
    
    /**
     * 康佳电视关闭TV管理器并静音
     */
    private fun switchKonkaInputSource(context: Context): Boolean {
        var success = false
        
        // 1. 关闭所有TV管理器
        try {
            val managerClass = Class.forName("com.konka.android.tv.KKCommonManager")
            val managerInstance = managerClass
                .getMethod("getInstance", Context::class.java)
                .invoke(null, context)
            managerClass.getMethod("finalizeAllTVManager").invoke(managerInstance)
            Log.d(TAG, "康佳: 已调用finalizeAllTVManager")
            success = true
        } catch (e: ClassNotFoundException) {
            Log.d(TAG, "康佳: 未找到KKCommonManager类")
        } catch (e: Exception) {
            Log.e(TAG, "康佳: finalizeAllTVManager调用失败", e)
        }
        
        // 2. 静音当前输入源
        try {
            val audioClass = Class.forName("com.konka.android.tv.KKAudioManager")
            val audioInstance = audioClass
                .getMethod("getInstance", Context::class.java)
                .invoke(null, context)
            audioClass.getMethod("setCurrentInputSourceMuteEnable", Boolean::class.javaPrimitiveType)
                .invoke(audioInstance, true) // true表示静音
            Log.d(TAG, "康佳: 已静音当前输入源")
            success = true
        } catch (e: ClassNotFoundException) {
            Log.d(TAG, "康佳: 未找到KKAudioManager类")
        } catch (e: Exception) {
            Log.e(TAG, "康佳: 静音操作失败", e)
        }
        
        return success
    }
    
    /**
     * 海信电视通过广播停止TV应用
     */
    private fun switchHisenseInputSource(context: Context): Boolean {
        return try {
            // 海信电视没有直接的输入源切换API
            // 尝试发送停止直播电视的广播
            val stopIntent = Intent().apply {
                action = "com.hisense.livetvhome_STOP_LIVETV"
                setPackage("com.hisense.ui")
            }
            context.sendBroadcast(stopIntent)
            Log.d(TAG, "海信: 已发送停止直播电视广播")
            
            // 备选方案：发送Home键广播（可能不可靠）
            val homeIntent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(homeIntent)
            Log.d(TAG, "海信: 已尝试返回主屏幕")
            
            true
        } catch (e: Exception) {
            Log.e(TAG, "海信: 操作失败", e)
            false
        }
    }
}
