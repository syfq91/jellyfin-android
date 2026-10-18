package org.jellyfin.mobile.book.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.RadioGroup
import android.widget.TextView
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import org.jellyfin.mobile.R
import org.readium.r2.navigator.epub.EpubPreferences
import org.readium.r2.navigator.preferences.Theme
import org.readium.r2.shared.ExperimentalReadiumApi

@OptIn(ExperimentalReadiumApi::class)
class BookSettingsBottomSheet : BottomSheetDialogFragment() {

    private var currentPreferences: EpubPreferences = EpubPreferences()
    private var onPreferencesChanged: ((EpubPreferences) -> Unit)? = null

    fun setup(
        preferences: EpubPreferences,
        onChanged: (EpubPreferences) -> Unit,
    ) {
        this.currentPreferences = preferences
        this.onPreferencesChanged = onChanged
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val view = inflater.inflate(R.layout.bottom_sheet_book_settings, container, false)

        val fontSizeText = view.findViewById<TextView>(R.id.font_size_text)
        val btnFontDecrease = view.findViewById<Button>(R.id.btn_font_decrease)
        val btnFontIncrease = view.findViewById<Button>(R.id.btn_font_increase)
        val themeRadioGroup = view.findViewById<RadioGroup>(R.id.theme_radio_group)

        var currentFontSize = currentPreferences.fontSize ?: 1.0

        fun updateFontSize(newSize: Double) {
            currentFontSize = newSize.coerceIn(0.6, 2.5)
            fontSizeText.text = "${(currentFontSize * 100).toInt()}%"
            currentPreferences = currentPreferences.copy(fontSize = currentFontSize)
            onPreferencesChanged?.invoke(currentPreferences)
        }

        fontSizeText.text = "${(currentFontSize * 100).toInt()}%"

        btnFontDecrease.setOnClickListener {
            updateFontSize(currentFontSize - 0.1)
        }

        btnFontIncrease.setOnClickListener {
            updateFontSize(currentFontSize + 0.1)
        }

        when (currentPreferences.theme) {
            Theme.LIGHT -> themeRadioGroup.check(R.id.radio_theme_light)
            Theme.SEPIA -> themeRadioGroup.check(R.id.radio_theme_sepia)
            Theme.DARK -> themeRadioGroup.check(R.id.radio_theme_dark)
            else -> themeRadioGroup.check(R.id.radio_theme_dark)
        }

        themeRadioGroup.setOnCheckedChangeListener { _, checkedId ->
            val newTheme = when (checkedId) {
                R.id.radio_theme_light -> Theme.LIGHT
                R.id.radio_theme_sepia -> Theme.SEPIA
                R.id.radio_theme_dark -> Theme.DARK
                else -> Theme.DARK
            }
            currentPreferences = currentPreferences.copy(theme = newTheme)
            onPreferencesChanged?.invoke(currentPreferences)
        }

        return view
    }

    companion object {
        const val TAG = "BookSettingsBottomSheet"
    }
}
