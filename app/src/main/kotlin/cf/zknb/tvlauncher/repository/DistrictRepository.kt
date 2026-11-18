package cf.zknb.tvlauncher.repository

import android.util.Log
import cf.zknb.tvlauncher.model.DistrictItem
import cf.zknb.tvlauncher.util.HttpCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * 行政区划查询仓库。
 *
 * 本地只保留省级数据，市 / 区两级按需请求：
 *   GET https://uapis.cn/api/v1/misc/district?adcode={父级adcode}&limit=100
 * 响应形如 {"total":N,"results":[{name,level,adcode,...}]}，
 * results 里第一条是父级自身，其余为直接下级；服务端 level 参数不生效，
 * 因此这里按 level 字段本地过滤出 city / district。
 *
 * 网络统一走 [HttpCompat]（Conscrypt native TLS + HTTP 兜底）。
 * 已查过的 adcode 在进程内缓存，电视遥控器来回切换省份时不会重复联网。
 * 无下级（如台湾省、东莞、儋州）返回空列表，由 UI 回退使用父级 adcode。
 */
object DistrictRepository {

    private const val TAG = "DistrictRepository"
    private const val API_URL = "https://uapis.cn/api/v1/misc/district"
    private const val TIMEOUT_MS = 8000
    private const val LIMIT = 100

    private val cache = ConcurrentHashMap<String, List<DistrictItem>>()

    /**
     * 查询 [parentAdcode] 的直接下级。
     * @param childLevel 目标层级，"city" 或 "district"
     * @return 下级列表；请求 / 解析失败返回 null（UI 据此提示重试，区别于「确实没有下级」）
     */
    suspend fun children(parentAdcode: String, childLevel: String): List<DistrictItem>? =
        withContext<List<DistrictItem>?>(Dispatchers.IO) {
            val key = "$parentAdcode:$childLevel"
            cache[key]?.let {
                Log.d(TAG, "$key 命中缓存 (${it.size})")
                return@withContext it
            }

            val url = "$API_URL?adcode=$parentAdcode&limit=$LIMIT"
            val body = HttpCompat.getString(url, TIMEOUT_MS, TIMEOUT_MS)
            if (body == null) {
                Log.w(TAG, "区划接口请求失败: $key")
                return@withContext null
            }

            val list = try {
                val root = JSONObject(body)
                val results = root.optJSONArray("results")
                if (results == null) {
                    Log.w(TAG, "响应缺少 results: ${body.take(160)}")
                    return@withContext null
                }
                val out = ArrayList<DistrictItem>(results.length())
                for (i in 0 until results.length()) {
                    val item = results.optJSONObject(i) ?: continue
                    // 跳过父级自身及其它层级（例如街道）
                    if (item.optString("level") != childLevel) continue
                    val adcode = item.optString("adcode", "")
                    val name = item.optString("name", "")
                    if (adcode.isEmpty() || name.isEmpty()) continue
                    out.add(DistrictItem(name, adcode))
                }
                out
            } catch (e: Exception) {
                Log.e(TAG, "区划响应解析失败: ${e.message}", e)
                return@withContext null
            }

            cache[key] = list
            Log.d(TAG, "$key -> ${list.size} 个下级")
            list
        }
}
