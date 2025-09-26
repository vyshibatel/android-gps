package io.github.jqssun.gpssetter.xposed
import de.robv.android.xposed.XSharedPreferences
import de.robv.android.xposed.XposedBridge
import io.github.jqssun.gpssetter.BuildConfig

class Xshare {

    private var xPref: XSharedPreferences? = null
    private var lastLoggedAltitude: Float = Float.NaN // Track last logged value to reduce log spam

    private fun pref() : XSharedPreferences {
        xPref = XSharedPreferences(BuildConfig.APPLICATION_ID,"${BuildConfig.APPLICATION_ID}_prefs")
        return xPref as XSharedPreferences
    }

    val isStarted : Boolean
        get() = pref().getBoolean(
            "start",
            false
        )

    val getLat: Double
        get() = pref().getFloat(
            "latitude",
            45.0000000.toFloat()
        ).toDouble()


    val getLng : Double
        get() = pref().getFloat(
            "longitude",
            0.0000000.toFloat()
        ).toDouble()

    // --- НОВЫЙ КОД ---
    val getAltitude : Float
        get() {
            val alt = pref().getFloat("altitude", 0.0F)
            // Логируем только при изменении значения (чтобы не засорять логи)
            if (alt != 0.0F && alt != lastLoggedAltitude) {
                XposedBridge.log("Xshare: Altitude changed to: $alt")
                lastLoggedAltitude = alt
            }
            return alt
        }

    val getSpeed: Float
        get() = pref().getFloat("speed", 0.0F)

    val getBearing: Float
        get() = pref().getFloat("bearing", 0.0F)
    // --- КОНЕЦ НОВОГО КОДА ---

    val isHookedSystem : Boolean
        get() = pref().getBoolean(
            "system_hooked",
            true
        )

    val isRandomPosition :Boolean
        get() = pref().getBoolean(
            "random_position",
            false
        )

    val accuracy : String?
        get() = pref().getString("accuracy_level","10")

    val reload = pref().reload()
}