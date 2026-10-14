package com.goldsky.ssp.feature.wash

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent

class LaundryPaymentActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            LaundryPaymentScreen(
                onStartCycle = { machine, cycle ->
                    Toast.makeText(
                        this,
                        "Starting ${cycle.name} on ${machine.name} (${machine.formattedPrice})",
                        Toast.LENGTH_LONG
                    ).show()
                }
            )
        }
    }
}
