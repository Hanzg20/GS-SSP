package com.goldsky.ssp.ui

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.widget.VideoView

/**
 * A VideoView that fills its parent like an ImageView's centerCrop: scaled
 * up, aspect kept, overflow cropped by the parent. The stock VideoView fits
 * the video inside, so a 16:9 ad on the 480x480 Q3mini played letterboxed
 * (owner: "ads don't play full screen", 2026-10-07). The parent must clip
 * its children (ConstraintLayout does by default) and centre this view.
 */
class FillVideoView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null,
) : VideoView(context, attrs) {

    private var videoW = 0
    private var videoH = 0

    /** From MediaPlayer.OnPreparedListener (videoWidth / videoHeight). */
    fun setVideoSize(width: Int, height: Int) {
        if (width == videoW && height == videoH) return
        videoW = width
        videoH = height
        requestLayout()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val parentView = parent as? View
        val pw = parentView?.width?.takeIf { it > 0 } ?: View.MeasureSpec.getSize(widthMeasureSpec)
        val ph = parentView?.height?.takeIf { it > 0 } ?: View.MeasureSpec.getSize(heightMeasureSpec)
        if (videoW <= 0 || videoH <= 0 || pw <= 0 || ph <= 0) {
            setMeasuredDimension(pw, ph)
            return
        }
        val scale = maxOf(pw / videoW.toFloat(), ph / videoH.toFloat())
        setMeasuredDimension((videoW * scale).toInt(), (videoH * scale).toInt())
    }
}
