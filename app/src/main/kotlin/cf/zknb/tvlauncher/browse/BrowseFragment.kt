package cf.zknb.tvlauncher.browse

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.SpannableString
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.graphics.Typeface
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.leanback.app.BrowseSupportFragment
import androidx.leanback.widget.ListRowPresenter
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import cf.zknb.tvlauncher.settings.SettingsActivity
import cf.zknb.tvlauncher.tvinput.TvSourceController
import cf.zknb.tvlauncher.cleaner.MemoryCleaner
import cf.zknb.tvlauncher.model.Shortcut
import cf.zknb.tvlauncher.model.ShortcutGroup
import cf.zknb.tvlauncher.model.Weather
import cf.zknb.tvlauncher.repository.ShortcutRepository
import cf.zknb.tvlauncher.repository.WeatherRepository
import cf.zknb.tvlauncher.util.QWeatherIconsUtil
import cf.zknb.tvlauncher.util.ColorExtractor
import cf.zknb.tvlauncher.util.BingWallpaperManager
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*

/**
 * TV启动器的主浏览片段
 *
 * 显示TV启动器的主界面，包括按分类组织的应用快捷方式、
 * 当前时间，以及处理应用启动和包变化
 */
class BrowseFragment : BrowseSupportFragment() {
    private val handler by lazy { Handler(Looper.getMainLooper()) }
    
    companion object {
        private const val SCHEME_PACKAGE = "package"
        private const val KEY_LAST_WEATHER_FETCH = "last_weather_fetch_ms"
        private const val WEATHER_REFRESH_INTERVAL_MS = 30L * 60L * 1000L
        private const val KEY_LAST_LAUNCHED_ID = "last_launched_shortcut_id"
        /** 设置页切换城市后置位，主页据此强制刷新，绕过 30 分钟节流 */
        private const val KEY_PENDING_WEATHER_REFRESH = "pending_weather_refresh"
        private const val KEY_ADCODE = "adcode"
        private val dateFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
    }
    private val viewModel: BrowseViewModel by lazy {
        val factory = ViewModelProvider.AndroidViewModelFactory.getInstance(requireActivity().application)
        ViewModelProvider(this, factory).get(BrowseViewModel::class.java)
    }
    private lateinit var weatherRepository: WeatherRepository
    private var currentWeather: Weather? = null
    private var settingsIcon: ImageView? = null
    private var weatherContainer: LinearLayout? = null
    private var weatherIconView: TextView? = null
    private var weatherCityView: TextView? = null
    private var weatherInfoView: TextView? = null
    private var loadingContainer: LinearLayout? = null
    private var loadingIconView: ProgressBar? = null
    private var loadingTextView: TextView? = null
    private var lastContentHash = 0

    /**
     * 上次从这里启动的应用快捷方式 id。
     * 退出该应用返回桌面后，需要把焦点恢复到它身上。
     */
    private var lastLaunchedShortcutId: String? = null

    /**
     * 标记：返回桌面后必须执行一次焦点恢复。
     * 由“启动应用时”和“onResume”置位，在内容观察者里消费一次。
     */
    private var pendingFocusRestore = false
    
    /**
     * 当前焦点的应用快捷方式
     * 用于存储当前获得焦点的应用快捷方式，以便在其他地方使用
     */
    var currentFocusedShortcut: Shortcut? = null
    
    /**
     * 当片段首次创建时调用
     *
     * 初始化片段，设置ViewModel，观察浏览内容变化，
     * 并设置项目点击监听器
     *
     * @param savedInstanceState 保存的实例状态包
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        headersState = HEADERS_DISABLED
        
        // 初始化天气字体
        QWeatherIconsUtil.init(requireContext())
        
        // 初始化天气仓库
        weatherRepository = WeatherRepository(requireContext())
        
        
        // 设置项目点击监听器
        setOnItemViewClickedListener { _, item, _, _ ->
            when (item) {
                is Shortcut -> {
                    // 检查是否是功能卡片
                    if (isFunctionCard(item.id)) {
                        handleFunctionClick(item.id)
                    } else {
                        // 普通应用启动
                        launch(item.id)
                        // 记下启动的是哪个应用，返回桌面后把焦点恢复回来
                        lastLaunchedShortcutId = item.id
                        pendingFocusRestore = true
                        viewModel.incrementOpenCount(item)
                    }
                }
            }
        }
        
        // 设置项目选择监听器，用于检测焦点在哪个卡片上
        
        setOnItemViewSelectedListener { _, item, _, _ ->
            when (item) {
                is Shortcut -> {
                    // 焦点在应用快捷方式卡片上
                    // 更新当前焦点的应用快捷方式
                    currentFocusedShortcut = item
                    // 焦点移动是遥控器的高频事件，避免在这里做字符串拼接与日志写入
                }
                else -> {
                    // 焦点不在应用快捷方式卡片上
                    currentFocusedShortcut = null
                }
            }
        }
    }

    /** 零分配的列表指纹，用于判断是否需要重建 Adapter */
    private fun contentFingerprint(groups: List<ShortcutGroup>): Int {
        var hash = 7
        for (g in groups) {
            hash = 31 * hash + g.category.hashCode()
            for (s in g.shortcutList) {
                hash = 31 * hash + s.id.hashCode()
                hash = 31 * hash + s.openCount
            }
        }
        return hash
    }

