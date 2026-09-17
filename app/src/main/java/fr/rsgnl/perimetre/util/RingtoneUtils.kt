package fr.rsgnl.perimetre.util

import android.content.ContentUris
import android.content.Context
import android.media.RingtoneManager
import android.net.Uri
import android.provider.MediaStore
import fr.rsgnl.perimetre.R

/** Une sonnerie disponible sur l'appareil. */
data class RingtoneInfo(val title: String, val uri: Uri)

/**
 * Énumération des sonneries de type "alarme"/"sonnerie" disponibles.
 */
object RingtoneUtils {

    fun list(context: Context): List<RingtoneInfo> {
        val result = mutableListOf<RingtoneInfo>()
        try {
            val projection = arrayOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE)
            // Colonnes en littéral pour rester compatible avec toutes les versions.
            val selection = "is_alarm=1 OR is_ringtone=1"
            val cursor = context.contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                projection,
                selection,
                null,
                "${MediaStore.Audio.Media.TITLE} ASC"
            )
            cursor?.use { c ->
                val idCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val titleCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                while (c.moveToNext()) {
                    val id = c.getLong(idCol)
                    val title = c.getString(titleCol) ?: context.getString(R.string.sound_unknown_title)
                    val uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id)
                    result.add(RingtoneInfo(title, uri))
                }
            }
        } catch (ignored: Exception) {
            // Colonne absente (anciennes versions) ou autre : liste vide.
        }
        return result
    }

    /** Titre lisible d'une sonnerie (ou "Défaut (système)" si null). */
    fun title(context: Context, uri: String?): String {
        if (uri == null) return context.getString(R.string.sound_default_system)
        return try {
            val ringtone = RingtoneManager.getRingtone(context, Uri.parse(uri))
            ringtone?.getTitle(context) ?: context.getString(R.string.sound_custom)
        } catch (e: Exception) {
            context.getString(R.string.sound_custom)
        }
    }
}
