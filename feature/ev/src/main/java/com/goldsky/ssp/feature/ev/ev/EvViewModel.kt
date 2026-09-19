package com.goldsky.ssp.ev

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * MVI-style ViewModel for EV Charging on IM30.
 * Includes a telemetry simulation engine.
 */
class EvViewModel : ViewModel() {

    sealed class EvState {
        object Idle : EvState()
        object CablePlugged : EvState()
        object Authorizing : EvState()
        data class Charging(
            val kwh: Double,
            val powerKw: Double,
            val cost: Double,
            val currentA: Double,
            val voltageV: Double,
            val progress: Float // 0.0 to 1.0
        ) : EvState()
        data class Finishing(val finalKwh: Double, val finalCost: Double) : EvState()
        data class Error(val message: String) : EvState()
    }

    private val _uiState = MutableStateFlow<EvState>(EvState.Idle)
    val uiState: StateFlow<EvState> = _uiState.asStateFlow()

    private var simulationJob: Job? = null

    /**
     * Simulate physical cable connection.
     */
    fun onCablePlugged() {
        if (_uiState.value is EvState.Idle) {
            _uiState.value = EvState.CablePlugged
        }
    }

    /**
     * Start the payment authorization flow.
     */
    fun startAuthorization() {
        if (_uiState.value is EvState.CablePlugged) {
            _uiState.value = EvState.Authorizing
            viewModelScope.launch {
                delay(2000) // Mock POSLink/OCPP Delay
                startCharging()
            }
        }
    }

    private fun startCharging() {
        simulationJob?.cancel()
        simulationJob = viewModelScope.launch {
            var currentKwh = 0.0
            val pricePerKwh = 0.35 // $0.35 per kWh
            val maxCapacity = 75.0 // 75kWh battery simulation
            
            while (currentKwh < maxCapacity) {
                // Simulate Level 2 Charging (Approx 7.2 kW)
                val power = 7.2 + (Math.random() - 0.5) * 0.2
                val energyGain = power / 3600.0 // energy per second
                currentKwh += energyGain
                
                _uiState.value = EvState.Charging(
                    kwh = currentKwh,
                    powerKw = power,
                    cost = currentKwh * pricePerKwh,
                    currentA = 32.0 + (Math.random() - 0.5),
                    voltageV = 238.0 + (Math.random() - 0.5) * 2,
                    progress = (currentKwh / maxCapacity).toFloat()
                )
                delay(1000)
            }
            finishCharging(currentKwh, currentKwh * pricePerKwh)
        }
    }

    fun stopChargingManual() {
        val current = _uiState.value
        if (current is EvState.Charging) {
            simulationJob?.cancel()
            finishCharging(current.kwh, current.cost)
        }
    }

    private fun finishCharging(kwh: Double, cost: Double) {
        _uiState.value = EvState.Finishing(kwh, cost)
        viewModelScope.launch {
            delay(5000)
            _uiState.value = EvState.Idle
        }
    }
}
