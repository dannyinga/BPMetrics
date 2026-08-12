package inga.bpmetrics.ui.locations

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import androidx.core.content.ContextCompat

/**
 * Where the phone thinks it is, if it has been told it may say.
 *
 * The framework's own [LocationManager], not Play Services. A GPS fix comes from satellites and
 * needs no network, which matters because the moment someone wants to pin a venue is usually the
 * moment they are standing in a field with no signal. The fused provider would be more accurate in
 * a city and useless in the place this feature is for.
 *
 * **Entirely optional.** Coordinates are informational — the time zone is chosen, not derived — so
 * refusing permission costs nothing but the convenience of not typing two numbers. Nothing in the
 * app requires this, asks twice, or behaves differently when it is denied.
 */
object DeviceLocation {

    fun hasPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    /** What to ask for. Coarse as well as fine, because a venue does not need metre accuracy. */
    val permissions = arrayOf(
        Manifest.permission.ACCESS_COARSE_LOCATION,
        Manifest.permission.ACCESS_FINE_LOCATION
    )

    /**
     * The last known fix, or null.
     *
     * Last known rather than a fresh one: this is naming a venue, not navigating. Waiting on a
     * satellite lock would mean a spinner and a permission dialog for two numbers nothing depends
     * on. The newest fix across providers wins — GPS is more accurate, network is more likely to
     * exist indoors, and whichever is fresher is the better guess about where someone is standing.
     */
    fun lastKnown(context: Context): Pair<Double, Double>? {
        if (!hasPermission(context)) return null
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            ?: return null

        return runCatching {
            manager.getProviders(true)
                .mapNotNull { provider ->
                    @Suppress("MissingPermission")
                    manager.getLastKnownLocation(provider)
                }
                .maxByOrNull { it.time }
                ?.let { it.latitude to it.longitude }
        }.getOrNull()
    }
}
