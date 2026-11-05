package com.example.face

import android.app.Application

/**
 * Application class. The app uses its own fixed color palette, so it does not apply
 * the phone's wallpaper colors (Material dynamic colors), which would override it.
 */
class FaceApp : Application()
