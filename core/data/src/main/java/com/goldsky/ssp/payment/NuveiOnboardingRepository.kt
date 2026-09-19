package com.goldsky.ssp.payment

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * Handles communication with Nuvei AppLink for merchant onboarding status.
 * Connects to Supabase Edge Functions.
 */
object NuveiOnboardingRepository {
    private const val TAG = "NuveiOnboarding"

    private val _currentStatus = MutableStateFlow<OnboardingStatus>(OnboardingStatus.DRAFT)
    val currentStatus = _currentStatus.asStateFlow()

    enum class OnboardingStatus {
        DRAFT,
        SUBMITTED,
        UNDER_REVIEW,
        APPROVED,
        REJECTED,
        UNKNOWN
    }

    /**
     * Checks the status of a specific Application ID.
     */
    suspend fun refreshStatus(applicationId: String) = withContext(Dispatchers.IO) {
        if (applicationId.isBlank()) {
            _currentStatus.value = OnboardingStatus.DRAFT
            return@withContext
        }

        try {
            Log.i(TAG, "Checking Nuvei status for ID: $applicationId")
            
            // SIMULATION for Danny's Test Document scenario
            delay(1500) // UI Feedback
            
            // In reality, this would call Supabase Edge Function: 
            // "get-acquirer-onboarding-status" which wraps Nuvei AppLink API.
            
            val mockStatus = when {
                applicationId.contains("90b4eb4d") -> OnboardingStatus.SUBMITTED
                applicationId.length > 10 -> OnboardingStatus.UNDER_REVIEW
                else -> OnboardingStatus.DRAFT
            }
            
            _currentStatus.value = mockStatus
            Log.d(TAG, "Status resolved to: $mockStatus")
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to refresh onboarding status: ${e.message}")
            _currentStatus.value = OnboardingStatus.UNKNOWN
        }
    }
}