    /**
     * 更新当前时间显示。
     * 注意：这里只设置文本。壁纸取色曾经放在每分钟的 tick 里，会重复解码整张
     * 1920x1080 壁纸 Bitmap，造成周期性 GC 停顿，现统一由 applyWallpaperColors() 处理。
     */
    private fun setTick() = handler.post {
        title = dateFormat.format(Date())
    }

    /** 一次性把标题、天气、设置图标的颜色对齐壁纸（结果带缓存） */
    private fun applyWallpaperColors() {
        adjustTitleColor()
        adjustWeatherColor()
        updateSettingsIconColor()
    }
    
    /**
     * 根据壁纸颜色调整标题文字颜色
     */
    private fun adjustTitleColor() {
        try {
            view?.let { rootView ->
                // 查找标题文本视图
                val titleView = rootView.findViewById<TextView?>(androidx.leanback.R.id.title_text)
                if (titleView != null) {
                    // 获取标题视图在屏幕上的位置
                    val location = IntArray(2)
                    titleView.getLocationOnScreen(location)
                    val x = location[0] + titleView.width / 2
                    val y = location[1] + titleView.height / 2
                    
                    // 从壁纸该位置提取颜色
                    val bgColor = ColorExtractor.extractColorFromWallpaper(
                        requireContext(),
                        x, y,
                        titleView.width,
                        titleView.height
                    )
                    
                    // 设置对比色
                    val textColor = ColorExtractor.getContrastColor(bgColor)
                    titleView.setTextColor(textColor)
                }
            }
        } catch (e: Exception) {
            Log.e("BrowseFragment", "Failed to adjust title color", e)
        }
    }
    
    /**
     * 更新天气显示
     */
    private fun updateWeather() {
        handler.post {
            // 数据已就绪，收起“加载中”占位，恢复天气内容显示
            loadingContainer?.visibility = View.GONE
            weatherContainer?.visibility = View.VISIBLE
            currentWeather?.let { weather ->
                // 更新图标
                val iconSpan = QWeatherIconsUtil.createIconSpan(requireContext(), weather.weatherCode)
                weatherIconView?.text = iconSpan
                
                // 更新城市名称
                weatherCityView?.text = weather.city
                
                // 更新天气和温度（加粗）
                val weatherText = QWeatherIconsUtil.getSimpleWeatherText(weather.weather)
                val temperature = weather.temperature
                val infoText = SpannableString("$weatherText $temperature")
                infoText.setSpan(
                    StyleSpan(Typeface.BOLD),
                    0,
                    infoText.length,
                    SpannableString.SPAN_EXCLUSIVE_EXCLUSIVE
                )
                weatherInfoView?.text = infoText
                
                // 调整天气组件的颜色
                adjustWeatherColor()
            }
        }
    }
    
    /**
     * 根据壁纸颜色调整天气显示的文字颜色
     */
    private fun adjustWeatherColor() {
        try {
            weatherContainer?.let { container ->
                // 获取天气容器在屏幕上的位置
                val location = IntArray(2)
                container.getLocationOnScreen(location)
                val x = location[0] + container.width / 2
                val y = location[1] + container.height / 2
                
                // 从壁纸该位置提取颜色
                val bgColor = ColorExtractor.extractColorFromWallpaper(
                    requireContext(),
                    x, y,
                    container.width,
                    container.height
                )
                
                // 设置对比色
                val textColor = ColorExtractor.getContrastColor(bgColor)
                weatherIconView?.setTextColor(textColor)
                weatherCityView?.setTextColor(textColor)
                weatherInfoView?.setTextColor(textColor)
                loadingTextView?.setTextColor(textColor)
                // 注意：setIndeterminateTintList 需 API 21，本机为 Android 4.x，
                // 改用 API 1 即存在的 Drawable.setColorFilter 给旋转图标着色，避免崩溃
                loadingIconView?.indeterminateDrawable?.setColorFilter(textColor, PorterDuff.Mode.SRC_IN)
            }
        } catch (e: Exception) {
            Log.e("BrowseFragment", "Failed to adjust weather color", e)
        }
    }

