package cf.zknb.tvlauncher.cleaner

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.util.Log
import java.io.BufferedReader
import java.io.FileReader
import kotlin.math.roundToInt

/**
 * 内存清理工具类
 * 
 * 提供内存信息查询和清理功能
 * 
 * @author JustTvLauncher
 */
object MemoryCleaner {
    
    private const val TAG = "MemoryCleaner"
    
    /**
     * 内存信息数据类
     */
    data class MemoryInfo(
        val totalMemory: Long,      // 总内存 (字节)
        val availableMemory: Long,  // 可用内存 (字节)
        val usedMemory: Long,       // 已用内存 (字节)
        val usedPercentage: Int     // 使用百分比
    )
    
    /**
     * 获取内存信息
     */
    fun getMemoryInfo(context: Context): MemoryInfo {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memInfo)
        
        val totalMemory = memInfo.totalMem
        val availableMemory = memInfo.availMem
        val usedMemory = totalMemory - availableMemory
        val usedPercentage = ((usedMemory.toDouble() / totalMemory) * 100).roundToInt()
        
        Log.d(TAG, "总内存: ${formatMemorySize(totalMemory)}, " +
                "可用: ${formatMemorySize(availableMemory)}, " +
                "已用: ${formatMemorySize(usedMemory)} ($usedPercentage%)")
        
        return MemoryInfo(totalMemory, availableMemory, usedMemory, usedPercentage)
    }
    
    /**
     * 执行内存清理
     * 清理后台应用进程
     * 
     * @return 清理释放的内存大小（字节）
     */
    fun cleanMemory(context: Context): Long {
        val memoryBefore = getMemoryInfo(context)
        
        try {
            val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            
            // 获取运行中的进程
            val runningApps = activityManager.runningAppProcesses
            if (runningApps != null) {
                val myPackage = context.packageName
                var killedCount = 0
                
                for (processInfo in runningApps) {
                    // 不要杀死自己的进程
                    if (processInfo.processName == myPackage) {
                        continue
                    }
                    
                    // 只杀死后台进程
                    if (processInfo.importance >= ActivityManager.RunningAppProcessInfo.IMPORTANCE_BACKGROUND) {
                        try {
                            activityManager.killBackgroundProcesses(processInfo.processName)
                            killedCount++
                            Log.d(TAG, "清理进程: ${processInfo.processName}")
                        } catch (e: Exception) {
                            Log.e(TAG, "清理进程失败: ${processInfo.processName}", e)
                        }
                    }
                }
                
                Log.d(TAG, "已清理 $killedCount 个后台进程")
            }
            
            // 请求垃圾回收
            System.gc()
            
            // 等待一下让系统有时间回收内存
            Thread.sleep(500)
            
            val memoryAfter = getMemoryInfo(context)
            val freedMemory = memoryAfter.availableMemory - memoryBefore.availableMemory
            
            if (freedMemory > 0) {
                Log.d(TAG, "释放内存: ${formatMemorySize(freedMemory)}")
            } else {
                Log.d(TAG, "内存清理完成，无明显释放")
            }
            
            return if (freedMemory > 0) freedMemory else 0
            
        } catch (e: Exception) {
            Log.e(TAG, "内存清理失败", e)
            return 0
        }
    }
    
    /**
     * 获取系统总内存（从/proc/meminfo）
     */
    fun getSystemTotalMemory(): Long {
        return try {
            val reader = BufferedReader(FileReader("/proc/meminfo"))
            val line = reader.readLine() // MemTotal行
            reader.close()
            
            // 解析 "MemTotal:        xxxx kB"
            val parts = line.split("\\s+".toRegex())
            if (parts.size >= 2) {
                val memKB = parts[1].toLongOrNull() ?: 0L
                memKB * 1024 // 转换为字节
            } else {
                0L
            }
        } catch (e: Exception) {
            Log.e(TAG, "读取系统内存信息失败", e)
            0L
        }
    }
    
    /**
     * 格式化内存大小显示
     */
    fun formatMemorySize(bytes: Long): String {
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "${(bytes / 1024.0).roundToInt()} KB"
            bytes < 1024 * 1024 * 1024 -> "${(bytes / (1024.0 * 1024)).roundToInt()} MB"
            else -> String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024))
        }
    }
    
    /**
     * 获取内存使用百分比文本
     */
    fun getMemoryPercentageText(context: Context): String {
        val memInfo = getMemoryInfo(context)
        return "${memInfo.usedPercentage}%"
    }
    
    /**
     * 获取内存状态描述
     */
    fun getMemoryStatusText(context: Context): String {
        val memInfo = getMemoryInfo(context)
        return "${formatMemorySize(memInfo.availableMemory)} 可用 / ${formatMemorySize(memInfo.totalMemory)}"
    }
    
    /**
     * 检查内存是否不足
     */
    fun isLowMemory(context: Context): Boolean {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memInfo)
        return memInfo.lowMemory
    }
}
