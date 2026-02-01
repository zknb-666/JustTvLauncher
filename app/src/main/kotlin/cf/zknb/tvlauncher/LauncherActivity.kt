package cf.zknb.tvlauncher

import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.widget.Toast
import androidx.fragment.app.FragmentActivity
import cf.zknb.tvlauncher.browse.BrowseFragment

/**
 * TV启动器应用的主启动Activity
 *
 * 作为TV启动器的入口点，设置主布局
 * 并处理按键事件
 */
class LauncherActivity : FragmentActivity() {
    /**
     * 当Activity首次创建时调用
     *
     * 将内容视图设置为启动器Activity布局
     *
     * @param savedInstanceState 保存的实例状态包
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_launcher)
    }

    /**
     * 当返回按钮被按下时调用
     *
     * 重写为不执行任何操作，防止启动器被返回按钮关闭
     */
    override fun onBackPressed() {}

    /**
     * 当按键被按下时调用
     *
     * 处理菜单和信息按键事件，为将来的功能预留占位
     * 以显示焦点项的应用信息
     *
     * @param keyCode 被按下的按键代码
     * @param event 包含事件信息的KeyEvent对象
     * @return 如果事件已处理，则返回true，否则返回false
     */
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_MENU || keyCode == KeyEvent.KEYCODE_INFO) {
            // 获取当前显示的Fragment
            val fragment = supportFragmentManager.findFragmentById(R.id.browse_fragment)
            if (fragment is BrowseFragment) {
                // 获取当前焦点的应用快捷方式
                val currentShortcut = fragment.currentFocusedShortcut
                if (currentShortcut != null) {
                    // 启动应用信息界面
                    launchAppInfo(currentShortcut.id, currentShortcut.title)
                    Log.d("LauncherActivity", "Launching app info for: ${currentShortcut.title} (${currentShortcut.id})")
                    return true
                }
            }
        }
        return super.onKeyDown(keyCode, event)
    }
    
    /**
     * 启动应用信息界面
     *
     * @param packageName 应用包名
     * @param appTitle 应用标题
     */
    private fun launchAppInfo(packageName: String, appTitle: String) {
        try {
            val intent = android.content.Intent(this, AppInfoActivity::class.java).apply {
                putExtra(AppInfoActivity.EXTRA_PACKAGE_NAME, packageName)
                putExtra(AppInfoActivity.EXTRA_APP_TITLE, appTitle)
                // 确保每次都创建新的Activity实例
                addFlags(android.content.Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
                addFlags(android.content.Intent.FLAG_ACTIVITY_NO_HISTORY)
            }
            startActivityForResult(intent, REQUEST_APP_INFO)
            // 添加进入动画
            overridePendingTransition(R.anim.app_info_enter, R.anim.app_info_exit)
        } catch (e: Exception) {
            Log.e("LauncherActivity", "Error launching app info", e)
            Toast.makeText(this, "Failed to open app info", Toast.LENGTH_SHORT).show()
        }
    }
    
    /**
     * 当从子Activity返回时调用
     * 
     * 在AppInfoActivity关闭后刷新列表，以显示收藏/隐藏的更新
     */
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: android.content.Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_APP_INFO) {
            // 从AppInfoActivity 返回，刷新列表
            val fragment = supportFragmentManager.findFragmentById(R.id.browse_fragment)
            if (fragment is BrowseFragment) {
                fragment.refreshList()
            }
        }
    }
    
    companion object {
        private const val REQUEST_APP_INFO = 1001
    }
}