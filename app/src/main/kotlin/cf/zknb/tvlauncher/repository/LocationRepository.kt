package cf.zknb.tvlauncher.repository

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.jsse.provider.BouncyCastleJsseProvider
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.security.Security
import java.util.concurrent.TimeUnit

/**
 * IP定位仓库
 * 
 * 通过IP地址获取用户所在城市信息
 * 使用BouncyCastle（纯Java实现）支持Android 4.2的TLS
 */
class LocationRepository(private val context: Context) {

    companion object {
        private const val TAG = "LocationRepository"
        // 使用 优创API 高精度IP定位接口（https://apis.uctb.cn/api/high）
        // 不传 ip 参数时默认查询当前请求IP
        private const val IP_API_URL = "https://apis.uctb.cn/api/high"
        private const val TIMEOUT = 5000L
        
        // 初始化 BouncyCastle 以支持 Android 4.2+ 的 TLS 1.2（纯Java实现）
        init {
            try {
                // 移除已有的BC provider（如果存在）
                Security.removeProvider("BC")
                Security.removeProvider("BCJSSE")
                
                // 添加BouncyCastle provider
                Security.insertProviderAt(BouncyCastleProvider(), 1)
                Security.insertProviderAt(BouncyCastleJsseProvider(), 2)
                Log.d(TAG, "BouncyCastle TLS providers initialized")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize BouncyCastle", e)
            }
        }
    }
    
    // 创建支持 TLS 1.2 的 OkHttpClient，配置 BouncyCastle SSLContext
    private val okHttpClient by lazy {
        try {
            val trustManager = createTrustManager()
            
            // 获取BouncyCastle的SSLContext（使用BCJSSE provider）
            val sslContext = javax.net.ssl.SSLContext.getInstance("TLS", "BCJSSE")
            sslContext.init(null, arrayOf<javax.net.ssl.TrustManager>(trustManager), java.security.SecureRandom())
            
            OkHttpClient.Builder()
                .connectTimeout(TIMEOUT, TimeUnit.MILLISECONDS)
                .readTimeout(TIMEOUT, TimeUnit.MILLISECONDS)
                .writeTimeout(TIMEOUT, TimeUnit.MILLISECONDS)
                .sslSocketFactory(sslContext.socketFactory, trustManager)
                .hostnameVerifier { _, _ -> true } // 信任所有主机名
                .build()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to create OkHttpClient with BouncyCastle, using default", e)
            // 降级到默认配置
            OkHttpClient.Builder()
                .connectTimeout(TIMEOUT, TimeUnit.MILLISECONDS)
                .readTimeout(TIMEOUT, TimeUnit.MILLISECONDS)
                .writeTimeout(TIMEOUT, TimeUnit.MILLISECONDS)
                .build()
        }
    }
    
    /**
     * 创建一个信任所有证书的TrustManager（仅用于兼容老版本Android）
     * 注意：生产环境应该使用正确的证书验证
     */
    private fun createTrustManager(): javax.net.ssl.X509TrustManager {
        return object : javax.net.ssl.X509TrustManager {
            override fun checkClientTrusted(chain: Array<out java.security.cert.X509Certificate>?, authType: String?) {
            }
            
            override fun checkServerTrusted(chain: Array<out java.security.cert.X509Certificate>?, authType: String?) {
            }
            
            override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> {
                return arrayOf()
            }
        }
    }

    /**
     * 通过IP获取当前城市信息
     * @return Pair<城市名称, 省份名称> 或 null
     */
    suspend fun getCityByIp(): Pair<String, String>? = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "请求IP定位API: $IP_API_URL")
            
            val request = Request.Builder()
                .url(IP_API_URL) // 不传ip，接口自动使用当前请求IP
                .header("User-Agent", "Mozilla/5.0")
                .build()
            
            okHttpClient.newCall(request).execute().use { response ->
                val responseCode = response.code()
                Log.d(TAG, "IP定位API响应码: $responseCode")
                
                if (response.isSuccessful) {
                    val responseBody = response.body()?.string()
                    Log.d(TAG, "IP定位API响应内容: $responseBody")
                    
                    if (!responseBody.isNullOrEmpty()) {
                        try {
                            val root = JSONObject(responseBody)
                            val code = root.optInt("code", -1)
                            if (code == 200) {
                                val data = root.optJSONObject("data")
                                val province = data?.optString("province", "") ?: ""
                                val city = data?.optString("city", "") ?: ""
                                Log.d(TAG, "解析到省市: province=$province, city=$city")
                                if (province.isNotEmpty() && city.isNotEmpty()) {
                                    return@withContext Pair(city, province)
                                }
                            } else {
                                Log.w(TAG, "API返回非200状态: $code")
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "解析IP定位API响应失败", e)
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
