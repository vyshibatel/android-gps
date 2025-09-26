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

    private val apiKey = "AIzaSyDbZfdOZtbsMX8cUjaZ2U3j6-i5MHusDn4" // Google Maps API Key

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

            println("RoutingService: API Key used: ${apiKey.take(10)}...")
            println("RoutingService: Requesting route from $origin to $destination")

            val response = api.getDirections(origin, destination, apiKey)

            println("RoutingService: Response status - ${response.routes.size} routes found")

            if (response.routes.isNotEmpty()) {
                val points = response.routes[0].overview_polyline.points
                println("RoutingService: Polyline points length: ${points.length}")

                val decodedPoints = decodePolyline(points)
                println("RoutingService: Decoded ${decodedPoints.size} points")

                // Тест: добавляем точки начала и конца маршрута, если они отсутствуют
                if (decodedPoints.isNotEmpty()) {
                    val result = mutableListOf<LatLng>()
                    val startLatLng = LatLng(start.lat, start.lon)
                    val endLatLng = LatLng(end.lat, end.lon)

                    // Добавляем точку начала, если она не слишком близка к первой точке маршрута
                    if (calculateDistance(startLatLng, decodedPoints.first()) > 50) {
                        result.add(startLatLng)
                    }

                    result.addAll(decodedPoints)

                    // Добавляем точку конца, если она не слишком близка к последней точке маршрута
                    if (calculateDistance(endLatLng, decodedPoints.last()) > 50) {
                        result.add(endLatLng)
                    }

                    println("RoutingService: Final route has ${result.size} points")
                    return result
                }

                return decodedPoints
            } else {
                println("RoutingService: No routes found in response")
            }
            return null
        } catch (e: Exception) {
            println("RoutingService: Error getting route: ${e.message}")
            e.printStackTrace()
            return null
        }
    }

    private fun calculateDistance(point1: LatLng, point2: LatLng): Float {
        val results = FloatArray(1)
        android.location.Location.distanceBetween(
            point1.latitude, point1.longitude,
            point2.latitude, point2.longitude,
            results
        )
        return results[0]
    }

    private fun decodePolyline(encoded: String): List<LatLng> {
        val poly = ArrayList<LatLng>()
        var index = 0
        val len = encoded.length
        var lat = 0
        var lng = 0

        println("RoutingService: Decoding polyline of length $len")

        while (index < len) {
            var b: Int
            var shift = 0
            var result = 0
            do {
                if (index >= len) break
                b = encoded[index++].code - 63
                result = result or (b and 0x1f shl shift)
                shift += 5
            } while (b >= 0x20)
            val dlat = if (result and 1 != 0) (result shr 1).inv() else result shr 1
            lat += dlat

            shift = 0
            result = 0
            do {
                if (index >= len) break
                b = encoded[index++].code - 63
                result = result or (b and 0x1f shl shift)
                shift += 5
            } while (b >= 0x20)
            val dlng = if (result and 1 != 0) (result shr 1).inv() else result shr 1
            lng += dlng

            val p = LatLng(lat.toDouble() / 1E5, lng.toDouble() / 1E5)
            poly.add(p)
        }

        println("RoutingService: Successfully decoded ${poly.size} points")
        if (poly.isNotEmpty()) {
            println("RoutingService: First point: ${poly.first()}")
            println("RoutingService: Last point: ${poly.last()}")
        }

        return poly
    }
}