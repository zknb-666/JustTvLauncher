package cf.zknb.tvlauncher.model

/**
 * 行政区划数据模型。
 *
 * 本地 assets/provinces.json 只保留 34 个省级条目（约 1.7 KB）；
 * 地级市 / 区县改为按需调用 uapis.cn district 接口，见 DistrictRepository。
 * 解析统一使用系统 org.json，不依赖 Gson 反射。
 */

/** 省级条目，adcode 用字符串，可直接拼进 API URL / 写入 SharedPreferences。 */
data class ProvinceData(
    val id: Int,
    val name: String,
    val adcode: String
)

/** 联网查询到的下级行政区（level=city 或 level=district）。 */
data class DistrictItem(
    val name: String,
    val adcode: String
)
