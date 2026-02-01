package cf.zknb.tvlauncher

import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.leanback.app.GuidedStepSupportFragment
import androidx.leanback.widget.GuidanceStylist
import androidx.leanback.widget.GuidedAction
import cf.zknb.tvlauncher.repository.AppPreferencesManager

/**
 * 隐藏应用管理Fragment
 *
 * 显示已隐藏的应用列表，允许用户取消隐藏
 */
class HiddenAppsFragment : GuidedStepSupportFragment() {

    companion object {
        private const val TAG = "HiddenAppsFragment"
    }

    private lateinit var prefsManager: AppPreferencesManager
    private val hiddenApps = mutableListOf<Pair<String, String>>() // packageName to appName

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefsManager = AppPreferencesManager.getInstance(requireContext())
    }

    override fun onCreateGuidance(savedInstanceState: Bundle?): GuidanceStylist.Guidance {
        return GuidanceStylist.Guidance(
            getString(R.string.settings_hidden_apps),
            getString(R.string.hidden_apps_desc),
            getString(R.string.back),
            null
        )
    }

    override fun onCreateActions(actions: MutableList<GuidedAction>, savedInstanceState: Bundle?) {
        // 确保 prefsManager 已初始化（因为 onCreateActions 可能在 onCreate 完成前被调用）
        if (!::prefsManager.isInitialized) {
            prefsManager = AppPreferencesManager.getInstance(requireContext())
        }
        
        val context = requireContext()
        val pm = context.packageManager
        
        // 获取所有隐藏应用
        hiddenApps.clear()
        val allHiddenPackages = prefsManager.getHiddenApps()
        
        allHiddenPackages.forEach { packageName ->
            try {
                val appInfo = pm.getApplicationInfo(packageName, 0)
                val appName = pm.getApplicationLabel(appInfo).toString()
                hiddenApps.add(packageName to appName)
            } catch (e: PackageManager.NameNotFoundException) {
                Log.w(TAG, "Hidden app not found: $packageName")
            }
        }
        
        if (hiddenApps.isEmpty()) {
            // 没有隐藏应用
            actions.add(
                GuidedAction.Builder(context)
                    .id(-1)
                    .title(R.string.hidden_apps_empty)
                    .enabled(false)
                    .build()
            )
        } else {
            // 显示隐藏应用列表
            hiddenApps.forEachIndexed { index, (packageName, appName) ->
                actions.add(
                    GuidedAction.Builder(context)
                        .id(index.toLong())
                        .title(appName)
                        .description("$packageName • ${getString(R.string.unhide)}")
                        .build()
                )
            }
        }
    }

    override fun onGuidedActionClicked(action: GuidedAction) {
        if (action.id < 0) return
        
        val (packageName, appName) = hiddenApps[action.id.toInt()]
        
        // 取消隐藏
        prefsManager.toggleHidden(packageName)
        Log.d(TAG, "Unhide app: $appName ($packageName)")
        Toast.makeText(requireContext(), getString(R.string.hidden_app_unhidden), Toast.LENGTH_SHORT).show()
        
        // 从列表中移除该应用
        hiddenApps.removeAt(action.id.toInt())
        
        // 重新创建整个actions列表
        val newActions = mutableListOf<GuidedAction>()
        onCreateActions(newActions, null)
        
        // 使用 setActions 重新设置整个列表
        setActions(newActions)
    }
}
