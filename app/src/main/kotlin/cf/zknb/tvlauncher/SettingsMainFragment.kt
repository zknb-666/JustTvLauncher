package cf.zknb.tvlauncher

import android.os.Bundle
import android.util.Log
import androidx.leanback.app.GuidedStepSupportFragment
import androidx.leanback.widget.GuidanceStylist
import androidx.leanback.widget.GuidedAction

/**
 * 设置主界面Fragment
 *
 * 显示设置选项列表：壁纸设置、天气地区设置、隐藏应用管理
 */
class SettingsMainFragment : GuidedStepSupportFragment() {

    companion object {
        private const val TAG = "SettingsMainFragment"
        
        private const val ACTION_ID_WALLPAPER = 1L
        private const val ACTION_ID_WEATHER = 2L
        private const val ACTION_ID_HIDDEN_APPS = 3L
    }

    override fun onCreateGuidance(savedInstanceState: Bundle?): GuidanceStylist.Guidance {
        return GuidanceStylist.Guidance(
            getString(R.string.settings_title),
            getString(R.string.settings_title),
            null,
            null
        )
    }

    override fun onCreateActions(actions: MutableList<GuidedAction>, savedInstanceState: Bundle?) {
        val context = requireContext()
        
        // 壁纸设置
        actions.add(
            GuidedAction.Builder(context)
                .id(ACTION_ID_WALLPAPER)
                .title(R.string.settings_wallpaper)
                .description(R.string.settings_wallpaper_desc)
                .build()
        )
        
        // 天气地区设置
        actions.add(
            GuidedAction.Builder(context)
                .id(ACTION_ID_WEATHER)
                .title(R.string.settings_weather_region)
                .description(R.string.settings_weather_region_desc)
                .build()
        )
        
        // 隐藏应用管理
        actions.add(
            GuidedAction.Builder(context)
                .id(ACTION_ID_HIDDEN_APPS)
                .title(R.string.settings_hidden_apps)
                .description(R.string.settings_hidden_apps_desc)
                .build()
        )
    }

    override fun onGuidedActionClicked(action: GuidedAction) {
        when (action.id) {
            ACTION_ID_WALLPAPER -> {
                Log.d(TAG, "Opening wallpaper settings")
                add(fragmentManager, WallpaperSettingsFragment())
            }
            ACTION_ID_WEATHER -> {
                Log.d(TAG, "Opening weather region settings")
                add(fragmentManager, WeatherRegionSettingsFragment())
            }
            ACTION_ID_HIDDEN_APPS -> {
                Log.d(TAG, "Opening hidden apps management")
                add(fragmentManager, HiddenAppsFragment())
            }
        }
    }
}