    /**
     * 根据壁纸颜色计算天气区（含加载中占位）的对比色。
     * 优先以可见的那个容器定位取色；若容器尚未布局（宽高为 0）则回退白色，保证可见。
     */
    private fun computeWeatherContrastColor(): Int {
        return try {
            val visibleRef = if (weatherContainer?.visibility == View.VISIBLE) weatherContainer
            else loadingContainer
            val container = visibleRef ?: weatherContainer
            if (container != null && container.width > 0 && container.height > 0) {
                val location = IntArray(2)
                container.getLocationOnScreen(location)
                val x = location[0] + container.width / 2
                val y = location[1] + container.height / 2
                val bgColor = ColorExtractor.extractColorFromWallpaper(
                    requireContext(), x, y, container.width, container.height
                )
                ColorExtractor.getContrastColor(bgColor)
            } else {
                Color.WHITE
            }
        } catch (e: Exception) {
            Color.WHITE
        }
    }

    /**
     * 显示“天气加载中”占位（旋转图标 + 文字），并隐藏已显示的天气内容，
     * 避免切换城市 / 冷启动联网刷新期间显示陈旧城市或空白。
     */
    private fun showWeatherLoading() {
        handler.post {
            try {
                weatherContainer?.visibility = View.GONE
                loadingContainer?.visibility = View.VISIBLE
                val color = computeWeatherContrastColor()
                loadingTextView?.setTextColor(color)
                loadingIconView?.indeterminateDrawable?.setColorFilter(color, PorterDuff.Mode.SRC_IN)
            } catch (e: Exception) {
                Log.e("BrowseFragment", "Failed to show weather loading", e)
            }
        }
    }

    /**
     * 隐藏“天气加载中”占位，恢复天气内容显示。
     */
    private fun hideWeatherLoading() {
        handler.post {
            try {
                loadingContainer?.visibility = View.GONE
                weatherContainer?.visibility = View.VISIBLE
            } catch (e: Exception) {
                Log.e("BrowseFragment", "Failed to hide weather loading", e)
            }
        }
    }
    
