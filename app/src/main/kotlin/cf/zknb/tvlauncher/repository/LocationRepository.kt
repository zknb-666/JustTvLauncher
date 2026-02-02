package cf.zknb.tvlauncher.repository

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.conscrypt.Conscrypt
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.security.Security
import java.util.concurrent.TimeUnit

/**
 * IP定位仓库
 * 
 * 通过IP地址获取用户所在城市信息
 */
class LocationRepository(private val context: Context) {

    companion object {
        private const val TAG = "LocationRepository"
        // 使用 myip.ipip.net API
        private const val IP_API_URL = "https://myip.ipip.net/"
        private const val TIMEOUT = 5000L
        
        // 初始化 Conscrypt 以支持 Android 4.2+ 的 TLS 1.2
        init {
            try {
                Security.insertProviderAt(Conscrypt.newProvider(), 1)
                Log.d(TAG, "Conscrypt TLS provider initialized")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize Conscrypt", e)
            }
        }
    }
    
    // 创建支持 TLS 1.2 的 OkHttpClient
    private val okHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(TIMEOUT, TimeUnit.MILLISECONDS)
            .readTimeout(TIMEOUT, TimeUnit.MILLISECONDS)
            .writeTimeout(TIMEOUT, TimeUnit.MILLISECONDS)
            .build()
    }

    /**
     * 通过IP获取当前城市信息
     * @return Pair<城市名称, 省份名称> 或 null
     */
    suspend fun getCityByIp(): Pair<String, String>? = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "请求IP定位API: $IP_API_URL")
            
            val request = Request.Builder()
                .url(IP_API_URL)
                .header("User-Agent", "Mozilla/5.0")
                .build()
            
            okHttpClient.newCall(request).execute().use { response ->
                val responseCode = response.code()
                Log.d(TAG, "IP定位API响应码: $responseCode")
                
                if (response.isSuccessful) {
                    val responseBody = response.body()?.string()
                    Log.d(TAG, "IP定位API响应内容: $responseBody")
                    
                    if (responseBody != null) {
                        // myip.ipip.net 返回格式: "当前 IP：xxx.xxx.xxx.xxx  来自于：中国 湖南 永州  移动"
                        // 支持中文冒号和英文冒号
                        val locationPart = responseBody.split("来自于[：:]".toRegex()).getOrNull(1)?.trim()
                        if (locationPart != null) {
                            // 按空格分割，并过滤空字符串（处理多余空格）
                            val parts = locationPart.split("\\s+".toRegex()).filter { it.isNotEmpty() }
                            Log.d(TAG, "解析到的位置信息部分: $parts (size=${parts.size})")
                            // 格式: [国家, 省份, 城市, 运营商(可选)]
                            if (parts.size >= 3) {
                                val country = parts[0]
                                val province = parts[1]
                                val city = parts[2]
                                Log.d(TAG, "IP定位成功: 国家=$country, 省份=$province, 城市=$city")
                                if (city.isNotEmpty() && province.isNotEmpty()) {
                                    return@withContext Pair(city, province)
                                }
                            } else {
                                Log.w(TAG, "位置信息部分数量不足: $parts")
                            }
                        } else {
                            Log.w(TAG, "无法解析响应格式: $responseBody")
                        }
                    }
                }
            }
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
            val inputStream = context.assets.open("all_area_with_adcode_key.json")
            val reader = BufferedReader(InputStreamReader(inputStream))
            val jsonString = reader.readText()
            reader.close()
            val json = JSONObject(jsonString as String)
            val provinceKeys = json.keys()
            while (provinceKeys.hasNext()) {
                val provinceKey = provinceKeys.next()
                val provinceObj = json.getJSONObject(provinceKey)
                val provinceNameInData = provinceObj.getString("province_name")
                if (provinceName.contains(provinceNameInData.replace("省", "").replace("市", "").replace("自治区", "").replace("特别行政区", ""), ignoreCase = false) ||
                    provinceNameInData.contains(provinceName.replace("省", "").replace("市", "").replace("自治区", "").replace("特别行政区", ""), ignoreCase = false)) {
                    val citiesObj = provinceObj.getJSONObject("city")
                    val cityKeys = citiesObj.keys()
                    while (cityKeys.hasNext()) {
                        val cityKey = cityKeys.next()
                        val cityObj = citiesObj.getJSONObject(cityKey)
                        val cityNameInData = cityObj.getString("city_name")
                        val cityAdcode = cityObj.getString("city_adcode")
                        val cityNameSimplified = cityName.replace("市", "")
                        val cityNameInDataSimplified = cityNameInData.replace("市", "")
                        Log.d(TAG, "对比: $cityNameSimplified vs $cityNameInDataSimplified")
                        if (cityNameSimplified == cityNameInDataSimplified ||
                            cityNameInData.contains(cityNameSimplified, ignoreCase = false) ||
                            cityNameSimplified.contains(cityNameInDataSimplified, ignoreCase = false)) {
                            Log.d(TAG, "找到匹配城市: $cityNameInData (adcode: $cityAdcode)")
                            return@withContext cityAdcode
                        }
                    }
                }
            }
            Log.w(TAG, "未找到匹配的城市: $provinceName $cityName")
            null
        } catch (e: Exception) {
            Log.e(TAG, "查找adcode失败", e)
            null
        }
    }

    /**
     * 自动定位并获取城市adcode
     * @return Pair<城市名称, adcode> 或 null
     */
    suspend fun autoLocate(): Pair<String, String>? {
        Log.d(TAG, "autoLocate() 开始...")
        val location = getCityByIp()
        Log.d(TAG, "getCityByIp() 返回: $location")
        if (location == null) return null
        val (cityName, provinceName) = location
        val adcode = findAdcodeByCity(cityName, provinceName)
        Log.d(TAG, "findAdcodeByCity() 返回: $adcode")
        if (adcode == null) return null
        return Pair(cityName, adcode)
    }
}
