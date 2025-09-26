package io.github.jqssun.gpssetter.api

import com.google.android.gms.maps.model.LatLng
import io.github.jqssun.gpssetter.BuildConfig
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Query

// Модели данных для ответа от Google API
data class DirectionsResponse(val routes: List<Route>)
data class Route(val overview_polyline: Polyline)
data class Polyline(val points: String)

// Простой класс для координат, он нам все еще нужен
data class RoutePoint(val lat: Double, val lon: Double)

// Интерфейс для Retrofit
interface GoogleDirectionsApi {
    @GET("maps/api/directions/json")
    suspend fun getDirections(
        @Query("origin") origin: String,
        @Query("destination") destination: String,
        @Query("key") apiKey: String
    ): DirectionsResponse
}

class RoutingService {

    private val apiKey = "AIzaSyDbZfdOZtbsMX8cUjaZ2U3j6-i5MHusDn4" // ВАЖНО: Вставьте сюда ваш ключ!

    private val api: GoogleDirectionsApi

    init {
        val logging = HttpLoggingInterceptor()
        logging.setLevel(HttpLoggingInterceptor.Level.BODY)
        val httpClient = OkHttpClient.Builder()
        if (BuildConfig.DEBUG) {
            httpClient.addInterceptor(logging)
        }

        val retrofit = Retrofit.Builder()
            .baseUrl("https://maps.googleapis.com/")
            .addConverterFactory(GsonConverterFactory.create())
            .client(httpClient.build())
            .build()

        api = retrofit.create(GoogleDirectionsApi::class.java)
    }

    suspend fun getRoute(start: RoutePoint, end: RoutePoint): List<LatLng>? {
        try {
            val origin = "${start.lat},${start.lon}"
            val destination = "${end.lat},${end.lon}"
            val response = api.getDirections(origin, destination, apiKey)

            if (response.routes.isNotEmpty()) {
                val points = response.routes[0].overview_polyline.points
                return decodePolyline(points)
            }
            return null
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        }
    }

    private fun decodePolyline(encoded: String): List<LatLng> {
        val poly = ArrayList<LatLng>()
        var index = 0
        val len = encoded.length
        var lat = 0
        var lng = 0

        while (index < len) {
            var b: Int
            var shift = 0
            var result = 0
            do {
                b = encoded[index++].code - 63
                result = result or (b and 0x1f shl shift)
                shift += 5
            } while (b >= 0x20)
            val dlat = if (result and 1 != 0) (result shr 1).inv() else result shr 1
            lat += dlat

            shift = 0
            result = 0
            do {
                b = encoded[index++].code - 63
                result = result or (b and 0x1f shl shift)
                shift += 5
            } while (b >= 0x20)
            val dlng = if (result and 1 != 0) (result shr 1).inv() else result shr 1
            lng += dlng

            val p = LatLng(lat.toDouble() / 1E5, lng.toDouble() / 1E5)
            poly.add(p)
        }
        return poly
    }
}