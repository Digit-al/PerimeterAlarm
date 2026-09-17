package fr.rsgnl.perimetre.ui

import android.content.Context
import fr.rsgnl.perimetre.R

/**
 * Noms courts des jours localisés, ordonnés ISO (1 = lundi … 7 = dimanche).
 */
fun dayNames(context: Context): Array<String> = arrayOf(
    context.getString(R.string.day_mon),
    context.getString(R.string.day_tue),
    context.getString(R.string.day_wed),
    context.getString(R.string.day_thu),
    context.getString(R.string.day_fri),
    context.getString(R.string.day_sat),
    context.getString(R.string.day_sun)
)
