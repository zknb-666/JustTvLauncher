package cf.zknb.tvlauncher

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.widget.Toast
import androidx.fragment.app.FragmentActivity
import cf.zknb.tvlauncher.browse.BrowseFragment
import cf.zknb.tvlauncher.tvinput.TvInputSourceSwitcher
import cf.zknb.tvlauncher.tvinput.TvMonitorService
import cf.zknb.tvlauncher.tvinput.TvSourceController

/**
 * TV 启动器主 Activity
 *
 * 性能优化：
 * - 旧实现在 onCreate 和每次 onResume 都同步执行 switchTvInputSource()
 *   （Class.forName 反射 + binder 调用），并且在 onActivityResult 与 onResume
 *   各刷新一次应用列表，导致每次返回桌面都要全量重建 BrowseAdapter。
 * - 现在输入源切换只在首帧之后的后台线程做一次，列表只在数据确实变化后刷新一次。
 */
class LauncherActivity : FragmentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_launcher)

        startTvMonitorService()

        // 反射查找 TVManager 在 Android 4.2 上代价很高且往往抛 ClassNotFoundException，
        // 放到首帧之后的后台线程执行，不阻塞桌面冷启动。
        window.decorView.post {
            Thread({ switchTvInputSource() }, "tv-source-init").start()
        }
    }

    private fun startTvMonitorService() {
        try {
            if (!TvSourceController.isDeviceSupported(this)) {
                Log.d(TAG, "当前设备不支持TV信号源功能，跳过启动TV监控服务")
                return
            }
            startService(Intent(this, TvMonitorService::class.java))
        } catch (e: Exception) {
            Log.e(TAG, "启动TV监控服务失败", e)
        }
    }

    private fun switchTvInputSource() {
        if (!TvSourceController.isDeviceSupported(this)) return
        TvInputSourceSwitcher.switchTvInputSource(this)
    }

    override fun onBackPressed() {}

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_MENU || keyCode == KeyEvent.KEYCODE_INFO) {
            val fragment = supportFragmentManager.findFragmentById(R.id.browse_fragment)
            if (fragment is BrowseFragment) {
                val currentShortcut = fragment.currentFocusedShortcut
                if (currentShortcut != null) {
                    launchAppInfo(currentShortcut.id, currentShortcut.title)
                    return true
                }
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun launchAppInfo(packageName: String, appTitle: String) {
        try {
            val intent = Intent(this, AppInfoActivity::class.java).apply {
                putExtra(AppInfoActivity.EXTRA_PACKAGE_NAME, packageName)
                putExtra(AppInfoActivity.EXTRA_APP_TITLE, appTitle)
                addFlags(Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
                addFlags(Intent.FLAG_ACTIVITY_NO_HISTORY)
            }
            startActivityForResult(intent, REQUEST_APP_INFO)
            overridePendingTransition(R.anim.app_info_enter, R.anim.app_info_exit)
        } catch (e: Exception) {
            Log.e(TAG, "Error launching app info", e)
            Toast.makeText(this, "Failed to open app info", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_APP_INFO) {
            // 收藏/隐藏状态可能变化，刷新一次；ViewModel 有单飞，Fragment 有内容指纹，
            // 重复调用不会造成重复扫描或 UI 重建
            refreshBrowseList()
        }
    }

    override fun onResume() {
        super.onResume()
        // 从设置页 / 其它应用返回时，让列表有机会同步；
        // 内容未变化时 BrowseFragment 会跳过 Adapter 重建，代价极低。
        refreshBrowseList()
    }

    private fun refreshBrowseList() {
        val fragment = supportFragmentManager.findFragmentById(R.id.browse_fragment)
        if (fragment is BrowseFragment) fragment.refreshList()
    }

    companion object {
        private const val TAG = "LauncherActivity"
        private const val REQUEST_APP_INFO = 1001
    }
}
