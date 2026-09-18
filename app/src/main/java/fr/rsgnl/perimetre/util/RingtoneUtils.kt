package fr.rsgnl.perimetre.util

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import androidx.core.content.ContextCompat
import fr.rsgnl.perimetre.R

/** Une sonnerie disponible sur l'appareil. */
data class RingtoneInfo(val title: String, val uri: Uri)

/**
 * Énumération des sonneries de type "alarme"/"sonnerie" disponibles.
 */
object RingtoneUtils {

    private const val TAG = "RingtoneUtils"

    /** Permission audio requise selon la version Android. */
    fun requiredPermission(): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            Manifest.permission.READ_MEDIA_AUDIO
        else
            Manifest.permission.READ_EXTERNAL_STORAGE

    fun hasPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, requiredPermission()) == PackageManager.PERMISSION_GRANTED

    fun list(context: Context): List<RingtoneInfo> {
        val result = mutableListOf<RingtoneInfo>()
        try {
            val projection = arrayOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE)
            val selection = "is_alarm=1 OR is_ringtone=1 OR is_notification=1"
            val cursor = context.contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                projection,
                selection,
                null,
                "${MediaStore.Audio.Media.TITLE} ASC"
            )
            if (cursor == null) {
                Log.w(TAG, "MediaStore cursor is null — permission missing? hasPermission=${hasPermission(context)}")
                return result
            }
            cursor.use { c ->
                val idCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val titleCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                while (c.moveToNext()) {
                    val id = c.getLong(idCol)
                    val title = c.getString(titleCol) ?: context.getString(R.string.sound_unknown_title)
                    val uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id)
                    result.add(RingtoneInfo(title, uri))
                }
            }
            Log.i(TAG, "Listed ${result.size} ringtones")
        } catch (e: Exception) {
            Log.w(TAG, "MediaStore query failed: ${e.message}", e)
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
