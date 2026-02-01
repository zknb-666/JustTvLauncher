package cf.zknb.tvlauncher.repository

import android.content.Context
import android.content.SharedPreferences
import android.util.Log

/**
 * 应用偏好设置管理器
 *
 * 使用SharedPreferences存储和管理应用的收藏、隐藏等偏好设置
 * 采用单例模式确保全局只有一个实例
 */
class AppPreferencesManager private constructor(context: Context) {
    
    companion object {
        private const val TAG = "AppPreferencesManager"
        private const val PREFS_NAME = "app_preferences"
        private const val KEY_FAVORITE_APPS = "favorite_apps"
        private const val KEY_HIDDEN_APPS = "hidden_apps"
        private const val SEPARATOR = ","
        
        @Volatile
        private var instance: AppPreferencesManager? = null
        
        /**
         * 获取单例实例
         *
         * @param context 应用上下文
         * @return AppPreferencesManager实例
         */
        fun getInstance(context: Context): AppPreferencesManager {
            return instance ?: synchronized(this) {
                instance ?: AppPreferencesManager(context.applicationContext).also {
                    instance = it
                }
            }
        }
    }
    
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    
    /**
     * 获取收藏的应用包名列表
     *
     * @return 收藏应用的包名集合
     */
    fun getFavoriteApps(): Set<String> {
        return try {
            val favoritesString = prefs.getString(KEY_FAVORITE_APPS, "") ?: ""
            if (favoritesString.isEmpty()) {
                emptySet()
            } else {
                favoritesString.split(SEPARATOR).filter { it.isNotEmpty() }.toSet()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting favorite apps", e)
            emptySet()
        }
    }
    
    /**
     * 检查应用是否被收藏
     *
     * @param packageName 应用包名
     * @return 如果应用被收藏返回true，否则返回false
     */
    fun isFavorite(packageName: String): Boolean {
        return getFavoriteApps().contains(packageName)
    }
    
    /**
     * 添加应用到收藏
     *
     * @param packageName 应用包名
     * @return 操作是否成功
     */
    fun addFavorite(packageName: String): Boolean {
        return try {
            val favorites = getFavoriteApps().toMutableSet()
            if (favorites.add(packageName)) {
                saveFavorites(favorites)
                Log.d(TAG, "Added to favorites: $packageName")
                true
            } else {
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error adding favorite", e)
            false
        }
    }
    
    /**
     * 从收藏中移除应用
     *
     * @param packageName 应用包名
     * @return 操作是否成功
     */
    fun removeFavorite(packageName: String): Boolean {
        return try {
            val favorites = getFavoriteApps().toMutableSet()
            if (favorites.remove(packageName)) {
                saveFavorites(favorites)
                Log.d(TAG, "Removed from favorites: $packageName")
                true
            } else {
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error removing favorite", e)
            false
        }
    }
    
    /**
     * 切换应用的收藏状态
     *
     * @param packageName 应用包名
     * @return 切换后的收藏状态
     */
    fun toggleFavorite(packageName: String): Boolean {
        return if (isFavorite(packageName)) {
            removeFavorite(packageName)
            false
        } else {
            addFavorite(packageName)
            true
        }
    }
    
    /**
     * 保存收藏列表
     *
     * @param favorites 收藏的包名集合
     */
    private fun saveFavorites(favorites: Set<String>) {
        prefs.edit()
            .putString(KEY_FAVORITE_APPS, favorites.joinToString(SEPARATOR))
            .apply()
    }
    
    /**
     * 获取隐藏的应用包名列表
     *
     * @return 隐藏应用的包名集合
     */
    fun getHiddenApps(): Set<String> {
        return try {
            val hiddenString = prefs.getString(KEY_HIDDEN_APPS, "") ?: ""
            if (hiddenString.isEmpty()) {
                emptySet()
            } else {
                hiddenString.split(SEPARATOR).filter { it.isNotEmpty() }.toSet()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting hidden apps", e)
            emptySet()
        }
    }
    
    /**
     * 检查应用是否被隐藏
     *
     * @param packageName 应用包名
     * @return 如果应用被隐藏返回true，否则返回false
     */
    fun isHidden(packageName: String): Boolean {
        return getHiddenApps().contains(packageName)
    }
    
    /**
     * 隐藏应用
     *
     * @param packageName 应用包名
     * @return 操作是否成功
     */
    fun hideApp(packageName: String): Boolean {
        return try {
            val hidden = getHiddenApps().toMutableSet()
            if (hidden.add(packageName)) {
                saveHidden(hidden)
                Log.d(TAG, "App hidden: $packageName")
                true
            } else {
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error hiding app", e)
            false
        }
    }
    
    /**
     * 取消隐藏应用
     *
     * @param packageName 应用包名
     * @return 操作是否成功
     */
    fun unhideApp(packageName: String): Boolean {
        return try {
            val hidden = getHiddenApps().toMutableSet()
            if (hidden.remove(packageName)) {
                saveHidden(hidden)
                Log.d(TAG, "App unhidden: $packageName")
                true
            } else {
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error unhiding app", e)
            false
        }
    }
    
    /**
     * 切换应用的隐藏状态
     *
     * @param packageName 应用包名
     * @return 切换后的隐藏状态
     */
    fun toggleHidden(packageName: String): Boolean {
        return if (isHidden(packageName)) {
            unhideApp(packageName)
            false
        } else {
            hideApp(packageName)
            true
        }
    }
    
    /**
     * 保存隐藏列表
     *
     * @param hidden 隐藏的包名集合
     */
    private fun saveHidden(hidden: Set<String>) {
        prefs.edit()
            .putString(KEY_HIDDEN_APPS, hidden.joinToString(SEPARATOR))
            .apply()
    }
    
    /**
     * 清除所有偏好设置
     */
    fun clearAll() {
        prefs.edit().clear().apply()
        Log.d(TAG, "All preferences cleared")
    }
}
