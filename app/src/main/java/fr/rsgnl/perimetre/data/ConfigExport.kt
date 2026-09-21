package fr.rsgnl.perimetre.data

/**
 * Structure d'export/import de la configuration complète
 * (paramètres globaux + alarmes).
 *
 * Sérialisée en JSON via Gson. Le champ [version] permet d'évoluer
 * le format sans casser la compatibilité.
 */
data class ConfigExport(
    val version: Int = 1,
    val settings: AppSettings,
    val alarms: List<Alarm>
)
