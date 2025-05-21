package com.esri.arcgismaps.filterlocationwithkalmanfilter

import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

class KalmanFilter(x: Double, y: Double, vx: Double, vy: Double) {
    private var state = doubleArrayOf(x, y, vx, vy) // [x, y, vx, vy]
    private var covariance = Array(4) { i -> DoubleArray(4) { if (i == it) 1.0 else 0.0 } } // Diagonal matrix
    private var processNoise = 0.1  // Start with a moderate value for system dynamics
    private var measurementNoise = 0.01  // Start with a small value for accurate measurements

    fun predict(deltaTime: Double, velocity: Double, heading: Double): DoubleArray {
        val headingRad = Math.toRadians(heading) // Ensure heading is in degrees before calling this
        val vx = velocity * cos(headingRad)
        val vy = velocity * sin(headingRad)

        // Update state with motion model
        state[0] += state[3] * deltaTime // Predict y position
        state[1] += state[2] * deltaTime // Predict x position

        // Update velocity in the state
        state[2] = vx
        state[3] = vy

        // Update covariance with process noise
        for (i in 0..3) {
            covariance[i][i] += processNoise
        }
        return state
    }

    fun update(measurement: DoubleArray) {

        val innovation = doubleArrayOf(
            measurement[0] - state[0], // x innovation
            measurement[1] - state[1]  // y innovation
        )

        // Adjust noise parameters based on innovation
        adjustNoiseParameters(innovation)

        val R = measurementNoise
        val K = DoubleArray(4) { i -> covariance[i][i] / (covariance[i][i] + R) }

        for (i in 0..1) {
            state[i] += K[i] * (measurement[i] - state[i])
        }

        for (i in 0..3) {
            covariance[i][i] *= (1 - K[i])
        }
    }

    private fun adjustNoiseParameters(innovation: DoubleArray) {
        val innovationMagnitude = sqrt(innovation[0] * innovation[0] + innovation[1] * innovation[1])

        // Define thresholds for tuning
        val highInnovationThreshold = 10.0
        val lowInnovationThreshold = 1.0
        val minNoise = 0.001
        val maxNoise = 10.0


        if (innovationMagnitude > highInnovationThreshold) {
            // Increase process noise and decrease measurement noise
            processNoise = minOf(processNoise * 1.1, maxNoise)
            measurementNoise = maxOf(measurementNoise * 0.9, minNoise)
        } else if (innovationMagnitude < lowInnovationThreshold) {
            // Increase measurement noise and decrease process noise
            measurementNoise = minOf(measurementNoise * 1.1, maxNoise)
            processNoise = maxOf(processNoise * 0.9, minNoise)
        }
    }

    fun adjustMeasurementNoise(factor: Double) {
        measurementNoise *= factor
    }

    fun getWeightedAverage(predicted: DoubleArray, measured: DoubleArray): DoubleArray {
        val totalNoise = processNoise + measurementNoise
        val weightPredicted = measurementNoise / totalNoise
        val weightMeasured = processNoise / totalNoise

        return doubleArrayOf(
            (predicted[0] * weightPredicted) + (measured[0] * weightMeasured), // Weighted x
            (predicted[1] * weightPredicted) + (measured[1] * weightMeasured)  // Weighted y
        )
    }

    fun getState(): DoubleArray = state
}
