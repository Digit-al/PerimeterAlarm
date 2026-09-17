package fr.rsgnl.perimetre.ui.components

import android.content.Context
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import fr.rsgnl.perimetre.R
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polygon
import java.io.File
import kotlin.math.cos

/**
 * Initialisation d'osmdroid, une seule fois par processus.
 */
private object OsmInit {
    @Volatile
    var done = false
}

@Composable
fun ensureOsmdroidInitialized(context: Context) {
    if (!OsmInit.done) {
        synchronized(OsmInit) {
            if (!OsmInit.done) {
                val prefs = context.getSharedPreferences("osmdroid", Context.MODE_PRIVATE)
                val cfg = Configuration.getInstance()
                cfg.load(context, prefs)
                cfg.setUserAgentValue(context.packageName)
                // Cache des tuiles en stockage interne (évite les permissions de stockage).
                try {
                    cfg.setOsmdroidBasePath(File(context.filesDir.absolutePath, "osmdroid"))
                } catch (ignored: Throwable) {
                }
                OsmInit.done = true
            }
        }
    }
}

/**
 * Carte OpenStreetMap avec :
 *  - un marqueur centré sur [centerLat]/[centerLng],
 *  - un cercle de rayon [radiusMeters],
 *  - un point bleu sur la position actuelle ([currentLat]/[currentLng]) si fourni,
 *  - un callback au toucher de la carte (pour déplacer la localisation).
 *
 * @param fitTrigger Augmentez cette valeur pour recentrer/zoomer sur le cercle.
 */
@Composable
fun OsmMap(
    centerLat: Double,
    centerLng: Double,
    radiusMeters: Int,
    onMapClick: (lat: Double, lng: Double) -> Unit,
    modifier: Modifier = Modifier,
    fitTrigger: Int = 0,
    currentLat: Double? = null,
    currentLng: Double? = null
) {
    val context = LocalContext.current
    ensureOsmdroidInitialized(context)

    val onMapClickRef = rememberUpdatedState(onMapClick)
    val currentDotIcon = remember {
        androidx.core.content.ContextCompat.getDrawable(context, R.drawable.ic_current_location)
    }

    var mapRef by remember { mutableStateOf<MapView?>(null) }
    var markerRef by remember { mutableStateOf<Marker?>(null) }
    var polygonRef by remember { mutableStateOf<Polygon?>(null) }
    var currentRef by remember { mutableStateOf<Marker?>(null) }
    var lastFitTrigger by remember { mutableIntStateOf(-1) }
    var resumed by remember { mutableStateOf(false) }
    var laidOut by remember { mutableStateOf(false) }
    val handler = remember { Handler(Looper.getMainLooper()) }
    val touchSlopPx = remember {
        val d = 20f * context.resources.displayMetrics.density
        d * d
    }

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            val mapView = MapView(ctx)
            mapView.setTileSource(TileSourceFactory.MAPNIK)
            mapView.setMultiTouchControls(true)
            mapView.zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
            mapView.isFocusable = true
            mapView.isFocusableInTouchMode = false

            // Détection de tap simple (pour changer la localisation).
            var downX = 0f
            var downY = 0f
            var downTime = 0L
            mapView.setOnTouchListener { _, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        downX = event.x
                        downY = event.y
                        downTime = System.currentTimeMillis()
                    }
                    MotionEvent.ACTION_UP -> {
                        val dx = event.x - downX
                        val dy = event.y - downY
                        val dt = System.currentTimeMillis() - downTime
                        if (dx * dx + dy * dy < touchSlopPx && dt < 300L) {
                            val geo = mapView.projection.fromPixels(event.x.toInt(), event.y.toInt())
                            if (geo != null) {
                                handler.post { onMapClickRef.value(geo.latitude, geo.longitude) }
                            }
                        }
                    }
                }
                false // laisse la carte gérer le geste (pan/zoom)
            }

            // Signale le premier layout pour pouvoir ajuster le zoom de façon fiable.
            mapView.addOnFirstLayoutListener(object : MapView.OnFirstLayoutListener {
                override fun onFirstLayout(view: View, l: Int, t: Int, r: Int, b: Int) {
                    handler.post { laidOut = true }
                }
            })

            mapRef = mapView

            // Wrapper FrameLayout qui clippe : osmdroid 6.1.20 (MapView = ViewGroup)
            // déborde de ses limites lors du zoom/pan car le scroll interne
            // décale les enfants sans clipper. Le FrameLayout parent force le clip.
            val wrapper = FrameLayout(ctx).apply {
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
                clipChildren = true
                clipToPadding = true
                addView(mapView, FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                ))
            }
            wrapper
        },
        update = { _ ->
            val mapView = mapRef ?: return@AndroidView
            val center = GeoPoint(centerLat, centerLng)

            // Marqueur de l'alarme
            var marker = markerRef
            if (marker == null) {
                marker = Marker(mapView)
                marker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                mapView.overlays.add(marker)
                markerRef = marker
            }
            marker.setPosition(center)

            // Cercle (polygone)
            var polygon = polygonRef
            if (polygon == null) {
                polygon = Polygon(mapView)
                polygon.fillColor = Color.argb(55, 21, 101, 192)
                polygon.strokeColor = Color.argb(200, 21, 101, 192)
                polygon.strokeWidth = 4f
                mapView.overlays.add(polygon)
                polygonRef = polygon
            }
            polygon.setPoints(Polygon.pointsAsCircle(center, radiusMeters.toDouble()))

            // Point de position actuelle (point bleu)
            if (currentLat != null && currentLng != null) {
                var cm = currentRef
                if (cm == null) {
                    cm = Marker(mapView)
                    cm.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                    cm.setIcon(currentDotIcon)
                    mapView.overlays.add(cm)
                    currentRef = cm
                }
                cm.setPosition(GeoPoint(currentLat, currentLng))
            }

            if (!resumed) {
                mapView.onResume()
                resumed = true
            }

            // Ajustement : uniquement une fois la carte disposée, ou à la demande.
            if (laidOut && lastFitTrigger != fitTrigger) {
                fitToRadius(mapView, center, radiusMeters)
                lastFitTrigger = fitTrigger
            }

            mapView.invalidate()
        }
    )

    DisposableEffect(Unit) {
        onDispose {
            mapRef?.onPause()
        }
    }
}

/** Réglages le zoom pour que le cercle de rayon [radiusM] tienne à l'écran. */
private fun fitToRadius(mapView: MapView, center: GeoPoint, radiusM: Int) {
    val r = radiusM.toDouble().coerceAtLeast(20.0)
    val margin = 1.8
    val dLat = (r / 111320.0) * margin
    val dLon = (r / (111320.0 * cos(Math.toRadians(center.latitude)))) * margin
    val corners = listOf(
        GeoPoint(center.latitude + dLat, center.longitude - dLon),
        GeoPoint(center.latitude + dLat, center.longitude + dLon),
        GeoPoint(center.latitude - dLat, center.longitude - dLon),
        GeoPoint(center.latitude - dLat, center.longitude + dLon)
    )
    val bbox = BoundingBox.fromGeoPointsSafe(corners)
    mapView.controller.setCenter(center)
    mapView.zoomToBoundingBox(bbox, false)
}