    /**
     * 加载天气信息
     */
    private fun loadWeather() {
        val weatherPrefs = requireContext().getSharedPreferences("weather_settings", Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val lastFetch = weatherPrefs.getLong(KEY_LAST_WEATHER_FETCH, 0L)

        // 用户在设置里切换了城市：设置页会写入 pending_weather_refresh 标记；
        // 或者缓存中的 adcode 与当前设置不一致（说明城市已变但缓存还是旧城市）。
        // 这两种情况都必须立即联网刷新，不能被下面的节流挡住。
        val pendingRefresh = weatherPrefs.getBoolean(KEY_PENDING_WEATHER_REFRESH, false)
        val configuredAdcode = weatherPrefs.getString(KEY_ADCODE, null)
        val cached = weatherRepository.loadCachedWeather(requireContext())
        val cityChanged = cached != null && !configuredAdcode.isNullOrEmpty()
                && cached.adcode != configuredAdcode

        // 若城市已切换，旧缓存属于另一个城市，不要把它展示出来，等刷新后再更新
        if (cached != null && !cityChanged) {
            currentWeather = cached
            updateWeather()
        }

        // 节流：Android 4.2 上 BouncyCastle 纯 Java TLS 握手在双核 A9 上非常昂贵，
        // 旧实现每次 onStart（每次返回桌面）都发起一次，直接拖慢桌面。
        // 半小时内复用缓存，不重复建连；但城市变化或显式 pending 时强制刷新。
        val withinThrottle = now - lastFetch < WEATHER_REFRESH_INTERVAL_MS
        val mustRefresh = pendingRefresh || cityChanged
        if (withinThrottle && !mustRefresh) {
            Log.d("BrowseFragment", "天气数据在有效期内，跳过网络请求")
            return
        }

        // 即将联网刷新：切换城市 / 冷启动时展示“加载中”占位，
        // 避免显示陈旧城市或空白；同一城市定时刷新则保留现有显示、静默更新。
        val needLoadingUi = mustRefresh || currentWeather == null
        if (needLoadingUi) {
            showWeatherLoading()
        }

        // 消费 pending 标记（cityChanged 每次实时判断，无需持久化）
        if (pendingRefresh) {
            weatherPrefs.edit().putBoolean(KEY_PENDING_WEATHER_REFRESH, false).apply()
        }
        weatherPrefs.edit().putLong(KEY_LAST_WEATHER_FETCH, now).apply()

        lifecycleScope.launch {
            try {
                val prefs = requireContext().getSharedPreferences("weather_settings", Context.MODE_PRIVATE)
                val useIpLocation = prefs.getBoolean("use_ip_location", true) // 默认启用天气定位；手动选择会将其设为false
                
                var weather: cf.zknb.tvlauncher.model.Weather? = null
                
                // 如果启用了IP定位，直接调用天气API获取当前IP对应的天气
                if (useIpLocation) {
                    Log.d("BrowseFragment", "IP定位已启用，直接通过天气接口获取...")
                    try {
                        weather = weatherRepository.getWeather()
                        if (weather != null) {
                            // 保存定位结果以便后续失败时回退
                            prefs.edit()
                                .putString("city_name", weather.city)
                                .putString("adcode", weather.adcode)
                                .apply()
                        } else {
                            Log.w("BrowseFragment", "通过天气接口获取定位天气失败，使用保存的城市")
                        }
                    } catch (e: Exception) {
                        Log.e("BrowseFragment", "IP定位异常，使用保存的城市", e)
                    }
                }
                
                // 如果IP定位失败或被禁用，使用保存的城市
                if (weather == null) {
                    val cityId = getDefaultCityId()
                    Log.d("BrowseFragment", "使用保存的城市ID: $cityId")
                    weather = weatherRepository.getWeather(cityId)
                }
                
                if (weather != null) {
                    currentWeather = weather
                    updateWeather() // 更新天气显示（内部会收起加载中占位）
                    Log.d("BrowseFragment", "Weather loaded: ${weather.city} ${weather.weather} ${weather.temperature}")
                } else {
                    Log.e("BrowseFragment", "Failed to load weather")
                    hideWeatherLoading()
                }
            } catch (e: Exception) {
                Log.e("BrowseFragment", "Error loading weather", e)
                hideWeatherLoading()
            }
        }
    }
    
    /**
     * 获取默认城市ID
     * 从SharedPreferences读取用户设置的城市，默认返回北京
     */
    private fun getDefaultCityId(): String {
        val prefs = requireContext().getSharedPreferences("weather_settings", Context.MODE_PRIVATE)
        return prefs.getString("adcode", "110100") ?: "110100" // 默认北京adcode
    }
    
    /**
     * 检查是否是工具卡片
     */
    private fun isFunctionCard(id: String): Boolean {
        return id.startsWith("tool.")
    }
    
    /**
     * 处理工具卡片点击事件
     */
    private fun handleFunctionClick(functionId: String) {
        when (functionId) {
            BrowseViewModel.TOOL_TV_INPUT -> {
                // TV信号源工具
                openTvInput()
            }
            BrowseViewModel.TOOL_MEMORY_CLEAN -> {
                // 内存清理工具
                cleanMemory()
            }
        }
    }
    
    /**
     * 打开TV信号源
     */
    private fun openTvInput() {
        try {
            val tvSourceController = TvSourceController(requireContext())
            val success = tvSourceController.openTvSource()
            
            if (success) {
                // 启动TV监控服务
                val serviceIntent = Intent(requireContext(), cf.zknb.tvlauncher.tvinput.TvMonitorService::class.java)
                requireContext().startService(serviceIntent)
                Log.d("BrowseFragment", "已启动TV监控服务")
                
                Toast.makeText(
                    requireContext(),
                    getString(cf.zknb.tvlauncher.R.string.tool_tv_input_success),
                    Toast.LENGTH_SHORT
                ).show()
            } else {
                Toast.makeText(
                    requireContext(),
                    getString(cf.zknb.tvlauncher.R.string.tool_tv_input_failed),
                    Toast.LENGTH_LONG
                ).show()
            }
        } catch (e: Exception) {
            Log.e("BrowseFragment", "Failed to open TV input", e)
            Toast.makeText(
                requireContext(),
                getString(cf.zknb.tvlauncher.R.string.tool_tv_input_failed),
                Toast.LENGTH_LONG
            ).show()
        }
    }
    
    /**
     * 清理内存
     */
    private fun cleanMemory() {
        // 显示清理中提示
        Toast.makeText(
            requireContext(),
            getString(cf.zknb.tvlauncher.R.string.tool_memory_clean_cleaning),
            Toast.LENGTH_SHORT
        ).show()
        
        // 在后台线程执行清理
        lifecycleScope.launch {
            try {
                val freedMemory = withContext(Dispatchers.IO) {
                    MemoryCleaner.cleanMemory(requireContext())
                }
                
                // 回到主线程显示结果
                if (freedMemory > 0) {
                    val freedText = MemoryCleaner.formatMemorySize(freedMemory)
                    Toast.makeText(
                        requireContext(),
                        getString(cf.zknb.tvlauncher.R.string.tool_memory_clean_success, freedText),
                        Toast.LENGTH_LONG
                    ).show()
                } else {
                    Toast.makeText(
                        requireContext(),
                        getString(cf.zknb.tvlauncher.R.string.tool_memory_clean_no_effect),
                        Toast.LENGTH_SHORT
                    ).show()
                }
                
                // 打印内存状态
                val memoryStatus = MemoryCleaner.getMemoryStatusText(requireContext())
                Log.d("BrowseFragment", "清理后内存状态: $memoryStatus")
                
            } catch (e: Exception) {
                Log.e("BrowseFragment", "清理内存失败", e)
                Toast.makeText(
                    requireContext(),
                    "清理失败: ${e.message}",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    /**
     * 通过包名启动应用
     *
     * 尝试使用Leanback启动意图启动应用（适用于TV优化的应用）
     * 如果Leanback不可用，则回退到常规启动意图
     *
     * @param packageName 要启动的应用的包名
     */
    private fun launch(packageName: String) {
        val packageManager = requireContext().packageManager
        var intent: Intent? = null
        
        // 尝试获取TV优化应用的Leanback启动意图
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            intent = packageManager.getLeanbackLaunchIntentForPackage(packageName)
        }
        
        // 如果Leanback意图不可用，回退到常规启动意图
        if (intent == null) {
            intent = packageManager.getLaunchIntentForPackage(packageName)
        }
        
        startActivity(intent)
    }

    /**
     * 将选定位置设置为指定的快捷方式
     *
     * 向主线程发布一个可运行项，以查找快捷方式的位置并选择它
     *
     * @param shortcut 要选择的快捷方式
     */
    private fun setSelect(shortcut: Shortcut) = handler.post {
        val position = viewModel.findPosition(shortcut)
        val task = ListRowPresenter.SelectItemViewHolderTask(position.second)
        task.isSmoothScroll = false
        rowsSupportFragment.setSelectedPosition(position.first, false, task)
    }

    /**
     * 按 id 把焦点恢复到对应应用卡片。
     *
     * 列表每次刷新都会重建对象，故用 findPositionById 而非对象引用查找。
     * 通过 SelectedPosition 的 pending 机制，即便上一步刚重建了 Adapter，
     * 焦点也会在行视图就绪后落到正确卡片上。
     */
    private fun restoreFocusTo(id: String) {
        val pos = viewModel.findPositionById(id) ?: run {
            Log.w("BrowseFragment", "返回桌面恢复焦点失败：找不到 $id（可能已被隐藏/卸载）")
            lastLaunchedShortcutId = null
            return
        }
        try {
            val task = ListRowPresenter.SelectItemViewHolderTask(pos.second)
            task.isSmoothScroll = false
            // 略延迟一帧，确保新 Adapter 的行视图已经完成布局
            handler.postDelayed({
                rowsSupportFragment.setSelectedPosition(pos.first, false, task)
                // 恢复成功后清空，避免之后从其它界面（如设置）返回时反复抢占焦点
                lastLaunchedShortcutId = null
            }, 80)
        } catch (e: Exception) {
            Log.e("BrowseFragment", "Failed to restore focus to $id", e)
            lastLaunchedShortcutId = null
        }
    }

    /**
     * 当视图创建完成时调用
     */
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        // 进程被杀后重建时，把“上次启动的应用”恢复出来，便于返回桌面后恢复焦点
        savedInstanceState?.getString(KEY_LAST_LAUNCHED_ID)?.let { id ->
            lastLaunchedShortcutId = id
            pendingFocusRestore = true
        }
        // observe LiveData with viewLifecycleOwner to avoid leaks
        viewModel.browseContent.observe(viewLifecycleOwner) { groups ->
            // 内容指纹：绝大多数 onResume 刷新后数据完全一致，此时跳过整张
            // Adapter 的重建（旧版每次返回桌面都重建所有 Row 和卡片，是卡顿主因）
            val hash = contentFingerprint(groups)
            if (hash != lastContentHash) {
                lastContentHash = hash
                adapter = BrowseAdapter(groups)
            }
            // 返回桌面后恢复焦点：startActivity 离开再返回时，Leanback 的待选状态
            // 会因 Adapter 重建（openCount 变化导致指纹变化）而丢失，这里显式补回来。
            if (pendingFocusRestore) {
                pendingFocusRestore = false
                lastLaunchedShortcutId?.let { id -> restoreFocusTo(id) }
            }
        }
        setupWeatherView()
        setupSettingsIcon()
        // 标题栏三处自绘控件加完后，统一做一次壁纸取色（内部带缓存，只算一次）
        handler.postDelayed({ applyWallpaperColors() }, 700)
    }
    
    /**
     * 设置标题栏左侧的天气组件
     */
    private fun setupWeatherView() {
        try {
            // 延迟执行，确保标题栏已经创建完成
            handler.postDelayed({
                view?.let { rootView ->
                    // 查找标题容器
                    val titleGroup = rootView.findViewById<ViewGroup?>(androidx.leanback.R.id.browse_title_group)
                    
                    if (titleGroup != null && weatherContainer == null) {
                        val density = resources.displayMetrics.density
                        
                        // 创建水平容器
                        weatherContainer = LinearLayout(requireContext()).apply {
                            orientation = LinearLayout.HORIZONTAL
                            gravity = Gravity.CENTER_VERTICAL
                            setPadding(
                                (16 * density).toInt(),
                                0,
                                0,
                                0
                            )
                        }
                        
                        // 创建天气图标TextView（占两行高度）
                        weatherIconView = TextView(requireContext()).apply {
                            textSize = 42f  // 大图标
                            setTextColor(Color.WHITE)
                            gravity = Gravity.CENTER
                            setPadding(0, 0, (8 * density).toInt(), 0)
                        }
                        
                        // 创建垂直容器放置城市和天气信息
                        val textContainer = LinearLayout(requireContext()).apply {
                            orientation = LinearLayout.VERTICAL
                            gravity = Gravity.CENTER_VERTICAL
                        }
                        
                        // 创建城市名称TextView
                        weatherCityView = TextView(requireContext()).apply {
                            textSize = 16f
                            setTextColor(Color.WHITE)
                        }
                        
                        // 创建天气信息TextView（天气+温度，加粗）
                        weatherInfoView = TextView(requireContext()).apply {
                            textSize = 16f
                            setTextColor(Color.WHITE)
                        }
                        
                        // 组装布局
                        textContainer.addView(weatherCityView)
                        textContainer.addView(weatherInfoView)
                        weatherContainer?.addView(weatherIconView)
                        weatherContainer?.addView(textContainer)

                        // 加载中占位（旋转图标 + 文字），与天气容器同一位置、靠可见性互斥，
                        // 用于切换城市 / 冷启动联网刷新期间占位，避免显示陈旧城市或空白。
                        loadingContainer = LinearLayout(requireContext()).apply {
                            orientation = LinearLayout.HORIZONTAL
                            gravity = Gravity.CENTER_VERTICAL
                            setPadding(
                                (16 * density).toInt(),
                                0,
                                0,
                                0
                            )
                            visibility = View.GONE
                        }
                        loadingIconView = ProgressBar(requireContext()).apply {
                            isIndeterminate = true
                            val size = (24 * density).toInt()
                            layoutParams = LinearLayout.LayoutParams(size, size)
                        }
                        loadingTextView = TextView(requireContext()).apply {
                            text = "天气加载中…"
                            textSize = 16f
                            setTextColor(Color.WHITE)
                            setPadding((8 * density).toInt(), 0, 0, 0)
                        }
                        loadingContainer?.addView(loadingIconView)
                        loadingContainer?.addView(loadingTextView)
                        
                        // 设置布局参数 - 左侧显示
                        val layoutParams = FrameLayout.LayoutParams(
                            FrameLayout.LayoutParams.WRAP_CONTENT,
                            FrameLayout.LayoutParams.WRAP_CONTENT
                        ).apply {
                            gravity = Gravity.START or Gravity.CENTER_VERTICAL
                        }
                        val loadingParams = FrameLayout.LayoutParams(
                            FrameLayout.LayoutParams.WRAP_CONTENT,
                            FrameLayout.LayoutParams.WRAP_CONTENT
                        ).apply {
                            gravity = Gravity.START or Gravity.CENTER_VERTICAL
                        }
                        
                        // 将天气组件添加到标题栏
                        titleGroup.addView(weatherContainer, layoutParams)
                        titleGroup.addView(loadingContainer, loadingParams)
                        
                        // 如果已经有天气数据，立即更新显示
                        if (currentWeather != null) {
                            updateWeather()
                        }
                        
                        Log.d("BrowseFragment", "Weather view added successfully")
                    }
                }
            }, 500) // 延迟500ms确保UI已经准备好
        } catch (e: Exception) {
            Log.e("BrowseFragment", "Failed to setup weather view", e)
        }
    }
    
    /**
     * 设置标题栏右侧的设置图标
     */
    private fun setupSettingsIcon() {
        try {
            // 延迟执行，确保标题栏已经创建完成
            handler.postDelayed(Runnable {
                view?.let { rootView ->
                    // 查找标题容器（Leanback的标题栏通常在browse_title_group中）
                    val titleGroup = rootView.findViewById<ViewGroup?>(androidx.leanback.R.id.browse_title_group)
                    
                    if (titleGroup != null && settingsIcon == null) {
                        // 创建设置图标
                        settingsIcon = ImageView(requireContext()).apply {
                            // 使用Android系统的设置图标
                            setImageResource(android.R.drawable.ic_menu_manage)
                            // 设置图标着色为白色
                            setColorFilter(Color.WHITE)
                            // 设置可聚焦和可点击
                            isFocusable = true
                            isClickable = true
                            // 设置内边距
                            setPadding(12, 12, 12, 12)
                            // 背景透明，无背景
                            background = null
                            
                            // 设置点击事件 - 启动设置界面
                            setOnClickListener {
                                try {
                                    val intent = android.content.Intent(context, SettingsActivity::class.java)
                                    startActivity(intent)
                                } catch (e: Exception) {
                                    Log.e("BrowseFragment", "Failed to open settings", e)
                                    Toast.makeText(context, "打开设置失败", Toast.LENGTH_SHORT).show()
                                }
                            }
                            
                            // 设置焦点变化监听（白色透明圆底+图标反色）
                            setOnFocusChangeListener { _, hasFocus ->
                                if (hasFocus) {
                                    // 创建白色半透明圆形背景
                                    val circleBackground = GradientDrawable().apply {
                                        shape = GradientDrawable.OVAL
                                        setColor(Color.argb(128, 255, 255, 255))  // 50%透明度的白色
                                    }
                                    background = circleBackground
                                    // 图标反色为黑色
                                    setColorFilter(Color.BLACK, PorterDuff.Mode.SRC_IN)
                                    alpha = 1.0f
                                } else {
                                    // 无焦点时：根据壁纸颜色设置图标颜色
                                    background = null
                                    updateSettingsIconColor()
                                    alpha = 0.7f
                                }
                            }
                            // 默认半透明
                            alpha = 0.7f
                        }
                        
                        // 设置布局参数 - 贴近右侧边缘
                        val iconSize = (40 * resources.displayMetrics.density).toInt()
                        val layoutParams = FrameLayout.LayoutParams(iconSize, iconSize).apply {
                            gravity = Gravity.END or Gravity.CENTER_VERTICAL
                            // 小边距让图标贴近右侧
                            marginEnd = (-45 * resources.displayMetrics.density).toInt()
                        }
                        
                        // 将图标添加到标题栏
                        titleGroup.addView(settingsIcon, layoutParams)
                        
                        Log.d("BrowseFragment", "Settings icon added successfully")
                    }
                }
            }, 500) // 延迟500ms确保UI已经准备好
        } catch (e: Exception) {
            Log.e("BrowseFragment", "Failed to setup settings icon", e)
        }
    }
    
    /**
     * 根据壁纸颜色调整设置图标的颜色
     */
    private fun updateSettingsIconColor() {
        try {
            settingsIcon?.let { icon ->
                // 获取图标在屏幕上的位置
                val location = IntArray(2)
                icon.getLocationOnScreen(location)
                val x = location[0] + icon.width / 2
                val y = location[1] + icon.height / 2
                
                // 从壁纸该位置提取颜色
                val bgColor = ColorExtractor.extractColorFromWallpaper(
                    requireContext(),
                    x, y,
                    icon.width * 2,
                    icon.height * 2
                )
                
                // 设置对比色
                val iconColor = ColorExtractor.getContrastColor(bgColor)
                icon.setColorFilter(iconColor, PorterDuff.Mode.SRC_IN)
            }
        } catch (e: Exception) {
            Log.e("BrowseFragment", "Failed to adjust settings icon color", e)
        }
    }
    
    /**
     * 当片段对用户可见时调用
     *
     * 注册时间 tick 和包变化的广播接收器，并立即显示当前时间
     */
    override fun onStart() {
        super.onStart()
        setTick() // 立即显示当前时间
        loadWeather() // 加载天气信息
        // 每日自动应用 Bing 壁纸（内部有日期守卫，当天只真正下载一次）
        lifecycleScope.launch { BingWallpaperManager.maybeUpdateDaily(requireContext()) }
        val context = requireContext()
        context.registerReceiver(timeTickReceiver, timeTickReceiver.getIntentFilter())
        context.registerReceiver(packageChangedReceiver, packageChangedReceiver.getIntentFilter())
        context.registerReceiver(wallpaperChangedReceiver, wallpaperChangedReceiver.getIntentFilter())
    }

    /**
     * 从其它应用返回桌面时调用。
     *
     * 若此前从这里启动过某个应用，则标记需要在数据刷新后把焦点恢复到它。
     * 真正的恢复动作在 browseContent 观察者里执行（那时 Adapter 已就绪）。
     */
    override fun onResume() {
        super.onResume()
        if (lastLaunchedShortcutId != null) {
            pendingFocusRestore = true
        }
    }

    /**
     * 保存“上次启动的应用”id，保证进程被系统回收后重建仍能恢复焦点
     */
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        lastLaunchedShortcutId?.let { outState.putString(KEY_LAST_LAUNCHED_ID, it) }
    }

    /**
     * 当片段对用户不可见时调用
     *
     * 注销广播接收器
     */
    override fun onStop() {
        super.onStop()
        val context = requireContext()
        context.unregisterReceiver(timeTickReceiver)
        context.unregisterReceiver(packageChangedReceiver)
        context.unregisterReceiver(wallpaperChangedReceiver)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        // clear view references to allow GC
        weatherContainer = null
        weatherIconView = null
        weatherCityView = null
        weatherInfoView = null
        loadingContainer = null
        loadingIconView = null
        loadingTextView = null
        settingsIcon = null
    }

    /**
     * 时间 tick 事件的广播接收器
     *
     * 当收到时间 tick 事件时更新当前时间显示
     */
    private val timeTickReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent) {
            if (Intent.ACTION_TIME_TICK == intent.action) {
                setTick()
                // Launcher 常驻主屏时，跨零点后下一次 tick 即触发每日壁纸刷新
                val ctx = (context ?: this@BrowseFragment.requireContext()).applicationContext
                this@BrowseFragment.lifecycleScope.launch(Dispatchers.IO) {
                    BingWallpaperManager.maybeUpdateDaily(ctx)
                }
            }
        }

        /**
         * 获取此接收器的意图过滤器
         *
         * @return 配置为时间 tick 事件的IntentFilter
         */
        fun getIntentFilter(): IntentFilter {
            return IntentFilter(Intent.ACTION_TIME_TICK)
        }
    }

