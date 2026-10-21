package com.goldsky.ssp.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.animation.AlphaAnimation
import android.widget.ImageView
import androidx.appcompat.app.AppCompatActivity
import com.goldsky.ssp.core.ui.R

/**
 * Splash Screen Activity. 
 * Displays the high-resolution GoldSky logo for 2 seconds before 
 * navigating to the main entry point defined in the manifest.
 */
@SuppressLint("CustomSplashScreen")
class SplashActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_splash)

        val logo = findViewById<ImageView>(R.id.img_splash_logo)
        
        // Simple Fade-in animation
        val fadeIn = AlphaAnimation(0f, 1f).apply {
            duration = 1500
        }
        logo.startAnimation(fadeIn)

        Handler(Looper.getMainLooper()).postDelayed({
            // Launch the actual main activity. 
            // In a real multi-module setup, we rely on the manifest intent filters.
            // For GS-SSP, we typically launch the vertical's MainActivity.
            val intent = packageManager.getLaunchIntentForPackage(packageName)
            // Remove current category/action to avoid infinite loop since Splash 
            // will now be the LAUNCHER.
            intent?.setComponent(null)
            intent?.setPackage(packageName)
            
            // We need to find the specific MainActivity that was previously the launcher.
            // Since we're changing the launcher to SplashActivity, we need to know 
            // where to go. A common pattern is to use a specific Action.
            // The app's MainActivity named in the manifest (meta-data com.goldsky.ssp.MAIN_ACTIVITY on
            // this activity): opened by class, so it needs no intent filter of its own -- WizarPOS
            // asked us to drop com.goldsky.ssp.action.MAIN from Aegis Wash (2026-10-20). The action
            // stays the fallback for apps that still declare it.
            val mainClass = runCatching {
                packageManager.getActivityInfo(componentName, android.content.pm.PackageManager.GET_META_DATA)
                    .metaData?.getString("com.goldsky.ssp.MAIN_ACTIVITY")
            }.getOrNull()
            val mainIntent = if (mainClass != null) Intent().setClassName(packageName, mainClass)
            else Intent("com.goldsky.ssp.action.MAIN").setPackage(packageName)
            // AppSwitcher's EXTRA_CLAIM_HOME has to reach MainActivity.
            getIntent().extras?.let { mainIntent.putExtras(it) }
            
            if (mainIntent.resolveActivity(packageManager) != null) {
                startActivity(mainIntent)
            } else {
                // Fallback to searching for MainActivity in the package
                try {
                    val mainActivityClass = Class.forName("${packageName}.MainActivity")
                    startActivity(Intent(this, mainActivityClass))
                } catch (e: Exception) {
                    // Final fallback to launch intent (might loop if not careful)
                    startActivity(intent)
                }
            }
            finish()
        }, 2500)
    }
}
