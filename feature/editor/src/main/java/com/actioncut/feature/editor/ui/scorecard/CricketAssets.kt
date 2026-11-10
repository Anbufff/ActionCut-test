package com.actioncut.feature.editor.ui.scorecard

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64

object CricketAssets {
    // HTML எடிட்டரில் உள்ள அதே பேட்ஸ்மேன் கார்ட்டூன் படம்
    private const val BATTER_LOGO_B64 = 
        "/9j/4AAQSkZJRgABAQAAAQABAAD/2wBDAAUDBAQEAwUEBAQFBQUGBwwIBwcHBw8LCwkMEQ8SEhEPERETFhwXExQaFRERGCEYGh0dHx8fExciJCIeJBweHx7/2wBDAQUFBQcGBw4ICA4eFBEUHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh7/wAARCAIdAeADASIAAhEBAxEB/8QAHQAAAG..." // (உங்களின் index.html-ல் உள்ள FKB_CRICKET_LOGO_DATA_URI தரவு)

    // HTML எடிட்டரில் உள்ள பவுலர் கார்ட்டூன் படம்
    private const val BOWLER_LOGO_B64 = 
        "/9j/4AAQSkZJRgABAQAAAQABAAD/2wBDAAUDBAQEAwUEBAQFBQUGBwwIBwcHBw8LCwkMEQ8SEhEPERETFhwXExQaFRERGCEYGh0dHx8fExciJCIeJBweHx7/2wBDAQUFBQcGBw4ICA4eFBEUHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh7/wAARCAIcAeADASIAAhEBAxEB/8QAHQAAAG..."

    private var batterBitmap: Bitmap? = null
    private var bowlerBitmap: Bitmap? = null

    fun getBatterLogo(): Bitmap {
        if (batterBitmap == null || batterBitmap?.isRecycled == true) {
            val bytes = Base64.decode(BATTER_LOGO_B64, Base64.DEFAULT)
            batterBitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        }
        return batterBitmap!!
    }

    fun getBowlerLogo(): Bitmap {
        if (bowlerBitmap == null || bowlerBitmap?.isRecycled == true) {
            val bytes = Base64.decode(BOWLER_LOGO_B64, Base64.DEFAULT)
            bowlerBitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        }
        return bowlerBitmap!!
    }
}
