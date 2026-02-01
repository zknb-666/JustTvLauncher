package cf.zknb.tvlauncher.repository

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import cf.zknb.tvlauncher.model.AreaData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.lionsoul.ip2region.xdb.Searcher
import org.lionsoul.ip2region.xdb.Version
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * IP定位仓库
 * 
 * 使用 ip2region 离线 IP 库进行高性能定位
 */
class LocationRepository(private val context: Context) {

    companion object {
        private const val TAG = "LocationRepository"
        private const val XDB_FILE_NAME = "ip2region_v4.xdb"
        private const val IP_CHECK_URL = "https://api.ipify.org?format=text"
        private const val TIMEOUT = 5000
    }

    private var searcher: Searcher? = null

    /**
     * 初始化 ip2region searcher
     */
    private suspend fun initSearcher() = withContext(Dispatchers.IO) {
        if (searcher != null) return@withContext
        
        try {
            // 从 assets 复制 xdb 文件到内部存储
            val xdbFile = File(context.filesDir, XDB_FILE_NAME)
            if (!xdbFile.exists()) {
                Log.d(TAG, "复制xdb文件到内部存储")
                context.assets.open(XDB_FILE_NAME).use { input ->
                    xdbFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
            }
            
            // 创建 searcher，使用 VIndexCache 缓存策略
            Log.d(TAG, "初始化 ip2region searcher")
            searcher = Searcher.newWithVectorIndex(Version.IPv4, xdbFile.absolutePath)
            Log.d(TAG, "ip2region searcher 初始化成功")
        } catch (e: Exception) {
            Log.e(TAG, "初始化 ip2region searcher 失败", e)
            throw e
        }
    }

    /**
     * 获取当前公网IP地址
     */
    private suspend fun getCurrentIp(): String? = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "获取当前公网IP")
            val url = URL(IP_CHECK_URL)
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = TIMEOUT
            connection.readTimeout = TIMEOUT
            
            val responseCode = connection.responseCode
            if (responseCode == HttpURLConnection.HTTP_OK) {
                val ip = connection.inputStream.bufferedReader().readText().trim()
                Log.d(TAG, "当前公网IP: $ip")
                return@withContext ip
            }
            connection.disconnect()
            null
        } catch (e: Exception) {
            Log.e(TAG, "获取公网IP失败", e)
            null
        }
    }

    /**
     * 通过IP获取当前城市信息
     * @return Pair<城市名称, 省份名称> 或 null
     */
    suspend fun getCityByIp(): Pair<String, String>? = withContext(Dispatchers.IO) {
        try {
            // 确保 searcher 已初始化
            if (searcher == null) {
                initSearcher()
            }
            
            // 获取当前公网IP
            val currentIp = getCurrentIp()
            if (currentIp == null) {
                Log.w(TAG, "无法获取公网IP")
                return@withContext null
            }
            
            // 使用 ip2region 查询 IP 归属地
            Log.d(TAG, "查询IP归属地: $currentIp")
            val region = searcher?.search(currentIp)
            
            if (region.isNullOrEmpty()) {
                Log.w(TAG, "IP定位失败: 无法获取地区信息")
                return@withContext null
            }
            
            Log.d(TAG, "IP定位原始结果: $region")
            // 解析结果格式: 国家|省份|城市|ISP|CN
            val parts = region.split("|")
            if (parts.size >= 3) {
                val province = parts[1].trim()
                val city = parts[2].trim()
                Log.d(TAG, "IP定位成功: 省份=$province, 城市=$city")
                if (city.isNotEmpty() && province.isNotEmpty()) {
                    return@withContext Pair(city, province)
                }
            }
            
            Log.w(TAG, "IP定位失败: 无法解析地区信息")
            null
        } catch (e: Exception) {
            Log.e(TAG, "IP定位失败", e)
            null
        }
    }

    /**
     * 根据城市名称查找对应的adcode
     * 从all_area_with_adcode_key.json中匹配城市
     * @param cityName 城市名称（如"北京市"）
     * @param provinceName 省份名称（如"北京市"）
     * @return adcode 或 null
     */
    suspend fun findAdcodeByCity(cityName: String, provinceName: String): String? = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "查找adcode: cityName=$cityName, provinceName=$provinceName")
            
            // 读取并解析 JSON
            val inputStream = context.assets.open("all_area_with_adcode_key.json")
            val reader = BufferedReader(InputStreamReader(inputStream, Charsets.UTF_8))
            val jsonString = reader.readText()
            reader.close()
            
            val gson = Gson()
            val areaData = gson.fromJson(jsonString, AreaData::class.java)
            
            // 清理输入的城市和省份名称
            val cleanCity = cityName.replace("市", "").replace("区", "").replace("县", "").trim()
            val cleanProvince = provinceName.replace("省", "").replace("市", "").replace("自治区", "")
                .replace("特别行政区", "").replace("壮族", "").replace("回族", "").replace("维吾尔", "").trim()
            
            Log.d(TAG, "清理后: cleanCity=$cleanCity, cleanProvince=$cleanProvince")
            
            // 遍历所有省份
            for ((_, provinceData) in areaData) {
                val dataProvinceName = provinceData.province_name.replace("省", "").replace("市", "")
                    .replace("自治区", "").replace("特别行政区", "").replace("壮族", "")
                    .replace("回族", "").replace("维吾尔", "").trim()
                
                // 匹配省份
                if (cleanProvince.contains(dataProvinceName) || dataProvinceName.contains(cleanProvince)) {
                    Log.d(TAG, "找到匹配省份: ${provinceData.province_name}")
                    
                    // 遍历该省份下的城市
                    for ((_, cityData) in provinceData.city) {
                        val dataCityName = cityData.city_name.replace("市", "").replace("区", "")
                            .replace("县", "").trim()
                        
                        // 匹配城市
                        if (cleanCity.contains(dataCityName) || dataCityName.contains(cleanCity)) {
                            Log.d(TAG, "找到匹配城市: ${cityData.city_name}, adcode=${cityData.city_adcode}")
                            return@withContext cityData.city_adcode
                        }
                    }
                }
            }
            
            Log.w(TAG, "未找到匹配的adcode")
            null
        } catch (e: Exception) {
            Log.e(TAG, "查找adcode失败", e)
            null
        }
    }

    /**
     * 自动定位：获取IP位置并查找对应的adcode
     * @return adcode 或 null
     */
    suspend fun autoLocate(): String? {
        Log.d(TAG, "开始自动定位")
        val location = getCityByIp()
        if (location == null) {
            Log.w(TAG, "自动定位失败: 无法获取IP位置")
            return null
        }
        
        val (city, province) = location
        Log.d(TAG, "IP定位成功: 城市=$city, 省份=$province")
        
        val adcode = findAdcodeByCity(city, province)
        if (adcode == null) {
            Log.w(TAG, "自动定位失败: 无法找到对应的adcode")
        } else {
            Log.d(TAG, "自动定位成功: adcode=$adcode")
        }
        
        return adcode
    }

    /**
     * 释放资源
     */
    fun close() {
        try {
            searcher?.close()
            searcher = null
            Log.d(TAG, "ip2region searcher 已关闭")
        } catch (e: Exception) {
            Log.e(TAG, "关闭 searcher 失败", e)
        }
    }
}
