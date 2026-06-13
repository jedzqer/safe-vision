package com.safe.vision

import android.os.Build
import android.os.Bundle

internal inline fun <reified T : android.os.Parcelable> Bundle.parcelableArrayListCompat(key: String): ArrayList<T>? {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        getParcelableArrayList(key, T::class.java)
    } else {
        @Suppress("DEPRECATION")
        getParcelableArrayList(key)
    }
}
