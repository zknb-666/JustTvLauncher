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
    val provinceId: Int,
    // 可能包含区县数据
    @SerializedName("district")
    val district: Map<String, DistrictData>? = null
)

data class DistrictData(
    @SerializedName("id")
    val id: Int? = null,
    @SerializedName("district_name")
    val districtName: String,
    @SerializedName("district_adcode")
    val districtAdcode: Int,
    @SerializedName("city_adcode")
    val cityAdcode: Int? = null
)
