package cf.zknb.tvlauncher.settings

import android.content.Context
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.leanback.app.GuidedStepSupportFragment
import androidx.leanback.widget.GuidanceStylist
import androidx.leanback.widget.GuidedAction
import androidx.lifecycle.lifecycleScope
import cf.zknb.tvlauncher.R
import cf.zknb.tvlauncher.model.ProvinceData
import cf.zknb.tvlauncher.repository.LocationRepository
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.InputStreamReader
import kotlinx.coroutines.launch

/**
 * 天气地区设置Fragment
 *
 * 使用省市二级联动选择天气显示的城市
 */
class WeatherRegionSettingsFragment : GuidedStepSupportFragment() {

    companion object {
        private const val TAG = "WeatherRegionSettings"
        private const val PREFS_NAME = "weather_settings"
        private const val KEY_CITY_NAME = "city_name"
        private const val KEY_ADCODE = "adcode"
        private const val ACTION_AUTO_LOCATE = -1L
    }

    private var provinceDataMap: Map<String, ProvinceData>? = null
    private var locationRepository: LocationRepository? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        loadAreaData()
        locationRepository = LocationRepository(requireContext())
    }

    /**
     * 从 assets 加载区域数据
     */
    private fun loadAreaData() {
        try {
            val inputStream = requireContext().assets.open("all_area_with_adcode_key.json")
            val reader = InputStreamReader(inputStream)
            val type = object : TypeToken<Map<String, ProvinceData>>() {}.type
            provinceDataMap = Gson().fromJson(reader, type)
            reader.close()
            Log.d(TAG, "Loaded ${provinceDataMap?.size} provinces")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load area data", e)
        }
    }

    override fun onCreateGuidance(savedInstanceState: Bundle?): GuidanceStylist.Guidance {
        val prefs = requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val currentCity = prefs.getString(KEY_CITY_NAME, "北京市") ?: "北京市"
        
        return GuidanceStylist.Guidance(
            getString(R.string.settings_weather_region),
            getString(R.string.weather_city_selected, currentCity),
            getString(R.string.back),
            null
        )
    }

    override fun onCreateActions(actions: MutableList<GuidedAction>, savedInstanceState: Bundle?) {
        val context = requireContext()
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val currentAdcode = prefs.getString(KEY_ADCODE, "110100")
        
        // 添加自动定位选项
        actions.add(
            GuidedAction.Builder(context)
                .id(ACTION_AUTO_LOCATE)
                .title("📍 自动定位")
                .description("根据IP地址自动获取当前城市")
                .build()
        )
        
        // 如果数据还未加载，先加载
        if (provinceDataMap == null) {
            loadAreaData()
        }
        
        val dataMap = provinceDataMap
        if (dataMap == null || dataMap.isEmpty()) {
            Log.e(TAG, "Province data is null or empty in onCreateActions")
            return
        }
        
        Log.d(TAG, "Creating actions for ${dataMap.size} provinces")
        
        dataMap.values.sortedBy { it.id }.forEach { province ->
            val subActions = mutableListOf<GuidedAction>()
            
            // 为每个省份创建城市子选项
            province.cities.values.sortedBy { it.id }.forEach { city ->
                val isSelected = city.cityAdcode.toString() == currentAdcode
                subActions.add(
                    GuidedAction.Builder(context)
                        .id(city.cityAdcode.toLong())
                        .title(city.cityName)
                        .description(if (isSelected) "✓ 当前选择" else "")
                        .build()
                )
            }
            
            // 创建省份主选项
            actions.add(
                GuidedAction.Builder(context)
                    .id(province.provinceAdcode.toLong())
                    .title(province.provinceName)
                    .description("${province.cities.size}个城市")
                    .subActions(subActions)
                    .build()
            )
        }
        
        Log.d(TAG, "Created ${actions.size} province actions")
    }
    
    override fun onGuidedActionClicked(action: GuidedAction) {
        if (action.id == ACTION_AUTO_LOCATE) {
            performAutoLocation()
        } else {
            super.onGuidedActionClicked(action)
        }
    }
    
    /**
     * 执行自动定位
     */
    private fun performAutoLocation() {
        Toast.makeText(requireContext(), "正在定位...", Toast.LENGTH_SHORT).show()
        
        lifecycleScope.launch {
            try {
                Log.d(TAG, "开始自动定位...")
                val adcode = locationRepository?.autoLocate()
                Log.d(TAG, "autoLocate() 返回adcode: $adcode")
                if (adcode != null) {
                    Log.d(TAG, "自动定位成功: adcode=$adcode")
                    // 保存到SharedPreferences
                    val prefs = requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    prefs.edit()
                        .putString(KEY_ADCODE, adcode)
                        .apply()
                    Toast.makeText(requireContext(), "定位成功", Toast.LENGTH_SHORT).show()
                    // 返回上一页
                    finishGuidedStepSupportFragments()
                } else {
                    Log.w(TAG, "自动定位失败，未获取到adcode")
                    Toast.makeText(requireContext(), "定位失败，请手动选择城市", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Auto location error", e)
                Toast.makeText(requireContext(), "定位出错：${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onSubGuidedActionClicked(action: GuidedAction): Boolean {
        val cityAdcode = action.id.toString()
        val cityName = action.title.toString()
        
        // 保存选择
        val prefs = requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putString(KEY_CITY_NAME, cityName)
            .putString(KEY_ADCODE, cityAdcode)
            .apply()
        
        Log.d(TAG, "Selected city: $cityName ($cityAdcode)")
        Toast.makeText(requireContext(), getString(R.string.weather_city_selected, cityName), Toast.LENGTH_SHORT).show()
        
        // 更新所有subactions的描述，清除旧标记并添加新标记
        actions.forEach { provinceAction ->
            provinceAction.subActions?.forEach { cityAction ->
                cityAction.description = if (cityAction.id == action.id) "✓ 当前选择" else ""
            }
        }
        
        // 返回上一级（关闭子选项）
        return true
    }
}
