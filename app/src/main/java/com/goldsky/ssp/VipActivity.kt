package com.goldsky.ssp

import android.os.Bundle
import android.widget.Button
import android.widget.ImageView
import com.goldsky.ssp.payment.QrUtils

class VipActivity : BaseAdActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_vip)

        findViewById<Button>(R.id.btn_back_main).setOnClickListener {
            finish()
        }

        // Generate a mock VIP purchase QR code
        val qrVip = findViewById<ImageView>(R.id.img_vip_qr)
        val vipUrl = "https://gs-ssp.ca/vip-purchase"
        val qrBitmap = QrUtils.generateQrCode(vipUrl, 250, 250)
        if (qrBitmap != null) {
            qrVip.setImageBitmap(qrBitmap)
        }
    }
}
