package io.github.jqssun.gpssetter.ui


import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.text.method.DigitsKeyListener
import android.view.MenuItem
import android.widget.EditText
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.preference.DropDownPreference
import androidx.preference.EditTextPreference
import androidx.preference.Preference
import androidx.preference.PreferenceDataStore
import androidx.preference.PreferenceFragmentCompat
import io.github.jqssun.gpssetter.R
import io.github.jqssun.gpssetter.databinding.ActivitySettingsBinding
import io.github.jqssun.gpssetter.utils.JoystickService
import io.github.jqssun.gpssetter.utils.PrefManager
import io.github.jqssun.gpssetter.utils.ext.showToast

class ActivitySettings : AppCompatActivity() {

    private val binding by lazy {
        ActivitySettingsBinding.inflate(layoutInflater)
    }

    class SettingPreferenceDataStore() : PreferenceDataStore() {
        override fun getBoolean(key: String?, defValue: Boolean): Boolean {
            return when (key) {
                "system_hooked" -> PrefManager.isSystemHooked
                "random_position" -> PrefManager.isRandomPosition
                "update_disabled" -> PrefManager.isUpdateDisabled
                "joystick_enabled" -> PrefManager.isJoystickEnabled
                else -> throw IllegalArgumentException("Invalid key $key")
            }
        }

        override fun putBoolean(key: String?, value: Boolean) {
            return when (key) {
                "system_hooked" -> PrefManager.isSystemHooked = value
                "random_position" -> PrefManager.isRandomPosition = value
                "update_disabled" -> PrefManager.isUpdateDisabled = value
                "joystick_enabled" -> PrefManager.isJoystickEnabled = value
                else -> throw IllegalArgumentException("Invalid key $key")
            }
        }

        override fun getString(key: String?, defValue: String?): String? {
            return when (key) {
                "accuracy_level" -> PrefManager.accuracy
                "map_type" -> PrefManager.mapType.toString()
                "dark_theme" -> PrefManager.darkTheme.toString()
                else -> throw IllegalArgumentException("Invalid key $key")
            }
        }

        override fun putString(key: String?, value: String?) {
            return when (key) {
                "accuracy_level" -> PrefManager.accuracy = value
                "map_type" -> PrefManager.mapType = value!!.toInt()
                "dark_theme" -> PrefManager.darkTheme = value!!.toInt()
                else -> throw IllegalArgumentException("Invalid key $key")
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT))

        setContentView(binding.root)
        theme.applyStyle(com.google.android.material.R.style.Theme_Material3_DynamicColors_DayNight_NoActionBar, true)
        setSupportActionBar(binding.toolbar)
        if (savedInstanceState == null) {
            supportFragmentManager
                .beginTransaction()
                .replace(R.id.settings_container, SettingsPreferenceFragment())
                .commit()
        }
        supportActionBar?.setDisplayHomeAsUpEnabled(true)


        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    finish()
                }
            }
        )

    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            onBackPressedDispatcher.onBackPressed()
        }
        return super.onOptionsItemSelected(item)
    }

    class SettingsPreferenceFragment : PreferenceFragmentCompat() {

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            preferenceManager?.preferenceDataStore = SettingPreferenceDataStore()
            setPreferencesFromResource(R.xml.preferences, rootKey)

            findPreference<EditTextPreference>("accuracy_level")?.let {
                it.summary = "${PrefManager.accuracy} m"
                it.setOnBindEditTextListener { editText ->
                    editText.inputType = InputType.TYPE_CLASS_NUMBER;
                    editText.keyListener = DigitsKeyListener.getInstance("0123456789.,");
                    editText.addTextChangedListener(getCommaReplacerTextWatcher(editText));
                }

                it.setOnPreferenceChangeListener { preference, newValue ->
                    try {
                        newValue as String?
                        preference.summary = "$newValue m"
                    } catch (n: NumberFormatException) {
                        n.printStackTrace()
                        Toast.makeText(
                            requireContext(),
                            getString(R.string.enter_valid_input),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                    true
                }
            }

            findPreference<DropDownPreference>("dark_theme")?.setOnPreferenceChangeListener { _, newValue ->
                val newMode = (newValue as String).toInt()
                if (PrefManager.darkTheme != newMode) {
                    AppCompatDelegate.setDefaultNightMode(newMode)
                    activity?.recreate()
                }
                true
            }

            findPreference<Preference>("joystick_enabled")?.let {
                it.setOnPreferenceClickListener {
                    if (askOverlayPermission()){
                        if (isJoystickRunning()) {
                            requireContext().stopService(Intent(context,JoystickService::class.java))
                            it.summary = "Joystick disabled"
                        } else if (PrefManager.isStarted) {
                            requireContext().startService(Intent(context,JoystickService::class.java))
                            it.summary = "Joystick enabled"
                        } else {
                            requireContext().showToast(requireContext().getString(R.string.location_not_select))
                        }
                    }
                    true
                }
            }
        }

        private fun isJoystickRunning(): Boolean {
            var isRunning = false
            val manager = requireContext().getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager? ?: return false
            for (service in manager.getRunningServices(Int.MAX_VALUE)) {
                if ("io.github.jqssun.gpssetter.utils.JoystickService" == service.service.className) {
                    isRunning = true
                }
            }
            return isRunning
        }


        private fun askOverlayPermission() : Boolean {
            if (Settings.canDrawOverlays(context)){
                return true
            }
            val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context?.applicationContext?.packageName}" ))
            requireContext().startActivity(intent)
            return false
        }


        private fun getCommaReplacerTextWatcher(editText: EditText): TextWatcher {
            return object : TextWatcher {
                override fun beforeTextChanged(
                    charSequence: CharSequence,
                    i: Int,
                    i1: Int,
                    i2: Int
                ) {
                }

                override fun onTextChanged(
                    charSequence: CharSequence,
                    i: Int,
                    i1: Int,
                    i2: Int
                ) {
                }

                override fun afterTextChanged(editable: Editable) {
                    val text = editable.toString()
                    if (text.contains(",")) {
                        editText.setText(text.replace(",", "."))
                        editText.setSelection(editText.text.length)
                    }
                }
            }
        }
    }
}