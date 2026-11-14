package cf.zknb.tvlauncher.settings

import android.content.Context
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import android.app.AlertDialog
import android.view.LayoutInflater
import android.view.View
import android.widget.ArrayAdapter
import android.widget.AdapterView
import android.widget.Spinner
import androidx.leanback.app.GuidedStepSupportFragment
import androidx.leanback.widget.GuidanceStylist
import androidx.leanback.widget.GuidedAction
import androidx.lifecycle.lifecycleScope
import cf.zknb.tvlauncher.R
import cf.zknb.tvlauncher.model.ProvinceData
import cf.zknb.tvlauncher.model.DistrictData
import cf.zknb.tvlauncher.repository.WeatherRepository
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.InputStreamReader
import kotlinx.coroutines.launch

/**
 * 天气地区设置Fragment
 *
 * 通过天气 API 自动或手动选择城市。手动选择采用省→市→区三级联动对话框替代原有的 GuidedStep 子列表方式。
 */
class WeatherRegionSettingsFragment : GuidedStepSupportFragment() {

    companion object {
        private const val TAG = "WeatherRegionSettings"
        private const val PREFS_NAME = "weather_settings"
        private const val KEY_CITY_NAME = "city_name"
        private const val KEY_ADCODE = "adcode"
        private const val KEY_USE_IP_LOCATION = "use_ip_location"
        private const val ACTION_AUTO_LOCATE = -1L
        private const val ACTION_MANUAL_SELECT = -3L
        private const val CHECK_SET_ID = 1
    }

    private var provinceDataMap: Map<String, ProvinceData>? = null
    private lateinit var weatherRepository: WeatherRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        loadAreaData()
        weatherRepository = WeatherRepository(requireContext())
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
                .description("通过天气API自动获取当前城市")
                .build()
        )
        
        
        // 添加手动选择城市选项
        actions.add(
            GuidedAction.Builder(context)
                .id(ACTION_MANUAL_SELECT)
                .title("手动选择城市")
                .description("省⇢市⇢区三级选择")
                .build()
        )
        
        // 确保区县数据已经加载，以备后续弹窗使用
        if (provinceDataMap == null) {
            loadAreaData()
        }
    }
    
    override fun onGuidedActionClicked(action: GuidedAction) {
        when (action.id) {
            ACTION_AUTO_LOCATE -> {
                performAutoLocation()
            }
            ACTION_MANUAL_SELECT -> {
                showThreeLevelPicker()
            }
            else -> {
                super.onGuidedActionClicked(action)
            }
        }
    }
    
    
    /**
     * 执行自动定位
     */
    private fun performAutoLocation() {
        // 使用 applicationContext，避免协程挂起期间 Fragment/Activity 被销毁后
        // requireContext() 在 catch 块中再次抛异常导致崩溃（之前的闪退隐患之一）
        val appContext = requireActivity().applicationContext
        Toast.makeText(appContext, "正在定位...", Toast.LENGTH_SHORT).show()

        lifecycleScope.launch {
            try {
                Log.d(TAG, "开始自动定位...")
                val weather = weatherRepository.getWeather()
                Log.d(TAG, "weather API autoLocate 返回: $weather")
                if (weather != null) {
                    val cityName = weather.city
                    val adcode = weather.adcode
                    Log.d(TAG, "自动定位成功: cityName=$cityName, adcode=$adcode")
                    // 保存到SharedPreferences
                    val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    prefs.edit()
                        .putString(KEY_CITY_NAME, cityName)
                        .putString(KEY_ADCODE, adcode)
                        // 自动定位后启用天气定位
                        .putBoolean(KEY_USE_IP_LOCATION, true)
                        // 通知主页返回后强制刷新天气（绕过节流）
                        .putBoolean("pending_weather_refresh", true)
                        .apply()
                    Toast.makeText(appContext, "定位成功：$cityName", Toast.LENGTH_SHORT).show()
                    // 直接关闭设置页，回到主页查看天气
                    requireActivity().finish()
                } else {
                    Log.w(TAG, "自动定位失败，未获取到天气结果")
                    Toast.makeText(appContext, "定位失败，请手动选择城市", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Auto location error", e)
                Toast.makeText(appContext, "定位出错：${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    /**
     * 使用自定义Dialog进行省市区三级联动选择
     */
    private fun showThreeLevelPicker() {
        val dataMap = provinceDataMap ?: return
        val provinces = dataMap.values.sortedBy { it.id }
        val inflater = LayoutInflater.from(requireContext())
        val dialogView = inflater.inflate(R.layout.dialog_three_level_picker, null)
        val spinnerProvince = dialogView.findViewById<Spinner>(R.id.spinnerProvince)
        val spinnerCity = dialogView.findViewById<Spinner>(R.id.spinnerCity)
        val spinnerDistrict = dialogView.findViewById<Spinner>(R.id.spinnerDistrict)

        // province adapter
        val provinceNames = provinces.map { it.provinceName }
        spinnerProvince.adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, provinceNames).also {
            it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }

        // helper to update cities/districts
        fun updateDistricts(provinceIndex: Int, cityIndex: Int) {
            val city = provinces[provinceIndex].cities.values.sortedBy { it.id }[cityIndex]
            val districts = city.district?.values?.sortedBy { it.id } ?: emptyList()
            val districtNames = districts.map { it.districtName }
            spinnerDistrict.adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, districtNames).also {
                it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            }
            spinnerDistrict.isEnabled = districts.isNotEmpty()
        }
        fun updateCities(provinceIndex: Int) {
            val cityList = provinces[provinceIndex].cities.values.sortedBy { it.id }
            val cityNames = cityList.map { it.cityName }
            spinnerCity.adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, cityNames).also {
                it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            }
            // update districts for first city
            updateDistricts(provinceIndex, 0)
        }

        spinnerProvince.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                updateCities(position)
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        spinnerCity.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val provIndex = spinnerProvince.selectedItemPosition
                updateDistricts(provIndex, position)
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        // initialize lists
        if (provinces.isNotEmpty()) {
            updateCities(0)
        }

        AlertDialog.Builder(requireContext())
            .setTitle("选择城市")
            .setView(dialogView)
            .setPositiveButton("确定") { _, _ ->
                val pIndex = spinnerProvince.selectedItemPosition
                val cIndex = spinnerCity.selectedItemPosition
                val dIndex = spinnerDistrict.selectedItemPosition
                val province = provinces[pIndex]
                val cityList = province.cities.values.sortedBy { it.id }
                val city = cityList[cIndex]
                val districtList = city.district?.values?.sortedBy { it.id } ?: emptyList()
                val finalName: String
                val adcode: String
                if (districtList.isNotEmpty() && dIndex in districtList.indices) {
                    finalName = districtList[dIndex].districtName
                    adcode = districtList[dIndex].districtAdcode.toString()
                } else {
                    finalName = city.cityName
                    adcode = city.cityAdcode.toString()
                }
                val appContext = requireActivity().applicationContext
                val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                prefs.edit()
                    .putString(KEY_CITY_NAME, finalName)
                    .putString(KEY_ADCODE, adcode)
                    .putBoolean(KEY_USE_IP_LOCATION, false)
                    // 通知主页返回后强制刷新天气（绕过节流），否则主页仍显示旧城市
                    .putBoolean("pending_weather_refresh", true)
                    .apply()
                Toast.makeText(appContext, getString(R.string.weather_city_selected, finalName), Toast.LENGTH_SHORT).show()
                // 关闭设置页回到主页，由 BrowseFragment.loadWeather() 检测到标记后强制刷新
                requireActivity().finish()
            }
            .setNegativeButton("取消", null)
            .show()
    }

}