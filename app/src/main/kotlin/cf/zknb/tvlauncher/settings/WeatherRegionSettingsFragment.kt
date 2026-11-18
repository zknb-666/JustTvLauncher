package cf.zknb.tvlauncher.settings

import android.app.AlertDialog
import android.content.Context
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Spinner
import android.widget.Toast
import androidx.leanback.app.GuidedStepSupportFragment
import androidx.leanback.widget.GuidanceStylist
import androidx.leanback.widget.GuidedAction
import androidx.lifecycle.lifecycleScope
import cf.zknb.tvlauncher.R
import cf.zknb.tvlauncher.model.DistrictItem
import cf.zknb.tvlauncher.model.ProvinceData
import cf.zknb.tvlauncher.repository.DistrictRepository
import cf.zknb.tvlauncher.repository.WeatherRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray

/**
 * 天气地区设置Fragment
 *
 * 手动选择采用省→市→区三级联动：
 * - 省级：本地 assets/provinces.json（34 条，约 1.7 KB）
 * - 市 / 区：选中上级后联网查询 uapis.cn district 接口，结果按 adcode 缓存
 * 这样不再随包携带 500 KB 的全国四级区划表，同时避免一次性解析大 JSON。
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
        private const val AREA_ASSET = "provinces.json"
        private const val LEVEL_CITY = "city"
        private const val LEVEL_DISTRICT = "district"
    }

    private var provinceList: List<ProvinceData>? = null
    private lateinit var appContext: Context
    private lateinit var weatherRepository: WeatherRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        appContext = requireContext().applicationContext
        weatherRepository = WeatherRepository(appContext)
        // 省级数据只有 1.7 KB，点击「手动选择城市」时才在 IO 线程读取
    }

    /** 读取本地省级列表，须在 IO 线程调用。 */
    private fun loadProvinces(): List<ProvinceData>? = try {
        val text = appContext.assets.open(AREA_ASSET)
            .bufferedReader(Charsets.UTF_8).use { it.readText() }
        val arr = JSONArray(text)
        val out = ArrayList<ProvinceData>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val adcode = o.optString("adcode", "")
            val name = o.optString("name", "")
            if (adcode.isEmpty() || name.isEmpty()) continue
            out.add(ProvinceData(o.optInt("id", i + 1), name, adcode))
        }
        Log.d(TAG, "Loaded ${out.size} provinces (local)")
        out
    } catch (e: Exception) {
        Log.e(TAG, "Failed to load provinces", e)
        null
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

        actions.add(
            GuidedAction.Builder(context)
                .id(ACTION_AUTO_LOCATE)
                .title("📍 自动定位")
                .description("通过天气API自动获取当前城市")
                .build()
        )

        actions.add(
            GuidedAction.Builder(context)
                .id(ACTION_MANUAL_SELECT)
                .title("手动选择城市")
                .description("省（本地）⇢市/区（联网查询）")
                .build()
        )
    }

    override fun onGuidedActionClicked(action: GuidedAction) {
        when (action.id) {
            ACTION_AUTO_LOCATE -> performAutoLocation()
            ACTION_MANUAL_SELECT -> showRegionPicker()
            else -> super.onGuidedActionClicked(action)
        }
    }

    /**
     * 执行自动定位。
     *
     * 使用 [WeatherRepository.locateByIp]（不回退缓存）：只有真正从网络拿到 IP
     * 定位实况才算成功，否则明确失败并**保持原有城市设置不变**。
     * 旧实现调用会回退缓存的 getWeather()，断网时也会把上一次别的城市的天气
     * 当成定位结果，误报「定位成功」并改写 adcode / use_ip_location。
     */
    private fun performAutoLocation() {
        // 使用 applicationContext，避免协程挂起期间 Fragment/Activity 被销毁后
        // requireContext() 在 catch 块中再次抛异常导致崩溃
        val context = requireActivity().applicationContext
        Toast.makeText(context, "正在定位...", Toast.LENGTH_SHORT).show()

        lifecycleScope.launch {
            try {
                Log.d(TAG, "开始自动定位...")
                val weather = weatherRepository.locateByIp()
                if (weather != null) {
                    Log.d(TAG, "自动定位成功: cityName=${weather.city}, adcode=${weather.adcode}")
                    val editor = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                        .edit()
                        .putString(KEY_CITY_NAME, weather.city)
                        .putBoolean(KEY_USE_IP_LOCATION, true)
                    // 文档：adcode 可能为空；为空时保留已有配置，避免写入空串让
                    // 后续 getWeather("") 又退化成一次 IP 定位请求
                    if (weather.adcode.isNotEmpty()) editor.putString(KEY_ADCODE, weather.adcode)
                    editor.apply()
                    // locateByIp 已把结果写入 cached_weather，主页直接显示缓存即可；
                    // 不再置 pending_weather_refresh，避免主页重复发一次定位请求
                    Toast.makeText(context, "定位成功：${weather.city}", Toast.LENGTH_SHORT).show()
                    activity?.finish()
                } else {
                    Log.w(TAG, "IP 定位失败（无有效实况），保持原有设置")
                    Toast.makeText(context, "定位失败，请检查网络或手动选择城市", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Auto location error", e)
                Toast.makeText(context, "定位出错：${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun showRegionPicker() {
        provinceList?.let {
            showPicker(it)
            return
        }
        lifecycleScope.launch {
            val data = withContext(Dispatchers.IO) { loadProvinces() }
            if (data.isNullOrEmpty()) {
                Toast.makeText(appContext, "省份数据加载失败", Toast.LENGTH_SHORT).show()
                return@launch
            }
            provinceList = data
            if (isAdded) showPicker(data)
        }
    }

    /**
     * 三级联动：省本地加载，市/区异步联网。
     *
     * 用 requestSeq 作为请求令牌，只接受最新一次响应，避免快速切换省份时
     * 旧请求回来覆盖新列表；Dialog 关闭时令牌递增，让在途请求全部失效。
     */
    private fun showPicker(provinces: List<ProvinceData>) {
        val ctx = requireContext()
        val dialogView = LayoutInflater.from(ctx).inflate(R.layout.dialog_three_level_picker, null)
        val spProvince = dialogView.findViewById<Spinner>(R.id.spinnerProvince)
        val spCity = dialogView.findViewById<Spinner>(R.id.spinnerCity)
        val spDistrict = dialogView.findViewById<Spinner>(R.id.spinnerDistrict)

        var cities: List<DistrictItem> = emptyList()
        var districts: List<DistrictItem> = emptyList()
        // 记录「已发起请求」的上级 adcode，用于去重 Spinner.setAdapter 触发的重复回调
        var cityRequestedFor: String? = null
        var districtRequestedFor: String? = null
        var loadingCities = false
        var loadingDistricts = false
        var requestSeq = 0

        fun active(seq: Int) = seq == requestSeq && isAdded

        /** 空列表时显示占位文案并禁用，避免用户误选。 */
        fun fill(spinner: Spinner, names: List<String>, placeholder: String, enabled: Boolean) {
            val items = if (names.isEmpty()) listOf(placeholder) else names
            spinner.adapter = ArrayAdapter(ctx, android.R.layout.simple_spinner_item, items).also {
                it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            }
            spinner.isEnabled = enabled && names.isNotEmpty()
        }

        fun loadDistricts(city: DistrictItem) {
            if (districtRequestedFor == city.adcode) return
            districtRequestedFor = city.adcode
            val seq = ++requestSeq
            loadingDistricts = true
            districts = emptyList()
            fill(spDistrict, emptyList(), "加载区县中…", false)
            lifecycleScope.launch {
                val result = DistrictRepository.children(city.adcode, LEVEL_DISTRICT)
                if (!active(seq)) return@launch
                loadingDistricts = false
                if (result == null) {
                    districtRequestedFor = null  // 允许重选该市重试
                    fill(spDistrict, emptyList(), "区县加载失败，重选城市可重试", false)
                    Toast.makeText(appContext, "区县加载失败，请检查网络", Toast.LENGTH_SHORT).show()
                } else {
                    districts = result
                    fill(spDistrict, result.map { it.name }, "（无区县，将使用市级）", true)
                }
            }
        }

        spCity.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val city = cities.getOrNull(position) ?: return
                loadDistricts(city)
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        fun loadCities(province: ProvinceData) {
            if (cityRequestedFor == province.adcode) return
            cityRequestedFor = province.adcode
            val seq = ++requestSeq
            loadingCities = true
            cities = emptyList()
            districts = emptyList()
            districtRequestedFor = null
            fill(spCity, emptyList(), "加载城市中…", false)
            fill(spDistrict, emptyList(), "等待选择城市", false)
            lifecycleScope.launch {
                val result = DistrictRepository.children(province.adcode, LEVEL_CITY)
                if (!active(seq)) return@launch
                loadingCities = false
                if (result == null) {
                    cityRequestedFor = null  // 允许切走再切回重试
                    fill(spCity, emptyList(), "城市加载失败，切换省份重试", false)
                    fill(spDistrict, emptyList(), "等待选择城市", false)
                    Toast.makeText(appContext, "城市加载失败，请检查网络", Toast.LENGTH_SHORT).show()
                } else {
                    cities = result
                    fill(spCity, result.map { it.name }, "（无下级，将使用省级）", true)
                    if (result.isEmpty()) {
                        // 台湾省等没有 level=city 下级：直接用省级 adcode，不再查区县
                        districtRequestedFor = province.adcode
                        fill(spDistrict, emptyList(), "（无区县，将使用省级）", false)
                    }
                }
            }
        }

        spProvince.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val province = provinces.getOrNull(position) ?: return
                loadCities(province)
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        // 省份立即可用；市/区占位，等待联网结果
        fill(spProvince, provinces.map { it.name }, "（无省份）", true)
        fill(spCity, emptyList(), "加载城市中…", false)
        fill(spDistrict, emptyList(), "等待选择城市", false)
        if (provinces.isNotEmpty()) loadCities(provinces[0])

        val dialog = AlertDialog.Builder(ctx)
            .setTitle("选择城市（市/区需联网加载）")
            .setView(dialogView)
            .setPositiveButton("确定", null)
            .setNegativeButton("取消", null)
            .create()

        // 覆盖确定按钮的点击，加载未完成时不允许提交并阻止 Dialog 关闭
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (loadingCities || loadingDistricts) {
                    Toast.makeText(appContext, "城市数据尚未加载完成，请稍候", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                val province = provinces.getOrNull(spProvince.selectedItemPosition)
                val city = cities.getOrNull(spCity.selectedItemPosition)
                val district = districts.getOrNull(spDistrict.selectedItemPosition)
                val finalName: String
                val finalAdcode: String
                when {
                    district != null -> {
                        finalName = district.name
                        finalAdcode = district.adcode
                    }
                    city != null -> {
                        finalName = city.name
                        finalAdcode = city.adcode
                    }
                    province != null -> {
                        finalName = province.name
                        finalAdcode = province.adcode
                    }
                    else -> {
                        Toast.makeText(appContext, "没有可选择的地区", Toast.LENGTH_SHORT).show()
                        return@setOnClickListener
                    }
                }
                appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .edit()
                    .putString(KEY_CITY_NAME, finalName)
                    .putString(KEY_ADCODE, finalAdcode)
                    .putBoolean(KEY_USE_IP_LOCATION, false)
                    // 通知主页返回后强制刷新天气（绕过节流）
                    .putBoolean("pending_weather_refresh", true)
                    .apply()
                Log.d(TAG, "城市已保存: $finalName ($finalAdcode)")
                Toast.makeText(appContext, getString(R.string.weather_city_selected, finalName), Toast.LENGTH_SHORT).show()
                dialog.dismiss()
                requireActivity().finish()
            }
        }
        // Dialog 关闭后让所有在途请求失效，避免回调更新已销毁的视图
        dialog.setOnDismissListener { requestSeq++ }
        dialog.show()
    }
}
