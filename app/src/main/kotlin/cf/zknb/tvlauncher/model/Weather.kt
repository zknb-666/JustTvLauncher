package cf.zknb.tvlauncher.model

/**
 * 天气数据模型
 *
 * @property city 城市名称
 * @property weather 天气状况（如：晴、多云、雨等）
 * @property temperature 当前温度
 * @property weatherCode 天气代码，用于显示对应图标
 */
data class Weather(
    val city: String,
    val weather: String,
    val temperature: String,
    val weatherCode: String
)
