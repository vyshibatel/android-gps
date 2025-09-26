package io.github.jqssun.gpssetter.api

import retrofit2.Call
import retrofit2.http.GET
import retrofit2.http.Query

// Модели данных для ответа API
data class ElevationResponse(val results: List<ElevationResult>)
data class ElevationResult(val latitude: Double, val longitude: Double, val elevation: Double)

interface ElevationService {
    @GET("api/v1/lookup")
    fun getElevation(@Query("locations") locations: String): Call<ElevationResponse>
}