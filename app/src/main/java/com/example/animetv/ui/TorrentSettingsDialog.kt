package com.example.animetv.ui

import android.content.Context
import android.view.LayoutInflater
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.example.animetv.R
import com.example.animetv.core.torrent.TorrentSettingsStore

/**
 * The app-wide torrent search settings editor (Jackett/TorrServer URLs, quality/language/file-size
 * filters, episode click action). Shared between [DetailActivity] (its per-episode torrent dialog
 * and top-level Ajustes entry points) and the main screen's top-bar Ajustes button, so there is a
 * single place to edit it.
 */
object TorrentSettingsDialog {
    fun show(context: Context, onSaved: (() -> Unit)? = null) {
        val dialogView = LayoutInflater.from(context).inflate(R.layout.dialog_torrent_settings, null)
        val editTorrServer = dialogView.findViewById<EditText>(R.id.editTorrServerUrl)
        val editJackett = dialogView.findViewById<EditText>(R.id.editJackettUrl)
        val editJackettKey = dialogView.findViewById<EditText>(R.id.editJackettApiKey)
        val btnCancel = dialogView.findViewById<Button>(R.id.btnCancelTorrentSettings)
        val btnSave = dialogView.findViewById<Button>(R.id.btnSaveTorrentSettings)

        val rgQuality = dialogView.findViewById<RadioGroup>(R.id.rgQuality)
        val rbQuality1080p = dialogView.findViewById<RadioButton>(R.id.rbQuality1080p)
        val rbQuality720p = dialogView.findViewById<RadioButton>(R.id.rbQuality720p)
        val rbQualityAll = dialogView.findViewById<RadioButton>(R.id.rbQualityAll)
        val chkAllow4k = dialogView.findViewById<CheckBox>(R.id.chkAllow4k)
        val txtQualitySummary = dialogView.findViewById<TextView>(R.id.txtQualitySummary)

        val rgLanguage = dialogView.findViewById<RadioGroup>(R.id.rgLanguage)
        val rbLangSpanish = dialogView.findViewById<RadioButton>(R.id.rbLangSpanish)
        val rbLangDual = dialogView.findViewById<RadioButton>(R.id.rbLangDual)
        val rbLangSub = dialogView.findViewById<RadioButton>(R.id.rbLangSub)
        val rbLangAll = dialogView.findViewById<RadioButton>(R.id.rbLangAll)
        val txtLanguageSummary = dialogView.findViewById<TextView>(R.id.txtLanguageSummary)

        val rgEpisodeAction = dialogView.findViewById<RadioGroup>(R.id.rgEpisodeAction)
        val rbActionWeb = dialogView.findViewById<RadioButton>(R.id.rbActionWeb)
        val rbActionTorrent = dialogView.findViewById<RadioButton>(R.id.rbActionTorrent)
        val rbActionAsk = dialogView.findViewById<RadioButton>(R.id.rbActionAsk)
        val txtActionSummary = dialogView.findViewById<TextView>(R.id.txtActionSummary)

        val rgFileSize = dialogView.findViewById<RadioGroup>(R.id.rgFileSize)
        val rbSizeAll = dialogView.findViewById<RadioButton>(R.id.rbSizeAll)
        val rbSize15Gb = dialogView.findViewById<RadioButton>(R.id.rbSize15Gb)
        val rbSize3Gb = dialogView.findViewById<RadioButton>(R.id.rbSize3Gb)
        val rbSize6Gb = dialogView.findViewById<RadioButton>(R.id.rbSize6Gb)
        val rbSize12Gb = dialogView.findViewById<RadioButton>(R.id.rbSize12Gb)
        val txtFileSizeSummary = dialogView.findViewById<TextView>(R.id.txtFileSizeSummary)

        fun updateQualitySummary(id: Int) {
            txtQualitySummary.text = when (id) {
                R.id.rbQuality720p -> "✔ Activo: 720p"
                R.id.rbQualityAll -> "✔ Activo: Cualquiera (HD)"
                else -> "✔ Activo: 1080p (Óptimo)"
            }
        }

        fun updateLanguageSummary(id: Int) {
            txtLanguageSummary.text = when (id) {
                R.id.rbLangSpanish -> "✔ Activo: 🇪🇸 Solo Español"
                R.id.rbLangDual -> "✔ Activo: 🌐 Dual Audio"
                R.id.rbLangSub -> "✔ Activo: 💬 Sub / Orig"
                else -> "✔ Activo: 🌍 Todos"
            }
        }

        fun updateActionSummary(id: Int) {
            txtActionSummary.text = when (id) {
                R.id.rbActionTorrent -> "✔ Activo: ⚡ TOR"
                R.id.rbActionAsk -> "✔ Activo: ❓ Preguntar"
                else -> "✔ Activo: ▶ WEB"
            }
        }

        fun updateFileSizeSummary(id: Int) {
            txtFileSizeSummary.text = when (id) {
                R.id.rbSize15Gb -> "✔ Activo: ≤ 1.5 GB"
                R.id.rbSize3Gb -> "✔ Activo: ≤ 3 GB"
                R.id.rbSize6Gb -> "✔ Activo: ≤ 6 GB"
                R.id.rbSize12Gb -> "✔ Activo: ≤ 12 GB"
                else -> "✔ Activo: Sin límite"
            }
        }

        // Initialize values from store
        when (TorrentSettingsStore.getQualityFilter(context)) {
            "720p" -> rgQuality.check(R.id.rbQuality720p)
            "all" -> rgQuality.check(R.id.rbQualityAll)
            else -> rgQuality.check(R.id.rbQuality1080p)
        }
        chkAllow4k.isChecked = !TorrentSettingsStore.isDisallow4k(context)

        when (TorrentSettingsStore.getLanguageFilter(context)) {
            "spanish_only" -> rgLanguage.check(R.id.rbLangSpanish)
            "dual_audio" -> rgLanguage.check(R.id.rbLangDual)
            "sub_only" -> rgLanguage.check(R.id.rbLangSub)
            else -> rgLanguage.check(R.id.rbLangAll)
        }

        when (TorrentSettingsStore.getEpisodeClickAction(context)) {
            "torrent" -> rgEpisodeAction.check(R.id.rbActionTorrent)
            "ask" -> rgEpisodeAction.check(R.id.rbActionAsk)
            else -> rgEpisodeAction.check(R.id.rbActionWeb)
        }

        val currentMaxSize = TorrentSettingsStore.getMaxFileSizeGb(context)
        when {
            currentMaxSize in 1.4f..1.6f -> rgFileSize.check(R.id.rbSize15Gb)
            currentMaxSize in 2.9f..3.1f -> rgFileSize.check(R.id.rbSize3Gb)
            currentMaxSize in 5.9f..6.1f -> rgFileSize.check(R.id.rbSize6Gb)
            currentMaxSize in 11.9f..12.1f -> rgFileSize.check(R.id.rbSize12Gb)
            else -> rgFileSize.check(R.id.rbSizeAll)
        }

        updateQualitySummary(rgQuality.checkedRadioButtonId)
        updateLanguageSummary(rgLanguage.checkedRadioButtonId)
        updateActionSummary(rgEpisodeAction.checkedRadioButtonId)
        updateFileSizeSummary(rgFileSize.checkedRadioButtonId)

        rgQuality.setOnCheckedChangeListener { _, id -> updateQualitySummary(id) }
        rgLanguage.setOnCheckedChangeListener { _, id -> updateLanguageSummary(id) }
        rgEpisodeAction.setOnCheckedChangeListener { _, id -> updateActionSummary(id) }
        rgFileSize.setOnCheckedChangeListener { _, id -> updateFileSizeSummary(id) }

        editTorrServer.setText(TorrentSettingsStore.getTorrServerUrl(context))
        editJackett.setText(TorrentSettingsStore.getJackettUrl(context))
        editJackettKey.setText(TorrentSettingsStore.getJackettApiKey(context))

        val dialog = AlertDialog.Builder(context)
            .setView(dialogView)
            .create()

        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        btnCancel.setOnClickListener { dialog.dismiss() }
        btnSave.setOnClickListener {
            val qualityChoice = when (rgQuality.checkedRadioButtonId) {
                R.id.rbQuality720p -> "720p"
                R.id.rbQualityAll -> "all"
                else -> "1080p"
            }
            TorrentSettingsStore.setQualityFilter(context, qualityChoice)
            TorrentSettingsStore.setDisallow4k(context, !chkAllow4k.isChecked)

            val langChoice = when (rgLanguage.checkedRadioButtonId) {
                R.id.rbLangSpanish -> "spanish_only"
                R.id.rbLangDual -> "dual_audio"
                R.id.rbLangSub -> "sub_only"
                else -> "all"
            }
            TorrentSettingsStore.setLanguageFilter(context, langChoice)

            val actionChoice = when (rgEpisodeAction.checkedRadioButtonId) {
                R.id.rbActionTorrent -> "torrent"
                R.id.rbActionAsk -> "ask"
                else -> "web"
            }
            TorrentSettingsStore.setEpisodeClickAction(context, actionChoice)

            val sizeChoice = when (rgFileSize.checkedRadioButtonId) {
                R.id.rbSize15Gb -> 1.5f
                R.id.rbSize3Gb -> 3.0f
                R.id.rbSize6Gb -> 6.0f
                R.id.rbSize12Gb -> 12.0f
                else -> 0.0f
            }
            TorrentSettingsStore.setMaxFileSizeGb(context, sizeChoice)

            val tsUrl = editTorrServer.text.toString().trim()
            val jUrl = editJackett.text.toString().trim()
            val jKey = editJackettKey.text.toString().trim()

            TorrentSettingsStore.setTorrServerUrl(context, tsUrl)
            TorrentSettingsStore.setJackettUrl(context, jUrl)
            TorrentSettingsStore.setJackettApiKey(context, jKey)

            Toast.makeText(context, "Ajustes de Torrents guardados", Toast.LENGTH_SHORT).show()
            dialog.dismiss()
            onSaved?.invoke()
        }

        dialog.show()
    }
}