    /**
     * 包变化事件的广播接收器
     *
     * 通过更新快捷方式列表处理应用安装、移除和变化
     */
    private val packageChangedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent) {
            if (SCHEME_PACKAGE != intent.scheme) {
                return
            }
            if (intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)) {
                return
            }
            // 应用被替换后图标可能变化，让主色与图标缓存都失效
            ShortcutCardPresenter.invalidateColors()
            ShortcutRepository.invalidateCache()
            if (Intent.ACTION_PACKAGE_REMOVED == intent.action) {
                val packageName = intent.data!!.schemeSpecificPart
                viewModel.removePackage(packageName)
            } else {
                viewModel.loadShortcutGroupList()
            }
        }

        /**
         * 获取此接收器的意图过滤器
         *
         * @return 配置为包变化事件的IntentFilter
         */
        fun getIntentFilter(): IntentFilter {
            val intentFilter = IntentFilter()
            intentFilter.addAction(Intent.ACTION_PACKAGE_ADDED)
            intentFilter.addAction(Intent.ACTION_PACKAGE_REMOVED)
            intentFilter.addAction(Intent.ACTION_PACKAGE_CHANGED)
            intentFilter.addAction(Intent.ACTION_PACKAGE_REPLACED)
            intentFilter.addDataScheme(SCHEME_PACKAGE)
            return intentFilter
        }
    }
    
    /**
     * 壁纸变化事件的广播接收器
     *
     * 当壁纸改变时，更新UI组件的颜色
     */
    private val wallpaperChangedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (Intent.ACTION_WALLPAPER_CHANGED == intent?.action) {
                // 壁纸换了必须让取色缓存失效，否则会一直沿用旧壁纸的对比色
                ColorExtractor.clearWallpaperCache()
                handler.postDelayed({
                    applyWallpaperColors()
                    Log.d("BrowseFragment", "UI colors updated after wallpaper change")
                }, 500)
            }
        }
        
        /**
         * 获取此接收器的意图过滤器
         *
         * @return 配置为壁纸变化事件的IntentFilter
         */
        fun getIntentFilter(): IntentFilter {
            return IntentFilter(Intent.ACTION_WALLPAPER_CHANGED)
        }
    }

    /**
     * 刷新应用列表
     * 
     * 当收藏或隐藏状态改变后，调用此方法更新列表
     */
    fun refreshList() {
        viewModel.loadShortcutGroupList()
    }

}