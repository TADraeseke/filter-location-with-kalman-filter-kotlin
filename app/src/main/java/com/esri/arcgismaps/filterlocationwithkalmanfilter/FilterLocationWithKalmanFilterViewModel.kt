/* Copyright 2025 Esri
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package com.esri.arcgismaps.filterlocationwithkalmanfilter

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.arcgismaps.location.CustomLocationDataSource
import com.arcgismaps.location.LocationDisplayAutoPanMode
import com.arcgismaps.mapping.ArcGISMap
import com.arcgismaps.mapping.BasemapStyle
import com.arcgismaps.mapping.view.GraphicsOverlay
import com.arcgismaps.mapping.view.LocationDisplay
import kotlinx.coroutines.launch

class FilterLocationWithKalmanFilterViewModel(application: Application) : AndroidViewModel(application) {

    // Create an ArcGIS map
    val arcGISMap = ArcGISMap(BasemapStyle.ArcGISNavigation)

    val graphicsOverlay = GraphicsOverlay()

    private val fusedLocationWithKalmanFilterProvider = FusedLocationWithKalmanFilterProvider(
        application,
        graphicsOverlay
    )

    /**
     * Initialize the location display with a custom location data source using the fused location provider.
     */
    fun initialize(locationDisplay: LocationDisplay) {

        fusedLocationWithKalmanFilterProvider.start()

        // Set the location display to be used by this view model
        locationDisplay.apply {
            // Set the location display's data source to a Custom Location DataSource
            dataSource = CustomLocationDataSource { fusedLocationWithKalmanFilterProvider }
            // Keep track of the job so it can be canceled elsewhere
            viewModelScope.launch {
                // Start the data source
                dataSource.start()
            }
            // Set the AutoPan mode to recenter around the location display
            setAutoPanMode(LocationDisplayAutoPanMode.Navigation)
        }
    }

    override fun onCleared() {
        super.onCleared()
        fusedLocationWithKalmanFilterProvider.stop()
    }
}


