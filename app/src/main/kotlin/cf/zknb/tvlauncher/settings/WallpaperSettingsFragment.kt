package cf.zknb.tvlauncher.settings

import android.app.WallpaperManager
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.leanback.app.GuidedStepSupportFragment
import androidx.leanback.widget.GuidanceStylist
import androidx.leanback.widget.GuidedAction
import cf.zknb.tvlauncher.R

/**
 * 壁纸设置Fragment
 *
 * 提供壁纸设置选项，打开系统壁纸选择器
 */
class WallpaperSettingsFragment : GuidedStepSupportFragment() {

    companion object {
        private const val TAG = "WallpaperSettings"
        private const val ACTION_ID_OPEN_PICKER = 1L
    }

    override fun onCreateGuidance(savedInstanceState: Bundle?): GuidanceStylist.Guidance {
        return GuidanceStylist.Guidance(
            getString(R.string.settings_wallpaper),
            getString(R.string.wallpaper_open_picker_desc),
            getString(R.string.back),
            null
        )
    }

    override fun onCreateActions(actions: MutableList<GuidedAction>, savedInstanceState: Bundle?) {
        val context = requireContext()
        
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
            ACTION_ID_OPEN_PICKER -> {
                openWallpaperPicker()
            }
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
                val wallpaperManager = WallpaperManager.getInstance(requireContext())
                val intent2 = Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER)
                startActivity(Intent.createChooser(intent2, getString(R.string.wallpaper_open_picker)))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open wallpaper picker", e)
            Toast.makeText(requireContext(), R.string.wallpaper_open_failed, Toast.LENGTH_SHORT).show()
        }
    }
}
