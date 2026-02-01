package cf.zknb.tvlauncher

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.fragment.app.FragmentActivity
import cf.zknb.tvlauncher.model.Shortcut

/**
 * 应用信息Activity
 *
 * 显示应用的详细信息和操作选项，使用GuidedStepFragment提供用户交互界面
 * 支持打开应用设置、查看应用商店、收藏应用、隐藏应用等功能
 */
class AppInfoActivity : FragmentActivity() {
    
    companion object {
        const val TAG = "AppInfoActivity"
        // Intent参数常量
        const val EXTRA_PACKAGE_NAME = "extra_package_name"
        const val EXTRA_APP_TITLE = "extra_app_title"
        
        // 操作ID常量
        const val ACTION_ID_OPEN = 1L
        const val ACTION_ID_SETTINGS = 2L
        const val ACTION_ID_IN_STORE = 3L
        const val ACTION_ID_UNINSTALL = 4L
        const val ACTION_ID_FAVORITE = 5L
        const val ACTION_ID_HIDE = 6L
    }

    private var packageName: String? = null
    private var appTitle: String? = null

    /**
     * Activity创建时调用
     *
     * 初始化Activity，设置布局，从Intent中获取应用信息，
     * 并加载AppInfoFragment显示应用详情
     *
     * @param savedInstanceState 保存的实例状态包
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        try {
            setContentView(R.layout.activity_app_info)
            
            // 从Intent中获取应用信息
            packageName = intent?.getStringExtra(EXTRA_PACKAGE_NAME)
            appTitle = intent?.getStringExtra(EXTRA_APP_TITLE)

            
            // 验证必要参数
            if (packageName.isNullOrEmpty()) {
                Log.e(TAG, "Package name is missing")
                Toast.makeText(this, R.string.error_package_name_missing, Toast.LENGTH_SHORT).show()
                finish()
                return
            }
            
            // 首次创建时添加Fragment
            if (savedInstanceState == null) {
                val fragment = AppInfoFragment.newInstance(packageName!!, appTitle)
                supportFragmentManager.beginTransaction()
                    .replace(R.id.app_info_fragment_container, fragment)
                    .commit()
            }
            
            Log.d(TAG, "AppInfoActivity created for package: $packageName")
        } catch (e: Exception) {
            Log.e(TAG, "Error creating AppInfoActivity", e)
            Toast.makeText(this, R.string.error_generic, Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    /**
     * 打开应用
     *
     * @param packageName 应用包名
     */
    fun openApp(packageName: String) {
        try {
            val intent = packageManager.getLaunchIntentForPackage(packageName)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(intent)
                finish()
            } else {
                Toast.makeText(this, R.string.error_app_cannot_open, Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error opening app: $packageName", e)
            Toast.makeText(this, R.string.error_app_cannot_open, Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * 打开应用设置页面
     *
     * @param packageName 应用包名
     */
    fun openAppSettings(packageName: String) {
        try {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", packageName, null)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Error opening app settings: $packageName", e)
            Toast.makeText(this, R.string.error_open_settings, Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * 在应用商店中打开应用
     *
     * @param packageName 应用包名
     */
    fun openAppInStore(packageName: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                data = Uri.parse("market://details?id=$packageName")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            
            // 检查是否有应用可以处理此Intent
            if (intent.resolveActivity(packageManager) != null) {
                startActivity(intent)
            } else {
                // 如果没有应用商店，使用浏览器打开
                val webIntent = Intent(Intent.ACTION_VIEW).apply {
                    data = Uri.parse("https://play.google.com/store/apps/details?id=$packageName")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(webIntent)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error opening app in store: $packageName", e)
            Toast.makeText(this, R.string.error_open_store, Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * 卸载应用
     *
     * @param packageName 应用包名
     */
    fun uninstallApp(packageName: String) {
        try {
            val intent = Intent(Intent.ACTION_DELETE).apply {
                data = Uri.fromParts("package", packageName, null)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
            finish()
        } catch (e: Exception) {
            Log.e(TAG, "Error uninstalling app: $packageName", e)
            Toast.makeText(this, R.string.error_uninstall, Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * 处理返回按钮
     * 使用默认行为，允许用户返回到启动器
     */
    override fun onBackPressed() {
        super.onBackPressed()
        // 添加退出动�?        overridePendingTransition(R.anim.app_info_enter, R.anim.app_info_exit)
    }

    /**
     * Activity销毁时调用
     */
    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "AppInfoActivity destroyed")
    }
}