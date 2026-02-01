package cf.zknb.tvlauncher.settings

import android.os.Bundle
import androidx.fragment.app.FragmentActivity

/**
 * 设置Activity
 *
 * 显示启动器设置选项，包括壁纸、天气地区、隐藏应用管理
 * 使用GuidedStepFragment提供电视适配的用户界面
 */
class SettingsActivity : FragmentActivity() {
    
    companion object {
        const val TAG = "SettingsActivity"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        if (savedInstanceState == null) {
            val fragment = SettingsMainFragment()
            supportFragmentManager.beginTransaction()
                .replace(android.R.id.content, fragment)
                .commit()
        }
    }
}
