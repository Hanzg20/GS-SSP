package com.goldsky.ssp.ui

import android.content.Context
import android.util.AttributeSet
import android.util.Log
import android.widget.VideoView

/**
 * A VideoView that fills its parent like an ImageView's centerCrop: scaled
 * up, aspect kept, overflow cropped. The stock VideoView fits the video
 * inside, so ads on the 480x480 Q3mini played with black bars (owner: "ads
 * don't play full screen", 2026-10-07). Put it in a FrameLayout with
 * layout_gravity="center" -- that centres an oversized child and clips it.
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
        Log.i("FillVideoView", "Video ${width}x$height -> cover")
        requestLayout()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // The space the parent offers (the full ad screen), not our own size.
        val pw = MeasureSpec.getSize(widthMeasureSpec)
        val ph = MeasureSpec.getSize(heightMeasureSpec)
        if (videoW <= 0 || videoH <= 0 || pw <= 0 || ph <= 0) {
            setMeasuredDimension(pw, ph)
            return
        }
        val scale = maxOf(pw / videoW.toFloat(), ph / videoH.toFloat())
        setMeasuredDimension(Math.round(videoW * scale), Math.round(videoH * scale))
    }
}
