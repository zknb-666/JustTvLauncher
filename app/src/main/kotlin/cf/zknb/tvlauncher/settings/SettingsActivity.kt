package cf.zknb.tvlauncher.settings

import android.os.Bundle
import android.view.WindowManager
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
        // 设置页同样只有遥控器焦点，不需要输入法；避免旧 framework 的 IME 焦点链路。
        window.setFlags(
            WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM,
            WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM
        )

        if (savedInstanceState == null) {
            val fragment = SettingsMainFragment()
            supportFragmentManager.beginTransaction()
                .replace(android.R.id.content, fragment)
                .commit()
        }
    }
}
