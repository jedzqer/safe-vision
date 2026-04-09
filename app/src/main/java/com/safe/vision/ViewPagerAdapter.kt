package com.safe.vision

import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.viewpager2.adapter.FragmentStateAdapter

class ViewPagerAdapter(activity: FragmentActivity) : FragmentStateAdapter(activity) {
    companion object {
        const val IMAGE_PROCESSING_POSITION = 0
        const val IMAGE_GALLERY_POSITION = 1
        const val IMAGE_VIEWER_POSITION = 2
        const val SETTINGS_POSITION = 3
        const val PAGE_COUNT = 4
    }

    override fun getItemCount(): Int = PAGE_COUNT

    override fun createFragment(position: Int): Fragment {
        return when (position) {
            IMAGE_PROCESSING_POSITION -> ImageProcessingFragment()
            IMAGE_GALLERY_POSITION -> ImageGalleryFragment()
            IMAGE_VIEWER_POSITION -> ImageViewerFragment()
            SETTINGS_POSITION -> SettingsFragment()
            else -> throw IllegalArgumentException("Invalid position: $position")
        }
    }
}
