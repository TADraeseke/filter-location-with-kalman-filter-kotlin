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

import android.annotation.SuppressLint
import android.content.Context
import android.os.Looper
import com.arcgismaps.Color
import com.arcgismaps.geometry.GeometryEngine
import com.arcgismaps.geometry.Point
import com.arcgismaps.geometry.PolylineBuilder
import com.arcgismaps.geometry.SpatialReference
import com.arcgismaps.location.CustomLocationDataSource
import com.arcgismaps.location.Location
import com.arcgismaps.mapping.symbology.SimpleLineSymbol
import com.arcgismaps.mapping.symbology.SimpleLineSymbolStyle
import com.arcgismaps.mapping.symbology.SimpleMarkerSymbol
import com.arcgismaps.mapping.symbology.SimpleMarkerSymbolStyle
import com.arcgismaps.mapping.view.Graphic
import com.arcgismaps.mapping.view.GraphicsOverlay
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import java.time.Instant

class FusedLocationWithKalmanFilterProvider(applicationContext: Context, private val graphicsOverlay: GraphicsOverlay) :
    CustomLocationDataSource.LocationProvider {

    private val _headings = MutableSharedFlow<Double>()

    // Note the override property here, required to implement the LocationProvider interface
    override val headings: Flow<Double> = _headings.asSharedFlow()

    private val _locations = MutableSharedFlow<Location>()

    // Note the override property here, required to implement the LocationProvider interface
    override val locations: Flow<Location> = _locations.asSharedFlow()

    // Set up fused location provider states
    private var fusedLocationProviderClient = LocationServices.getFusedLocationProviderClient(applicationContext)
    private var locationCallback: LocationCallback? = null
    private var emitLocationsJob: Job? = null
    private var priority: Int = Priority.PRIORITY_HIGH_ACCURACY
    private var intervalInSeconds: Long = 1L

    private var kalmanFilter: KalmanFilter? = null

    var previousTimestamp: Long? = null

    // Polyline builders for each location type
    private val measuredPolylineBuilder = PolylineBuilder(SpatialReference(wkid = 6340))
    private val predictedPolylineBuilder = PolylineBuilder(SpatialReference(wkid = 6340))
    private val weightedPolylineBuilder = PolylineBuilder(SpatialReference(wkid = 6340))

    // Graphics for the polylines
    private val measuredPolylineGraphic = Graphic(
        measuredPolylineBuilder.toGeometry(), SimpleLineSymbol(SimpleLineSymbolStyle.Solid, Color.green, 3f)
    )
    private val predictedPolylineGraphic = Graphic(
        predictedPolylineBuilder.toGeometry(), SimpleLineSymbol(SimpleLineSymbolStyle.Solid, Color.red, 3f)
    )
    private val weightedPolylineGraphic = Graphic(
        weightedPolylineBuilder.toGeometry(), SimpleLineSymbol(SimpleLineSymbolStyle.Solid, Color.cyan, 3f)
    )

    private var lastWeightedPositionGraphic: Graphic? = null
    private var lastPredictedPositionGraphic: Graphic? = null
    private var lastMeasuredPositionGraphic: Graphic? = null

    init {
        // Add the polyline graphics to the graphics overlay
        graphicsOverlay.graphics.addAll(
            listOf(weightedPolylineGraphic, measuredPolylineGraphic, predictedPolylineGraphic)
        )
    }

    /**
     * Start the fused location provider.
     */
    fun start() {
        startNewFusedLocationWithKalmanFilterProvider(priority, intervalInSeconds)
    }

    /**
     * Stop the fused location provider.
     */
    fun stop() {
        // Stop emitting locations into the locations flow
        emitLocationsJob?.cancel()
        locationCallback?.let { fusedLocationProviderClient.removeLocationUpdates(it) }
    }

    @SuppressLint("MissingPermission") // Permission requests are handled in MainActivity
    private fun startNewFusedLocationWithKalmanFilterProvider(
        priority: Int = Priority.PRIORITY_HIGH_ACCURACY, intervalInSeconds: Long = 1L
    ) {
        // Cancel any current jobs emitting into locations
        emitLocationsJob?.cancel()

        // Clear any previous location updates
        locationCallback?.let {
            fusedLocationProviderClient.removeLocationUpdates(it)
        }

        // Create a location request with the desired priority and interval
        val locationRequest = LocationRequest.Builder(priority, intervalInSeconds * 1000).build()

        // Create a new location callback to emit location updates
        locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {

                locationResult.lastLocation?.let { fusedLocation ->
                    // Get the last known location
                    val wgs84Position = Point(fusedLocation.longitude, fusedLocation.latitude, SpatialReference.wgs84())
                    // Project the location into an equidistant projection like UTM
                    val utm11NPosition = GeometryEngine.projectOrNull(wgs84Position, SpatialReference(wkid = 6340))
                    utm11NPosition?.let { measuredPosition ->
                        // Initialize the kalman filter if it is null
                        if (kalmanFilter == null) {
                            kalmanFilter = KalmanFilter(measuredPosition.x, measuredPosition.y, 0.0, 0.0)
                        }
                        kalmanFilter?.let { kalmanFilter ->
                            // Adjust measurement noise based on GPS accuracy
                            val accuracy = fusedLocation.accuracy
                            when {
                                // Low accuracy
                                accuracy > 20 -> kalmanFilter.adjustMeasurementNoise(1.5) // Increase measurement noise
                                // High accuracy
                                accuracy < 5 -> kalmanFilter.adjustMeasurementNoise(0.9) // Decrease measurement noise
                            }
                            // Get the weighted average of the predicted and measured positions
                            val weightedPositionArray = kalmanFilter.getWeightedAverage(
                                predicted = kalmanFilter.getState(), // Predicted state from Kalman filter
                                measured = doubleArrayOf(measuredPosition.x, measuredPosition.y) // Measured position
                            )
                            // Create a new Point for the weighted position
                            val weightedPosition =
                                Point(weightedPositionArray[0], weightedPositionArray[1], SpatialReference(wkid = 6340))
                            // Add the weighted point to the weighted position polyline
                            weightedPolylineBuilder.addPoint(weightedPosition)
                            weightedPolylineGraphic.geometry = weightedPolylineBuilder.toGeometry()
                            // Create a new graphic for the weighted position
                            val weightedPositionGraphic = Graphic(
                                weightedPosition, SimpleMarkerSymbol(SimpleMarkerSymbolStyle.Circle, Color.cyan, 10f)
                            )
                            // Remove the last weighted position graphic if it exists
                            graphicsOverlay.graphics.remove(lastWeightedPositionGraphic)
                            // Add the new weighted position graphic to the graphics overlay
                            graphicsOverlay.graphics.add(weightedPositionGraphic)
                            // Keep track of the last weighted position graphic
                            lastWeightedPositionGraphic = weightedPositionGraphic

                            // Update the kalman filter with a new measurement [x, y]
                            kalmanFilter.update(doubleArrayOf(measuredPosition.x, measuredPosition.y))
                            // Add the measured Point to the measured position polyline
                            measuredPolylineBuilder.addPoint(measuredPosition)
                            measuredPolylineGraphic.geometry = measuredPolylineBuilder.toGeometry()
                            // Create a new graphic for the measured position
                            val measuredPositionGraphic = Graphic(
                                measuredPosition, SimpleMarkerSymbol(SimpleMarkerSymbolStyle.Cross, Color.green, 10f)
                            )
                            // Remove the last measured position graphic if it exists
                            graphicsOverlay.graphics.remove(lastMeasuredPositionGraphic)
                            // Add the new measured position graphic to the graphics overlay
                            graphicsOverlay.graphics.add(measuredPositionGraphic)
                            // Keep track of the last measured position graphic
                            lastMeasuredPositionGraphic = measuredPositionGraphic

                            // Calculate the delta time between the previous and current measured position updates
                            val deltaTime = previousTimestamp?.let { fusedLocation.time - it } ?: 0
                            // Predict the next position with given delta time, velocity and heading
                            val stateAfterPrediction = kalmanFilter.predict(
                                deltaTime = deltaTime.toDouble() / 1000.0,
                                velocity = fusedLocation.speed.toDouble(),
                                heading = fusedLocation.bearing.toDouble()
                            )
                            // Create a new Point for the predicted position
                            val predictedPosition =
                                Point(stateAfterPrediction[0], stateAfterPrediction[1], SpatialReference(wkid = 6340))
                            // Add the predicted Point to the predicted position polyline
                            predictedPolylineBuilder.addPoint(predictedPosition)
                            predictedPolylineGraphic.geometry = predictedPolylineBuilder.toGeometry()
                            // Create a new graphic for the predicted position
                            val predictedLocationGraphic = Graphic(
                                predictedPosition, SimpleMarkerSymbol(
                                    SimpleMarkerSymbolStyle.X, Color.red, 10f
                                )
                            )
                            // Remove the last predicted position graphic if it exists
                            graphicsOverlay.graphics.remove(lastPredictedPositionGraphic)
                            // Add the new predicted position graphic to the graphics overlay
                            graphicsOverlay.graphics.add(predictedLocationGraphic)
                            // Keep track of the last predicted position graphic
                            lastPredictedPositionGraphic = predictedLocationGraphic
                        }

                        // Emit the fused location object into the Location Provider's overridden locations flow
                        emitLocationsJob = CoroutineScope(Dispatchers.IO).launch {
                            // Emit the ArcGIS location object into the Location Provider's overridden locations flow
                            _locations.emit(
                                createKalmanFilterLocationFromFusedLocation(
                                    fusedLocation = fusedLocation,
                                    weightedLocation = lastWeightedPositionGraphic!!.geometry as Point
                                )
                            )
                        }
                    }
                    // Keep track of the previous timestamp
                    previousTimestamp = fusedLocation.time
                }
            }
        }
        // Requests location updates with the given request and results delivered to the given listener on the specified
        // Looper
        locationCallback?.let { locationCallback ->
            fusedLocationProviderClient.requestLocationUpdates(
                locationRequest, locationCallback, Looper.getMainLooper()
            )
        }
    }
}

/**
 * Creates an ArcGIS Maps SDK Location object from the Kalman filter weighted position.
 */
private fun createKalmanFilterLocationFromFusedLocation(
    fusedLocation: android.location.Location, weightedLocation: Point
): Location {
    return Location.create(
        position = Point(
            x = weightedLocation.x, y = weightedLocation.y, SpatialReference(wkid = 6340)
        ),
        horizontalAccuracy = fusedLocation.accuracy.toDouble(),
        verticalAccuracy = fusedLocation.verticalAccuracyMeters.toDouble(),
        speed = fusedLocation.speed.toDouble(),
        course = fusedLocation.bearing.toDouble(),
        // If the timestamp is more than 5 seconds old, set lastKnown to true
        lastKnown = (Instant.now().toEpochMilli() - fusedLocation.time) > 5000,
        timestamp = Instant.ofEpochMilli(fusedLocation.time),
    )
}
