package cf.zknb.tvlauncher

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.leanback.app.GuidedStepSupportFragment
import androidx.leanback.widget.GuidanceStylist
import androidx.leanback.widget.GuidedAction
import cf.zknb.tvlauncher.repository.AppPreferencesManager
import java.text.SimpleDateFormat
import java.util.*

/**
 * 应用信息Fragment
 *
 * 使用GuidedStepSupportFragment显示应用的详细信息和可执行的操作选项
 * 提供指导式界面让用户对应用进行各种操作
 */
class AppInfoFragment : GuidedStepSupportFragment() {

    companion object {
        private const val TAG = "AppInfoFragment"
        private const val ARG_PACKAGE_NAME = "arg_package_name"
        private const val ARG_APP_TITLE = "arg_app_title"

        /**
         * 创建Fragment实例的工厂方法
         *
         * @param packageName 应用包名
         * @param appTitle 应用标题（可选）
         * @return AppInfoFragment实例
         */
        fun newInstance(packageName: String, appTitle: String? = null): AppInfoFragment {
            return AppInfoFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_PACKAGE_NAME, packageName)
                    putString(ARG_APP_TITLE, appTitle)
                }
            }
        }
    }

    private var packageName: String? = null
    private var appTitle: String? = null
    private var appIcon: Drawable? = null
    private var appDescription: String? = null
    private var isSystemApp: Boolean = false
    private lateinit var prefsManager: AppPreferencesManager

    /**
     * Fragment创建时调用
     *
     * 从arguments中获取应用信息并加载应用详情
     *
     * @param savedInstanceState 保存的实例状态包
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        prefsManager = AppPreferencesManager.getInstance(requireContext())
        
        arguments?.let {
            packageName = it.getString(ARG_PACKAGE_NAME)
            appTitle = it.getString(ARG_APP_TITLE)
        }
        
        // 注意：不在这里调用 loadAppInfo()，因为 onCreateActions() 会先执行
    }

    /**
     * 加载应用信息
     *
     * 从PackageManager获取应用的详细信息，包括图标、名称、版本等
     */
    private fun loadAppInfo() {
        try {
            val pm = requireContext().packageManager
            val packageInfo = pm.getPackageInfo(packageName!!, 0)
            val applicationInfo = pm.getApplicationInfo(packageName!!, 0)
            
            // 获取应用图标
            appIcon = try {
                pm.getApplicationIcon(packageName!!)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to load app icon", e)
                null
            }
            
            // 获取应用名称（如果没有传入）
            if (appTitle.isNullOrEmpty()) {
                appTitle = pm.getApplicationLabel(applicationInfo).toString()
            }
            
            // 判断是否为系统应用
            isSystemApp = (applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
            Log.d(TAG, "App loaded: $appTitle, isSystemApp=$isSystemApp, package=$packageName")
            
            // 构建应用描述信息
            val versionName = packageInfo.versionName ?: "Unknown"
            val versionCode = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                packageInfo.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                packageInfo.versionCode.toLong()
            }
            
            val installDate = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
                .format(Date(packageInfo.firstInstallTime))
            val updateDate = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
                .format(Date(packageInfo.lastUpdateTime))
            
            appDescription = buildString {
                append(getString(R.string.app_info_version, versionName, versionCode))
                append("\n")
                append(getString(R.string.app_info_package, packageName))
                append("\n")
                append(getString(R.string.app_info_install_date, installDate))
                append("\n")
                append(getString(R.string.app_info_update_date, updateDate))
                if (isSystemApp) {
                    append("\n")
                    append(getString(R.string.app_info_system_app))
                }
            }
            
            Log.d(TAG, "App info loaded: $appTitle ($packageName)")
        } catch (e: PackageManager.NameNotFoundException) {
            Log.e(TAG, "Package not found: $packageName", e)
            appDescription = getString(R.string.error_app_not_found)
        } catch (e: Exception) {
            Log.e(TAG, "Error loading app info", e)
            appDescription = getString(R.string.error_load_app_info)
        }
    }

    /**
     * 创建指导信息
     *
     * 显示应用的图标、标题和描述信息
     *
     * @param savedInstanceState 保存的实例状态包
     * @return Guidance对象，包含应用信息
     */

    override fun onCreateGuidance(savedInstanceState: Bundle?): GuidanceStylist.Guidance {
        return GuidanceStylist.Guidance(
            appTitle ?: getString(R.string.app_info_title),
            appDescription ?: "",
            packageName ?: "",
            appIcon
        )
    }

    /**
     * 创建操作选项
     *
     * 根据应用类型提供不同的操作选项，如打开、设置、卸载等
     *
     * @param actions 操作列表，用于添加操作项
     * @param savedInstanceState 保存的实例状态包
     */
    override fun onCreateActions(actions: MutableList<GuidedAction>, savedInstanceState: Bundle?) {
        try {
            // 确保 prefsManager 已初始化（因为 onCreateActions 可能在 onCreate 完成前被调用）
            if (!::prefsManager.isInitialized) {
                prefsManager = AppPreferencesManager.getInstance(requireContext())
            }
            
            // 确保已经从 arguments 中获取了 packageName
            if (packageName == null) {
                arguments?.let {
                    packageName = it.getString(ARG_PACKAGE_NAME)
                    appTitle = it.getString(ARG_APP_TITLE)
                }
            }
            
            // 在创建操作之前先加载应用信息，确保 isSystemApp 已经正确判断
            // 使用 try-catch 确保即使加载失败也能继续创建基本操作
            try {
                loadAppInfo()
            } catch (e: Exception) {
                Log.e(TAG, "Error in loadAppInfo during onCreateActions", e)
                // 即使失败也继续，使用默认值
            }
            
            val context = requireContext()
            val isFavorite = packageName?.let { prefsManager.isFavorite(it) } ?: false
            val isHidden = packageName?.let { prefsManager.isHidden(it) } ?: false
            
            Log.d(TAG, "onCreateActions: isSystemApp=$isSystemApp for package=$packageName")
            
            // 打开应用
            actions.add(
                GuidedAction.Builder(context)
                    .id(AppInfoActivity.ACTION_ID_OPEN)
                    .title(R.string.action_open_app)
                    .description(R.string.action_open_app_desc)
                    .build()
            )
            
            // 应用设置
            actions.add(
                GuidedAction.Builder(context)
                    .id(AppInfoActivity.ACTION_ID_SETTINGS)
                    .title(R.string.action_app_settings)
                    .description(R.string.action_app_settings_desc)
                    .build()
            )
            
            // 在应用商店查看
            actions.add(
                GuidedAction.Builder(context)
                    .id(AppInfoActivity.ACTION_ID_IN_STORE)
                    .title(R.string.action_view_in_store)
                    .description(R.string.action_view_in_store_desc)
                    .build()
            )
            
            // 卸载应用（系统应用不显示此选项）
                actions.add(
                    GuidedAction.Builder(context)
                        .id(AppInfoActivity.ACTION_ID_UNINSTALL)
                        .title(R.string.action_uninstall)
                        .description(R.string.action_uninstall_desc)
                        .enabled(!isSystemApp)
                        .build()
                )


            
            // 收藏应用
            actions.add(
                GuidedAction.Builder(context)
                    .id(AppInfoActivity.ACTION_ID_FAVORITE)
                    .title(if (isFavorite) R.string.action_unfavorite else R.string.action_favorite)
                    .description(R.string.action_favorite_desc)
                    .checkSetId(GuidedAction.DEFAULT_CHECK_SET_ID)
                    .checked(isFavorite)
                    .build()
            )
            
            // 隐藏应用
            actions.add(
                GuidedAction.Builder(context)
                    .id(AppInfoActivity.ACTION_ID_HIDE)
                    .title(if (isHidden) R.string.action_unhide else R.string.action_hide)
                    .description(R.string.action_hide_desc)
                    .checkSetId(GuidedAction.DEFAULT_CHECK_SET_ID)
                    .checked(isHidden)
                    .build()
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error creating actions", e)
        }
    }

    /**
     * 处理操作点击事件
     *
     * 根据用户选择的操作执行相应的功能
     *
     * @param action 被点击的操作
     */
    override fun onGuidedActionClicked(action: GuidedAction) {
        try {
            val activity = activity as? AppInfoActivity ?: run {
                Log.e(TAG, "Activity is not AppInfoActivity")
                return
            }
            
            when (action.id) {
                AppInfoActivity.ACTION_ID_OPEN -> {
                    // 打开应用
                    packageName?.let { activity.openApp(it) }
                }
                AppInfoActivity.ACTION_ID_SETTINGS -> {
                    // 打开应用设置
                    packageName?.let { activity.openAppSettings(it) }
                }
                AppInfoActivity.ACTION_ID_IN_STORE -> {
                    // 在应用商店中打开
                    packageName?.let { activity.openAppInStore(it) }
                }
                AppInfoActivity.ACTION_ID_UNINSTALL -> {
                    // 卸载应用
                    packageName?.let { activity.uninstallApp(it) }
                }
                AppInfoActivity.ACTION_ID_FAVORITE -> {
                    // 收藏/取消收藏（预留功能）
                    handleFavorite(action)
                }
                AppInfoActivity.ACTION_ID_HIDE -> {
                    // 隐藏应用（预留功能）
                    handleHide(action)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling action click", e)
        }
    }

    /**
     * 处理收藏操作
     *
     * @param action 操作对象
     */
    private fun handleFavorite(action: GuidedAction) {
        try {
            packageName?.let { pkg ->
                val isFavorite = prefsManager.toggleFavorite(pkg)
                action.isChecked = isFavorite
                action.title = getString(if (isFavorite) R.string.action_unfavorite else R.string.action_favorite)
                notifyActionChanged(findActionPositionById(action.id))
                
                val message = if (isFavorite) R.string.message_added_to_favorites else R.string.message_removed_from_favorites
                Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
                
                Log.d(TAG, "Favorite toggled: $isFavorite for $pkg")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling favorite", e)
            Toast.makeText(requireContext(), R.string.error_generic, Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * 处理隐藏操作
     *
     * @param action 操作对象
     */
    private fun handleHide(action: GuidedAction) {
        try {
            packageName?.let { pkg ->
                val isHidden = prefsManager.toggleHidden(pkg)
                action.isChecked = isHidden
                action.title = getString(if (isHidden) R.string.action_unhide else R.string.action_hide)
                notifyActionChanged(findActionPositionById(action.id))
                
                val message = if (isHidden) R.string.message_app_hidden else R.string.message_app_unhidden
                Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
                
                Log.d(TAG, "Hide toggled: $isHidden for $pkg")
                
                // 如果隐藏应用，延迟关闭当前界面
                if (isHidden) {
                    view?.postDelayed({
                        activity?.finish()
                    }, 1000)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling hide", e)
            Toast.makeText(requireContext(), R.string.error_generic, Toast.LENGTH_SHORT).show()
        }
    }
}
