package cf.zknb.tvlauncher.model

import com.google.gson.annotations.SerializedName

/**
 * 区域数据模型
 */
data class ProvinceData(
    @SerializedName("id")
    val id: Int,
    @SerializedName("province_name")
    val provinceName: String,
    @SerializedName("province_adcode")
    val provinceAdcode: Int,
    @SerializedName("city")
    val cities: Map<String, CityData>
)

data class CityData(
    @SerializedName("id")
    val id: Int,
    @SerializedName("city_name")
    val cityName: String,
    @SerializedName("city_adcode")
    val cityAdcode: Int,
    @SerializedName("province_adcode")
    val provinceAdcode: Int,
    @SerializedName("province_id")
    val provinceId: Int
)
