package com.example.wifi

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationManager
import androidx.core.content.PermissionChecker

/**
 * Simple location provider that gets the last known GPS/network location.
 * Does not actively request location updates — relies on other system components
 * (e.g. Wi-Fi scanning already requires ACCESS_FINE_LOCATION) to keep it fresh.
 */
object LocationProvider {

    @SuppressLint("MissingPermission")
    fun getLastKnownLocation(context: Context): Location? {
        if (PermissionChecker.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) != PermissionChecker.PERMISSION_GRANTED
        ) {
            return null
        }

        val locationManager =
            context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null

        val providers = listOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
            LocationManager.PASSIVE_PROVIDER
        )

        return providers
            .mapNotNull { runCatching { locationManager.getLastKnownLocation(it) }.getOrNull() }
            .minByOrNull { it.accuracy }
    }
}

