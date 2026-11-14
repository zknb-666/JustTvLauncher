package cf.zknb.tvlauncher.settings

import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.leanback.app.GuidedStepSupportFragment
import androidx.leanback.widget.GuidanceStylist
import androidx.leanback.widget.GuidedAction
import androidx.lifecycle.lifecycleScope
import cf.zknb.tvlauncher.R
import cf.zknb.tvlauncher.util.BingWallpaperManager
import kotlinx.coroutines.launch

/**
 * 壁纸设置Fragment
 *
 * 提供壁纸设置选项：打开系统壁纸选择器、开启 Bing 每日一图、立即应用今日壁纸
 */
class WallpaperSettingsFragment : GuidedStepSupportFragment() {

    companion object {
        private const val TAG = "WallpaperSettings"
        private const val ACTION_ID_OPEN_PICKER = 1L
        private const val ACTION_ID_BING_DAILY = 2L
        private const val ACTION_ID_BING_APPLY_NOW = 3L
    }

    override fun onCreateGuidance(savedInstanceState: Bundle?): GuidanceStylist.Guidance {
        return GuidanceStylist.Guidance(
            getString(R.string.settings_wallpaper),
            getString(R.string.settings_wallpaper_desc),
            getString(R.string.back),
            null
        )
    }

    override fun onCreateActions(actions: MutableList<GuidedAction>, savedInstanceState: Bundle?) {
        val context = requireContext()

        // Bing 每日一图开关
        val bingEnabled = BingWallpaperManager.isEnabled(context)
        actions.add(
            GuidedAction.Builder(context)
                .id(ACTION_ID_BING_DAILY)
                .title(R.string.wallpaper_bing_daily)
                .description(
                    if (bingEnabled) getString(R.string.wallpaper_bing_enabled)
                    else getString(R.string.wallpaper_bing_disabled)
                )
                .checkSetId(GuidedAction.CHECKBOX_CHECK_SET_ID)
                .checked(bingEnabled)
                .build()
        )

        // 立即应用今日壁纸
        actions.add(
            GuidedAction.Builder(context)
                .id(ACTION_ID_BING_APPLY_NOW)
                .title(R.string.wallpaper_bing_apply_now)
                .description(R.string.wallpaper_bing_daily_desc)
                .build()
        )

        // 打开系统壁纸选择器
        actions.add(
            GuidedAction.Builder(context)
                .id(ACTION_ID_OPEN_PICKER)
                .title(R.string.wallpaper_open_button)
                .description(R.string.wallpaper_open_picker_desc)
                .build()
        )
    }

    override fun onGuidedActionClicked(action: GuidedAction) {
        when (action.id) {
            ACTION_ID_BING_DAILY -> {
                val newState = !action.isChecked
                action.isChecked = newState
                BingWallpaperManager.setEnabled(requireContext(), newState)
                action.description = if (newState) {
                    getString(R.string.wallpaper_bing_enabled)
                } else {
                    getString(R.string.wallpaper_bing_disabled)
                }
                notifyActionChanged(findActionPositionById(ACTION_ID_BING_DAILY))
                // 刚开启时立即应用一次，让用户立刻看到效果
                if (newState) {
                    applyNow()
                }
            }
            ACTION_ID_BING_APPLY_NOW -> {
                applyNow()
            }
            ACTION_ID_OPEN_PICKER -> {
                openWallpaperPicker()
            }
        }
    }

    /**
     * 立即拉取并应用今日 Bing 壁纸，结果用 Toast 反馈
     */
    private fun applyNow() {
        lifecycleScope.launch {
            val ok = BingWallpaperManager.applyNow(requireContext())
            Toast.makeText(
                requireContext(),
                if (ok) R.string.wallpaper_bing_applied else R.string.wallpaper_bing_failed,
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    /**
     * 打开系统壁纸选择器
     */
    private fun openWallpaperPicker() {
        try {
            val intent = Intent(Intent.ACTION_SET_WALLPAPER)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

            if (intent.resolveActivity(requireContext().packageManager) != null) {
                startActivity(intent)
            } else {
                // 尝试使用WallpaperManager
                val intent2 = Intent(android.app.WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER)
                startActivity(Intent.createChooser(intent2, getString(R.string.wallpaper_open_picker)))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open wallpaper picker", e)
            Toast.makeText(requireContext(), R.string.wallpaper_open_failed, Toast.LENGTH_SHORT).show()
        }
    }
}
