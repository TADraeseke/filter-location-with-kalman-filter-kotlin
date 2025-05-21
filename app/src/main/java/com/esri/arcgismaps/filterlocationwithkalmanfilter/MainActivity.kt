package com.esri.arcgismaps.filterlocationwithkalmanfilter

import android.os.Bundle
import android.Manifest
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import com.arcgismaps.ApiKey
import com.arcgismaps.ArcGISEnvironment
import com.arcgismaps.BuildConfig
import com.esri.arcgismaps.filterlocationwithkalmanfilter.screens.FilterLocationWithKalmanFilterScreen

class MainActivity : ComponentActivity() {
    private var isLocationPermissionGranted = false

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            isLocationPermissionGranted = true
        } else {
            Toast.makeText(this, "Location permission is required to run this sample!", Toast.LENGTH_SHORT).show()
        }
        setContent {
            FilterLocationWithKalmanFilterScreen(isGranted)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // authentication with an API key or named user is
        // required to access basemaps and other location services
        ArcGISEnvironment.apiKey = ApiKey.create("YOUR_API_KEY_HERE")
        ArcGISEnvironment.applicationContext = applicationContext

        requestLocationPermission()
    }

    private fun requestLocationPermission() {
        requestPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
    }
}
