package fr.rsgnl.perimetre

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import fr.rsgnl.perimetre.data.AlarmRepository
import fr.rsgnl.perimetre.data.AppLanguage

/**
 * Applique la langue choisie dans les paramètres au démarrage.
 * « auto » (défaut) suit la langue du téléphone ; sinon la langue imposée (EN/FR).
 */
class PerimetreApp : Application() {

    override fun onCreate() {
        super.onCreate()
        AppCompatDelegate.setApplicationLocales(localesFor(AlarmRepository(this).loadSettings().language))
    }

    companion object {
        /** [LocaleListCompat] correspondant à un choix [AppLanguage]. */
        fun localesFor(language: String): LocaleListCompat = when (language) {
            AppLanguage.EN -> LocaleListCompat.forLanguageTags("en")
            AppLanguage.FR -> LocaleListCompat.forLanguageTags("fr")
            else -> LocaleListCompat.getEmptyLocaleList()
        }
    }
}
