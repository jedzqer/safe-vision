package com.safe.vision

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.InputType
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView as AndroidScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.AttrRes
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.widget.NestedScrollView
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.children
import androidx.fragment.app.Fragment
import androidx.interpolator.view.animation.FastOutSlowInInterpolator
import com.google.android.material.card.MaterialCardView
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import org.json.JSONObject

class SettingsFragment : Fragment() {

    private lateinit var debugToggle: Button
    private lateinit var shareLogsButton: Button
    private lateinit var joinGroupButton: Button
    private lateinit var languageButton: Button
    private lateinit var supportButton: Button
    
    // 遮挡设置相关UI
    private lateinit var blurModeGroup: RadioGroup
    private lateinit var blurModeMosaic: RadioButton
    private lateinit var blurModeBlack: RadioButton
    private lateinit var blurModeGaussian: RadioButton
    private lateinit var blurModeSobel: RadioButton
    private lateinit var blurModeSticker: RadioButton
    private lateinit var privacyPresetSummary: TextView
    private lateinit var switchPresetButton: Button
    private lateinit var savePresetButton: Button
    private lateinit var sharePresetButton: Button
    private lateinit var importPresetButton: Button
    private lateinit var deletePresetButton: Button
    private lateinit var circularMaskSwitch: android.widget.Switch
    private lateinit var maskOutlineSwitch: android.widget.Switch
    private lateinit var reversePreRenderSwitch: android.widget.Switch
    private lateinit var maskOutlineSummary: TextView
    private lateinit var maskOutlineAdjustButton: Button
    private lateinit var mosaicIntensitySummary: TextView
    private lateinit var mosaicIntensitySeekBar: SeekBar
    private lateinit var gaussianIntensitySummary: TextView
    private lateinit var gaussianIntensitySeekBar: SeekBar
    private lateinit var maskScaleSummary: TextView
    private lateinit var maskScaleSeekBar: SeekBar
    private lateinit var themeSummary: TextView
    private lateinit var openThemeDialogButton: Button
    private lateinit var settingsScrollView: NestedScrollView
    private lateinit var labelChipGroup: ChipGroup
    private lateinit var animeLabelChipGroup: ChipGroup
    private lateinit var labelSettingsInlineCard: MaterialCardView
    private lateinit var labelSettingsInlineContainer: LinearLayout
    private lateinit var stickerSummary: TextView
    private lateinit var chooseStickerButton: Button
    private lateinit var resetStickerButton: Button
    
    private lateinit var uploadViaFileSystemSwitch: android.widget.Switch
    private lateinit var videoSpeedButton: Button
    private lateinit var videoSpeedSummary: TextView
    private lateinit var randomBrowseSwitch: android.widget.Switch
    private lateinit var randomQueueButton: Button
    private lateinit var randomQueueSummary: TextView
    private lateinit var randomPlaySwitch: android.widget.Switch
    private lateinit var randomPlayIntervalButton: Button
    private lateinit var randomPlaySummary: TextView
    private lateinit var metronomeSwitch: android.widget.Switch
    private lateinit var metronomeSpeedButton: Button
    private lateinit var metronomeSummary: TextView
    
    private lateinit var privacySettings: PrivacySettingsManager
    private lateinit var appSettings: AppSettingsManager
    private var currentTheme: AppTheme = AppTheme.DEFAULT
    private var expandedLabel: String? = null
    private var expandedLabelCard: View? = null
    private var activeLabelEditor: LabelSettingsEditor? = null
    private var isCollapsingLabelCard = false
    private var pendingStickerLabel: String? = null
    private val videoSpeedOptions = listOf(
        VideoSpeedOption(1, R.string.settings_video_speed_best),
        VideoSpeedOption(3, R.string.settings_video_speed_high),
        VideoSpeedOption(5, R.string.settings_video_speed_low),
        VideoSpeedOption(7, R.string.settings_video_speed_lowest)
    )

    private fun resolveThemeColor(@AttrRes attr: Int): Int {
        val typedValue = TypedValue()
        val resolved = requireContext().theme.resolveAttribute(attr, typedValue, true)
        if (!resolved) return 0
        return if (typedValue.type in TypedValue.TYPE_FIRST_COLOR_INT..TypedValue.TYPE_LAST_COLOR_INT) {
            typedValue.data
        } else {
            ContextCompat.getColor(requireContext(), typedValue.resourceId)
        }
    }

    private fun colorStateList(@AttrRes attr: Int): ColorStateList {
        return ColorStateList.valueOf(resolveThemeColor(attr))
    }

    private fun switchTrackTintList(): ColorStateList {
        return ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
            intArrayOf(
                resolveThemeColor(R.attr.svColorAccent),
                resolveThemeColor(R.attr.svColorBorder)
            )
        )
    }

    private fun applySwitchTint(switch: SwitchCompat) {
        switch.thumbTintList = colorStateList(R.attr.svColorPrimary)
        switch.trackTintList = switchTrackTintList()
    }

    private val pickStickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            handleStickerSelected(uri)
        } else {
            pendingStickerLabel = null
        }
    }

    private val importPresetLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            importPresetPackage(uri)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_settings, container, false)
    }
    
    private fun refreshLabelChips() {
        refreshLabelChipGroup(labelChipGroup, DetectionConfig.getLabels(DetectionConfig.LabelProfile.STANDARD))
        refreshLabelChipGroup(animeLabelChipGroup, DetectionConfig.getLabels(DetectionConfig.LabelProfile.ANIME))
    }

    private fun refreshLabelChipGroup(group: ChipGroup, labels: List<String>) {
        group.removeAllViews()
        labels.forEach { label ->
            if (expandedLabel == label) {
                group.addView(createExpandedLabelCard(label))
            } else {
                group.addView(createLabelChip(label))
            }
        }
    }

    private fun createLabelChip(label: String): Chip {
        val chip = Chip(requireContext())
        chip.text = buildLabelChipText(label)
        chip.isCheckable = false
        chip.isClickable = true
        chip.isFocusable = true
        chip.chipBackgroundColor = colorStateList(R.attr.svColorCard)
        chip.chipStrokeColor = colorStateList(
            if (expandedLabel == label) R.attr.svColorAccent else R.attr.svColorBorder
        )
        chip.chipStrokeWidth = resources.displayMetrics.density
        chip.setTextColor(resolveThemeColor(R.attr.svColorTextPrimary))
        chip.alpha = if (privacySettings.isLabelBlocked(label)) 1f else 0.5f
        chip.setOnClickListener { toggleInlineLabelSettings(label) }
        return chip
    }

    private fun createExpandedLabelCard(label: String): View {
        val context = requireContext()
        val editor = createLabelSettingsEditor(label, includeHeader = true)
        activeLabelEditor = editor
        val contentHost = FrameLayout(context).apply {
            clipChildren = true
            clipToPadding = true
            addView(
                editor.root,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT
                )
            )
        }
        return MaterialCardView(context).apply {
            expandedLabelCard = this
            tag = label
            alpha = 0f
            scaleX = 0.88f
            scaleY = 0.88f
            radius = 22f * resources.displayMetrics.density
            cardElevation = 6f * resources.displayMetrics.density
            setCardBackgroundColor(resolveThemeColor(R.attr.svColorCard))
            strokeColor = resolveThemeColor(R.attr.svColorAccent)
            strokeWidth = resources.displayMetrics.density.toInt()
            layoutParams = ViewGroup.MarginLayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = (8 * resources.displayMetrics.density).toInt()
            }
            addView(contentHost)
            post {
                animate()
                    .alpha(1f)
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(220L)
                    .start()
            }
        }
    }

    private fun buildLabelChipText(label: String): String {
        val displayName = privacySettings.getLabelDisplayName(label)
        if (!privacySettings.isLabelBlocked(label)) {
            return getString(R.string.settings_label_chip_disabled, displayName)
        }
        val override = privacySettings.getLabelEffectOverride(label)
        return if (override == null) {
            val profile = if (DetectionConfig.ANIME_LABELS.contains(label)) {
                DetectionConfig.LabelProfile.ANIME
            } else {
                DetectionConfig.LabelProfile.STANDARD
            }
            val defaultName = privacySettings.getBlurModeName(privacySettings.getBlurMode(profile))
            getString(R.string.settings_label_chip_default, displayName, defaultName)
        } else {
            getString(
                R.string.settings_label_chip_custom,
                displayName,
                privacySettings.getBlurModeName(override)
            )
        }
    }

    private data class LabelSettingsEditor(
        val root: View,
        val applyChanges: (Boolean) -> Unit
    )

    private fun createLabelSettingsEditor(label: String, includeHeader: Boolean): LabelSettingsEditor {
        val context = requireContext()
        val padding = (16 * resources.displayMetrics.density).toInt()
        val smallMargin = (8 * resources.displayMetrics.density).toInt()
        val primaryTextColor = resolveThemeColor(R.attr.svColorTextPrimary)
        val secondaryTextColor = resolveThemeColor(R.attr.svColorTextSecondary)
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
        }

        if (includeHeader) {
            container.addView(TextView(context).apply {
                text = getString(R.string.settings_label_dialog_title, privacySettings.getLabelDisplayName(label))
                setTextColor(primaryTextColor)
                textSize = 18f
            })
            container.addView(TextView(context).apply {
                text = getString(R.string.settings_label_dialog_hint_tap_outside)
                setTextColor(secondaryTextColor)
                textSize = 13f
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = smallMargin / 2
                    bottomMargin = smallMargin
                }
            })
        }

        val isDisableLocked = privacySettings.isLabelDisableLocked(label)
        val isReverseLocked = privacySettings.isLabelReverseLocked(label)
        val enabledSwitch = SwitchCompat(context).apply {
            text = getString(R.string.settings_label_enabled)
            setTextColor(primaryTextColor)
            isChecked = privacySettings.isLabelBlocked(label)
            isEnabled = !isDisableLocked
            applySwitchTint(this)
        }

        val reverseSwitch = SwitchCompat(context).apply {
            text = getString(R.string.settings_label_reverse)
            setTextColor(primaryTextColor)
            isChecked = privacySettings.isLabelReverse(label)
            isEnabled = !isReverseLocked
            applySwitchTint(this)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = smallMargin
            }
        }

        val effectTitle = TextView(context).apply {
            text = getString(R.string.settings_label_effect_title)
            setTextColor(primaryTextColor)
            textSize = 14f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = smallMargin
            }
        }

        val effectGroup = RadioGroup(context).apply {
            orientation = RadioGroup.VERTICAL
        }

        val defaultOption = RadioButton(context).apply {
            id = View.generateViewId()
            text = getString(
                R.string.settings_label_effect_default,
                privacySettings.getBlurModeName(
                    privacySettings.getBlurMode(
                        if (DetectionConfig.ANIME_LABELS.contains(label)) {
                            DetectionConfig.LabelProfile.ANIME
                        } else {
                            DetectionConfig.LabelProfile.STANDARD
                        }
                    )
                )
            )
            setTextColor(primaryTextColor)
        }
        val mosaicOption = RadioButton(context).apply {
            id = View.generateViewId()
            text = getString(R.string.settings_blur_mode_mosaic)
            setTextColor(primaryTextColor)
        }
        val blackOption = RadioButton(context).apply {
            id = View.generateViewId()
            text = getString(R.string.settings_blur_mode_black)
            setTextColor(primaryTextColor)
        }
        val gaussianOption = RadioButton(context).apply {
            id = View.generateViewId()
            text = getString(R.string.settings_blur_mode_gaussian)
            setTextColor(primaryTextColor)
        }
        val sobelOption = RadioButton(context).apply {
            id = View.generateViewId()
            text = getString(R.string.settings_blur_mode_sobel)
            setTextColor(primaryTextColor)
        }
        val stickerOption = RadioButton(context).apply {
            id = View.generateViewId()
            text = getString(R.string.settings_blur_mode_sticker)
            setTextColor(primaryTextColor)
        }
        val stickerSummaryView = TextView(context).apply {
            text = buildLabelStickerSummary(label)
            setTextColor(secondaryTextColor)
            textSize = 13f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = smallMargin
            }
        }
        val stickerActionLayout = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = smallMargin }
        }
        val chooseLabelStickerButton = Button(context).apply {
            text = getString(R.string.settings_label_sticker_pick)
            setOnClickListener {
                pendingStickerLabel = label
                pickStickerLauncher.launch(arrayOf("image/*"))
            }
        }
        val resetLabelStickerButton = Button(context).apply {
            text = getString(R.string.settings_label_sticker_reset)
            setOnClickListener {
                privacySettings.setLabelStickerUri(label, null)
                StickerLoader.clearCache()
                stickerSummaryView.text = buildLabelStickerSummary(label)
                updateStickerSummary()
                refreshLabelChips()
                Toast.makeText(
                    context,
                    getString(
                        R.string.settings_label_sticker_cleared,
                        privacySettings.getLabelDisplayName(label)
                    ),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
        stickerActionLayout.addView(
            chooseLabelStickerButton,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        )
        stickerActionLayout.addView(
            resetLabelStickerButton,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = smallMargin }
        )

        effectGroup.addView(defaultOption)
        effectGroup.addView(mosaicOption)
        effectGroup.addView(blackOption)
        effectGroup.addView(gaussianOption)
        effectGroup.addView(sobelOption)
        effectGroup.addView(stickerOption)
        effectGroup.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            topMargin = smallMargin
        }

        when (privacySettings.getLabelEffectOverride(label)) {
            null -> effectGroup.check(defaultOption.id)
            PrivacySettingsManager.BLUR_MODE_MOSAIC -> effectGroup.check(mosaicOption.id)
            PrivacySettingsManager.BLUR_MODE_BLACK -> effectGroup.check(blackOption.id)
            PrivacySettingsManager.BLUR_MODE_GAUSSIAN -> effectGroup.check(gaussianOption.id)
            PrivacySettingsManager.BLUR_MODE_SOBEL -> effectGroup.check(sobelOption.id)
            PrivacySettingsManager.BLUR_MODE_STICKER -> effectGroup.check(stickerOption.id)
        }
        if (effectGroup.checkedRadioButtonId == View.NO_ID) {
            effectGroup.check(defaultOption.id)
        }

        reverseSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked && !enabledSwitch.isChecked) {
                enabledSwitch.isChecked = true
            }
        }
        enabledSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (!isChecked && reverseSwitch.isChecked) {
                reverseSwitch.isChecked = false
            }
        }

        container.addView(enabledSwitch)
        if (!isReverseLocked) {
            container.addView(reverseSwitch)
        }
        container.addView(effectTitle)
        container.addView(effectGroup)

        val labelScaleTitle = TextView(context).apply {
            text = getString(R.string.settings_label_scale_title)
            setTextColor(primaryTextColor)
            textSize = 14f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = smallMargin
            }
        }
        val labelProfile = if (DetectionConfig.ANIME_LABELS.contains(label)) {
            DetectionConfig.LabelProfile.ANIME
        } else {
            DetectionConfig.LabelProfile.STANDARD
        }
        val globalScale = if (labelProfile == DetectionConfig.LabelProfile.ANIME) {
            privacySettings.getAnimeMaskScale()
        } else {
            privacySettings.getMaskScale()
        }
        val labelScaleOverride = privacySettings.getLabelMaskScaleOverride(label)
        val followGlobalCheck = CheckBox(context).apply {
            text = getString(R.string.settings_label_scale_follow_global, globalScale)
            setTextColor(primaryTextColor)
            isChecked = labelScaleOverride == null
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = smallMargin / 2
            }
        }
        val labelScaleSummary = TextView(context).apply {
            setTextColor(secondaryTextColor)
            textSize = 13f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = smallMargin / 2
            }
        }
        val labelScaleSeekBar = SeekBar(context).apply {
            val minProgress = (PrivacySettingsManager.MASK_SCALE_MIN * 10).toInt()
            val maxProgress = (PrivacySettingsManager.MASK_SCALE_MAX * 10).toInt()
            max = maxProgress - minProgress
            val initialScale = labelScaleOverride ?: globalScale
            progress = ((initialScale * 10).toInt() - minProgress).coerceIn(0, max)
            isEnabled = !followGlobalCheck.isChecked
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        fun updateLabelScaleSummary() {
            val minProgress = (PrivacySettingsManager.MASK_SCALE_MIN * 10).toInt()
            val scale = if (followGlobalCheck.isChecked) {
                globalScale
            } else {
                ((labelScaleSeekBar.progress + minProgress) / 10f)
            }
            labelScaleSummary.text = getString(R.string.settings_label_scale_effective_summary, scale)
        }
        updateLabelScaleSummary()
        labelScaleSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                updateLabelScaleSummary()
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar) = Unit
        })
        followGlobalCheck.setOnCheckedChangeListener { _, isChecked ->
            labelScaleSeekBar.isEnabled = !isChecked
            updateLabelScaleSummary()
        }
        container.addView(labelScaleTitle)
        container.addView(followGlobalCheck)
        container.addView(labelScaleSummary)
        container.addView(labelScaleSeekBar)

        container.addView(stickerSummaryView)
        container.addView(stickerActionLayout)

        return LabelSettingsEditor(
            root = container,
            applyChanges = { refreshChips ->
                privacySettings.setLabelEnabled(label, enabledSwitch.isChecked)
                privacySettings.setLabelReverse(label, reverseSwitch.isChecked && enabledSwitch.isChecked)
                val selectedMode = when (effectGroup.checkedRadioButtonId) {
                    mosaicOption.id -> PrivacySettingsManager.BLUR_MODE_MOSAIC
                    blackOption.id -> PrivacySettingsManager.BLUR_MODE_BLACK
                    gaussianOption.id -> PrivacySettingsManager.BLUR_MODE_GAUSSIAN
                    sobelOption.id -> PrivacySettingsManager.BLUR_MODE_SOBEL
                    stickerOption.id -> PrivacySettingsManager.BLUR_MODE_STICKER
                    else -> null
                }
                privacySettings.setLabelEffectOverride(label, selectedMode)
                val minProgress = (PrivacySettingsManager.MASK_SCALE_MIN * 10).toInt()
                val customScale = ((labelScaleSeekBar.progress + minProgress) / 10f)
                privacySettings.setLabelMaskScaleOverride(
                    label,
                    if (followGlobalCheck.isChecked) null else customScale
                )
                if (refreshChips) {
                    refreshLabelChips()
                }
            }
        )
    }

    private fun measureCollapsedChipSize(label: String): Pair<Float, Float> {
        val group = if (DetectionConfig.ANIME_LABELS.contains(label)) {
            animeLabelChipGroup
        } else {
            labelChipGroup
        }
        val chip = createLabelChip(label)
        val widthSpec = View.MeasureSpec.makeMeasureSpec(group.width.coerceAtLeast(1), View.MeasureSpec.AT_MOST)
        val heightSpec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        chip.measure(widthSpec, heightSpec)
        return chip.measuredWidth.toFloat() to chip.measuredHeight.toFloat()
    }

    private fun toggleInlineLabelSettings(label: String) {
        if (isCollapsingLabelCard) return
        if (expandedLabel == label) {
            collapseInlineLabelSettings()
            return
        }
        showInlineLabelSettings(label)
    }

    private fun showInlineLabelSettings(label: String) {
        if (isCollapsingLabelCard) return
        activeLabelEditor?.applyChanges?.invoke(true)
        expandedLabel = label
        expandedLabelCard = null
        refreshLabelChips()
    }

    private fun collapseInlineLabelSettings() {
        if (isCollapsingLabelCard) return
        val card = expandedLabelCard
        if (card == null) {
            activeLabelEditor?.applyChanges?.invoke(true)
            activeLabelEditor = null
            expandedLabel = null
            refreshLabelChips()
            return
        }
        isCollapsingLabelCard = true
        val label = expandedLabel
        if (label != null) {
            activeLabelEditor?.applyChanges?.invoke(false)
            val (targetWidth, targetHeight) = measureCollapsedChipSize(label)
            val contentHost = (card as? ViewGroup)?.getChildAt(0) as? ViewGroup
            val content = contentHost?.getChildAt(0)
            val startWidth = card.width.coerceAtLeast(1)
            val startHeight = card.height.coerceAtLeast(1)
            val marginParams = card.layoutParams as? ViewGroup.MarginLayoutParams
            val startBottomMargin = marginParams?.bottomMargin ?: 0
            val targetBottomMargin = 0
            val materialCard = card as? MaterialCardView
            val startRadius = materialCard?.radius ?: 0f
            val targetRadius = targetHeight / 2f

            card.pivotX = if (card.layoutDirection == View.LAYOUT_DIRECTION_RTL) card.width.toFloat() else 0f
            card.pivotY = 0f
            card.layoutParams = (marginParams ?: ViewGroup.MarginLayoutParams(card.layoutParams)).apply {
                width = startWidth
                height = startHeight
            }
            card.requestLayout()

            val shrinkCard: () -> Unit = {
                ValueAnimator.ofFloat(0f, 1f).apply {
                    duration = 320L
                    interpolator = FastOutSlowInInterpolator()
                    addUpdateListener { animator ->
                        val progress = animator.animatedFraction
                        val layoutParams = card.layoutParams as ViewGroup.MarginLayoutParams
                        layoutParams.width = lerpInt(startWidth, targetWidth.toInt(), progress)
                        layoutParams.height = lerpInt(startHeight, targetHeight.toInt(), progress)
                        layoutParams.bottomMargin = lerpInt(startBottomMargin, targetBottomMargin, progress)
                        card.layoutParams = layoutParams
                        materialCard?.radius = lerpFloat(startRadius, targetRadius, progress)
                        card.alpha = 1f - (0.05f * progress)
                        val contentFadeProgress = ((progress - 0.08f) / 0.26f).coerceIn(0f, 1f)
                        content?.alpha = 1f - contentFadeProgress
                    }
                    addListener(object : AnimatorListenerAdapter() {
                        override fun onAnimationEnd(animation: Animator) {
                            activeLabelEditor = null
                            expandedLabel = null
                            expandedLabelCard = null
                            isCollapsingLabelCard = false
                            refreshLabelChips()
                        }
                    })
                    start()
                }
                Unit
            }

            if (content != null) {
                val animatedViews = buildList {
                    add(content)
                    if (content is ViewGroup) addAll(content.children.toList())
                }
                animatedViews.forEach { child -> child.animate().cancel() }
                shrinkCard()
            } else {
                shrinkCard()
            }
            return
        }
        card.animate()
            .alpha(0f)
            .scaleX(0.88f)
            .scaleY(0.88f)
            .setDuration(180L)
            .withEndAction {
                activeLabelEditor?.applyChanges?.invoke(true)
                activeLabelEditor = null
                expandedLabel = null
                expandedLabelCard = null
                isCollapsingLabelCard = false
                refreshLabelChips()
            }
            .start()
    }

    private fun lerpInt(start: Int, end: Int, progress: Float): Int {
        return (start + (end - start) * progress).toInt()
    }

    private fun lerpFloat(start: Float, end: Float, progress: Float): Float {
        return start + (end - start) * progress
    }

    private fun handleStickerSelected(uri: Uri) {
        try {
            requireContext().contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (e: SecurityException) {
            DebugLogManager.addLog("设置", "持久化贴纸权限失败: ${e.message}")
        }

        val options = BitmapFactory.Options().apply { inPreferredConfig = android.graphics.Bitmap.Config.ARGB_8888 }
        val bitmap = try {
            requireContext().contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, options)
            }
        } catch (e: Exception) {
            DebugLogManager.addLog("设置", "贴纸解码失败: ${e.message}")
            null
        }

        if (bitmap == null) {
            Toast.makeText(requireContext(), R.string.settings_sticker_select_failed, Toast.LENGTH_SHORT).show()
            return
        }

        val label = pendingStickerLabel
        if (label.isNullOrBlank()) {
            privacySettings.setStickerUri(uri.toString())
        } else {
            privacySettings.setLabelStickerUri(label, uri.toString())
        }
        StickerLoader.clearCache()
        pendingStickerLabel = null
        updateStickerSummary()
        bitmap.recycle()
        val name = resolveStickerName(uri)
        if (label.isNullOrBlank()) {
            Toast.makeText(requireContext(), getString(R.string.settings_sticker_saved, name), Toast.LENGTH_SHORT).show()
            DebugLogManager.addLog("设置", "已选择全局贴纸: $name")
        } else {
            val labelName = privacySettings.getLabelDisplayName(label)
            Toast.makeText(
                requireContext(),
                getString(R.string.settings_label_sticker_saved, labelName, name),
                Toast.LENGTH_SHORT
            ).show()
            DebugLogManager.addLog("设置", "已为${labelName}设置贴纸: $name")
        }
        refreshLabelChips()
    }

    private fun resolveStickerName(uri: Uri): String {
        return try {
            requireContext().contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else uri.lastPathSegment
                }
                ?: uri.lastPathSegment
                ?: "自定义贴纸"
        } catch (e: Exception) {
            DebugLogManager.addLog("设置", "读取贴纸名称失败: ${e.message}")
            uri.lastPathSegment ?: "自定义贴纸"
        }
    }

    private fun updateStickerSummary() {
        val uri = privacySettings.getStickerUri()
        stickerSummary.text = if (uri.isNullOrBlank()) {
            getString(R.string.settings_sticker_summary_default)
        } else {
            getString(R.string.settings_sticker_summary, resolveStickerName(Uri.parse(uri)))
        }
    }

    private fun buildLabelStickerSummary(label: String): String {
        val labelUri = privacySettings.getLabelStickerUri(label)
        return if (labelUri.isNullOrBlank()) {
            "该标签贴纸：跟随全局"
        } else {
            "该标签贴纸：${resolveStickerName(Uri.parse(labelUri))}"
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // 初始化管理器
        privacySettings = PrivacySettingsManager.getInstance(requireContext())
        appSettings = AppSettingsManager.getInstance(requireContext())
        privacySettings.migrateLegacyEyeModeLabelsToEyeRegion(DetectionConfig.LabelProfile.STANDARD)
        privacySettings.migrateLegacyEyeModeLabelsToEyeRegion(DetectionConfig.LabelProfile.ANIME)
        
        // 调试相关UI
        debugToggle = view.findViewById(R.id.debugToggle)
        shareLogsButton = view.findViewById(R.id.shareLogsButton)
        joinGroupButton = view.findViewById(R.id.joinGroupButton)
        
        // 主题与遮挡设置相关UI
        settingsScrollView = view.findViewById(R.id.settingsScrollView)
        themeSummary = view.findViewById(R.id.themeSummary)
        openThemeDialogButton = view.findViewById(R.id.openThemeDialogButton)
        blurModeGroup = view.findViewById(R.id.blurModeGroup)
        blurModeMosaic = view.findViewById(R.id.blurModeMosaic)
        blurModeBlack = view.findViewById(R.id.blurModeBlack)
        blurModeGaussian = view.findViewById(R.id.blurModeGaussian)
        blurModeSobel = view.findViewById(R.id.blurModeSobel)
        blurModeSticker = view.findViewById(R.id.blurModeSticker)
        privacyPresetSummary = view.findViewById(R.id.privacyPresetSummary)
        switchPresetButton = view.findViewById(R.id.switchPresetButton)
        savePresetButton = view.findViewById(R.id.savePresetButton)
        sharePresetButton = view.findViewById(R.id.sharePresetButton)
        importPresetButton = view.findViewById(R.id.importPresetButton)
        deletePresetButton = view.findViewById(R.id.deletePresetButton)
        circularMaskSwitch = view.findViewById(R.id.circularMaskSwitch)
        maskOutlineSummary = view.findViewById(R.id.maskOutlineSummary)
        maskOutlineSwitch = view.findViewById(R.id.maskOutlineSwitch)
        reversePreRenderSwitch = view.findViewById(R.id.reversePreRenderSwitch)
        maskOutlineAdjustButton = view.findViewById(R.id.maskOutlineAdjustButton)
        mosaicIntensitySummary = view.findViewById(R.id.mosaicIntensitySummary)
        mosaicIntensitySeekBar = view.findViewById(R.id.mosaicIntensitySeekBar)
        gaussianIntensitySummary = view.findViewById(R.id.gaussianIntensitySummary)
        gaussianIntensitySeekBar = view.findViewById(R.id.gaussianIntensitySeekBar)
        maskScaleSummary = view.findViewById(R.id.maskScaleSummary)
        maskScaleSeekBar = view.findViewById(R.id.maskScaleSeekBar)
        labelChipGroup = view.findViewById(R.id.labelChipGroup)
        animeLabelChipGroup = view.findViewById(R.id.animeLabelChipGroup)
        labelSettingsInlineCard = view.findViewById(R.id.labelSettingsInlineCard)
        labelSettingsInlineContainer = view.findViewById(R.id.labelSettingsInlineContainer)
        stickerSummary = view.findViewById(R.id.stickerSummary)
        chooseStickerButton = view.findViewById(R.id.chooseStickerButton)
        resetStickerButton = view.findViewById(R.id.resetStickerButton)
        supportButton = view.findViewById(R.id.supportButton)
        languageButton = view.findViewById(R.id.languageButton)

        uploadViaFileSystemSwitch = view.findViewById(R.id.uploadViaFileSystemSwitch)
        videoSpeedButton = view.findViewById(R.id.videoSpeedButton)
        videoSpeedSummary = view.findViewById(R.id.videoSpeedSummary)
        randomBrowseSwitch = view.findViewById(R.id.randomBrowseSwitch)
        randomQueueButton = view.findViewById(R.id.randomQueueButton)
        randomQueueSummary = view.findViewById(R.id.randomQueueSummary)
        randomPlaySwitch = view.findViewById(R.id.randomPlaySwitch)
        randomPlayIntervalButton = view.findViewById(R.id.randomPlayIntervalButton)
        randomPlaySummary = view.findViewById(R.id.randomPlaySummary)
        metronomeSwitch = view.findViewById(R.id.metronomeSwitch)
        metronomeSpeedButton = view.findViewById(R.id.metronomeSpeedButton)
        metronomeSummary = view.findViewById(R.id.metronomeSummary)

        setupThemeSettings()
        setupPrivacySettings()
        setupUploadSettings()
        setupVideoSpeedSettings()
        setupRandomPlaySettings()
        setupMetronomeSettings()
        setupDebugFeatures()
        setupCommunitySection()
        setupLanguageSection()
        setupSupportSection()
        setupInlineLabelCardDismiss()
        
        // 初始化调试日志
        DebugLogManager.addLog("设置", "设置页面已初始化")
    }

    private fun setupInlineLabelCardDismiss() {
        settingsScrollView.setOnTouchListener { _, event ->
            if (event.action != android.view.MotionEvent.ACTION_DOWN) return@setOnTouchListener false
            val activeCard = expandedLabelCard ?: return@setOnTouchListener false
            val location = IntArray(2)
            activeCard.getLocationOnScreen(location)
            val left = location[0]
            val top = location[1]
            val right = left + activeCard.width
            val bottom = top + activeCard.height
            val x = event.rawX.toInt()
            val y = event.rawY.toInt()
            if (x !in left..right || y !in top..bottom) {
                collapseInlineLabelSettings()
            }
            false
        }
    }

    private fun setupThemeSettings() {
        currentTheme = appSettings.getAppTheme()
        updateThemeSummary(currentTheme, appSettings.getCustomPalette())
        openThemeDialogButton.setOnClickListener { showThemeDialog() }
    }

    private fun updateThemeSummary(theme: AppTheme, palette: CustomPalette) {
        val label = when (theme) {
            AppTheme.DEFAULT, AppTheme.BLACK_RED -> getString(R.string.settings_theme_default)
            else -> getString(theme.labelRes)
        }
        themeSummary.text = if (theme == AppTheme.CUSTOM) {
            getString(
                R.string.settings_theme_current_custom,
                palette.baseHex,
                palette.primaryHex,
                palette.accentHex
            )
        } else {
            getString(R.string.settings_theme_current, label)
        }
    }

    private fun showThemeDialog() {
        val context = requireContext()
        val palette = appSettings.getCustomPalette()
        val padding = (16 * resources.displayMetrics.density).toInt()
        val primaryTextColor = resolveThemeColor(R.attr.svColorTextPrimary)
        val secondaryTextColor = resolveThemeColor(R.attr.svColorTextSecondary)
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
        }

        val themeGroup = RadioGroup(context).apply { orientation = RadioGroup.VERTICAL }
        val defaultOption = RadioButton(context).apply {
            id = View.generateViewId()
            text = getString(R.string.settings_theme_default)
            setTextColor(primaryTextColor)
        }
        val pastelOption = RadioButton(context).apply {
            id = View.generateViewId()
            text = getString(R.string.settings_theme_pastel)
            setTextColor(primaryTextColor)
        }
        val deepSeaOption = RadioButton(context).apply {
            id = View.generateViewId()
            text = getString(R.string.settings_theme_deep_sea)
            setTextColor(primaryTextColor)
        }
        val customOption = RadioButton(context).apply {
            id = View.generateViewId()
            text = getString(R.string.settings_theme_custom)
            setTextColor(primaryTextColor)
        }
        themeGroup.addView(defaultOption)
        themeGroup.addView(pastelOption)
        themeGroup.addView(deepSeaOption)
        themeGroup.addView(customOption)

        val baseLayout = TextInputLayout(context).apply {
            hint = getString(R.string.settings_theme_custom_base)
            isEnabled = false
        }
        val baseInput = TextInputEditText(context).apply {
            setText(palette.baseHex)
            hint = getString(R.string.settings_theme_custom_hint)
            setTextColor(primaryTextColor)
            setHintTextColor(secondaryTextColor)
        }
        baseLayout.addView(baseInput)

        val primaryLayout = TextInputLayout(context).apply {
            hint = getString(R.string.settings_theme_custom_primary)
            isEnabled = false
        }
        val primaryInput = TextInputEditText(context).apply {
            setText(palette.primaryHex)
            hint = getString(R.string.settings_theme_custom_hint)
            setTextColor(primaryTextColor)
            setHintTextColor(secondaryTextColor)
        }
        primaryLayout.addView(primaryInput)

        val accentLayout = TextInputLayout(context).apply {
            hint = getString(R.string.settings_theme_custom_accent)
            isEnabled = false
        }
        val accentInput = TextInputEditText(context).apply {
            setText(palette.accentHex)
            hint = getString(R.string.settings_theme_custom_hint)
            setTextColor(primaryTextColor)
            setHintTextColor(secondaryTextColor)
        }
        accentLayout.addView(accentInput)

        fun setCustomEnabled(enabled: Boolean) {
            baseLayout.isEnabled = enabled
            primaryLayout.isEnabled = enabled
            accentLayout.isEnabled = enabled
        }

        container.addView(themeGroup)
        container.addView(baseLayout)
        container.addView(primaryLayout)
        container.addView(accentLayout)

        val scrollView = AndroidScrollView(context).apply { addView(container) }
        DialogUtils.ensureDialogLayoutParams(scrollView)

        val currentForUi = when (currentTheme) {
            AppTheme.BLACK_RED -> AppTheme.DEFAULT
            else -> currentTheme
        }
        when (currentForUi) {
            AppTheme.DEFAULT -> themeGroup.check(defaultOption.id)
            AppTheme.PASTEL -> themeGroup.check(pastelOption.id)
            AppTheme.DEEP_SEA -> themeGroup.check(deepSeaOption.id)
            AppTheme.CUSTOM -> {
                themeGroup.check(customOption.id)
                setCustomEnabled(true)
            }
            else -> themeGroup.check(defaultOption.id)
        }

        themeGroup.setOnCheckedChangeListener { _, checkedId ->
            setCustomEnabled(checkedId == customOption.id)
        }

        val dialog = DialogUtils.builder(context)
            .setTitle(R.string.settings_theme_title)
            .setView(scrollView)
            .setPositiveButton(android.R.string.ok, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create()

        dialog.setOnShowListener {
            DialogUtils.styleShownDialog(dialog)
            val positive = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            positive.setOnClickListener {
                val selectedTheme = when (themeGroup.checkedRadioButtonId) {
                    defaultOption.id -> AppTheme.DEFAULT
                    pastelOption.id -> AppTheme.PASTEL
                    deepSeaOption.id -> AppTheme.DEEP_SEA
                    customOption.id -> AppTheme.CUSTOM
                    else -> currentTheme
                }
                if (selectedTheme == AppTheme.CUSTOM) {
                    val base = baseInput.text?.toString()?.trim().orEmpty()
                    val primary = primaryInput.text?.toString()?.trim().orEmpty()
                    val accent = accentInput.text?.toString()?.trim().orEmpty()
                    if (!isValidColor(base) || !isValidColor(primary) || !isValidColor(accent)) {
                        Toast.makeText(requireContext(), R.string.settings_theme_custom_hint, Toast.LENGTH_SHORT).show()
                        return@setOnClickListener
                    }
                    val newPalette = CustomPalette(base, primary, accent)
                    appSettings.setCustomPalette(newPalette)
                    appSettings.setAppTheme(AppTheme.CUSTOM)
                    currentTheme = AppTheme.CUSTOM
                    DebugLogManager.addLog("设置", "应用自定义主题: base=$base primary=$primary accent=$accent")
                    Toast.makeText(requireContext(), R.string.settings_theme_applied_custom, Toast.LENGTH_SHORT).show()
                    updateThemeSummary(currentTheme, newPalette)
                    ThemeManager.applyTheme(requireActivity(), AppTheme.CUSTOM)
                    requireActivity().recreate()
                    dialog.dismiss()
                    return@setOnClickListener
                }

                if (selectedTheme != currentTheme) {
                    currentTheme = selectedTheme
                    appSettings.setAppTheme(selectedTheme)
                    DebugLogManager.addLog("设置", "切换主题: ${selectedTheme.name}")
                    Toast.makeText(requireContext(), R.string.settings_theme_applied, Toast.LENGTH_SHORT).show()
                    updateThemeSummary(selectedTheme, appSettings.getCustomPalette())
                    ThemeManager.applyTheme(requireActivity(), selectedTheme)
                    requireActivity().recreate()
                }
                dialog.dismiss()
            }
        }

        dialog.show()
    }

    private fun isValidColor(hex: String): Boolean {
        return try {
            android.graphics.Color.parseColor(hex)
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun setupPrivacySettings() {
        // 设置遮挡模式
        val currentBlurMode = privacySettings.getBlurMode()
        when (currentBlurMode) {
            PrivacySettingsManager.BLUR_MODE_MOSAIC -> blurModeMosaic.isChecked = true
            PrivacySettingsManager.BLUR_MODE_BLACK -> blurModeBlack.isChecked = true
            PrivacySettingsManager.BLUR_MODE_GAUSSIAN -> blurModeGaussian.isChecked = true
            PrivacySettingsManager.BLUR_MODE_SOBEL -> blurModeSobel.isChecked = true
            PrivacySettingsManager.BLUR_MODE_STICKER -> blurModeSticker.isChecked = true
        }
        
        // 遮挡模式变化监听
        blurModeGroup.setOnCheckedChangeListener { _, checkedId ->
            val newMode = when (checkedId) {
                R.id.blurModeMosaic -> PrivacySettingsManager.BLUR_MODE_MOSAIC
                R.id.blurModeBlack -> PrivacySettingsManager.BLUR_MODE_BLACK
                R.id.blurModeGaussian -> PrivacySettingsManager.BLUR_MODE_GAUSSIAN
                R.id.blurModeSobel -> PrivacySettingsManager.BLUR_MODE_SOBEL
                R.id.blurModeSticker -> PrivacySettingsManager.BLUR_MODE_STICKER
                else -> PrivacySettingsManager.BLUR_MODE_MOSAIC
            }
            privacySettings.setBlurMode(newMode)
            DebugLogManager.addLog("设置", "遮挡模式已更改为: ${privacySettings.getBlurModeName(newMode)}")
            refreshLabelChips()
        }

        updatePrivacyPresetSummary()
        switchPresetButton.setOnClickListener { showPresetSwitchDialog() }
        savePresetButton.setOnClickListener { saveCurrentPreset() }
        sharePresetButton.setOnClickListener { showPresetShareDialog() }
        importPresetButton.setOnClickListener { importPresetLauncher.launch(arrayOf("application/zip", "application/x-zip-compressed", "*/*")) }
        deletePresetButton.setOnClickListener { showPresetDeleteDialog() }

        circularMaskSwitch.isChecked = privacySettings.isCircularMaskEnabled()
        circularMaskSwitch.setOnCheckedChangeListener { _, isChecked ->
            privacySettings.setCircularMaskEnabled(isChecked)
            val toastText = if (isChecked) {
                R.string.settings_circular_mask_enabled
            } else {
                R.string.settings_circular_mask_disabled
            }
            Toast.makeText(requireContext(), toastText, Toast.LENGTH_SHORT).show()
            DebugLogManager.addLog("设置", "圆形遮挡开关: ${if (isChecked) "开启" else "关闭"}")
        }

        maskOutlineSwitch.isChecked = privacySettings.isMaskOutlineEnabled()
        maskOutlineSwitch.setOnCheckedChangeListener { _, isChecked ->
            privacySettings.setMaskOutlineEnabled(isChecked)
            val toastText = if (isChecked) {
                R.string.settings_mask_outline_enabled
            } else {
                R.string.settings_mask_outline_disabled
            }
            Toast.makeText(requireContext(), toastText, Toast.LENGTH_SHORT).show()
            DebugLogManager.addLog("设置", "遮挡红边开关: ${if (isChecked) "开启" else "关闭"}")
        }
        maskOutlineAdjustButton.setOnClickListener {
            showMaskOutlineSelectionDialog()
        }
        reversePreRenderSwitch.isChecked = privacySettings.isReversePreRenderEnabled()
        reversePreRenderSwitch.setOnCheckedChangeListener { _, isChecked ->
            privacySettings.setReversePreRenderEnabled(isChecked)
            val toastText = if (isChecked) {
                R.string.settings_reverse_pre_render_enabled
            } else {
                R.string.settings_reverse_pre_render_disabled
            }
            Toast.makeText(requireContext(), toastText, Toast.LENGTH_SHORT).show()
            DebugLogManager.addLog("设置", "反向优先渲染开关: ${if (isChecked) "开启" else "关闭"}")
        }
        updateMaskOutlineSummary()

        setupMosaicIntensity()
        setupGaussianIntensity()
        setupMaskScale()

        chooseStickerButton.setOnClickListener {
            pendingStickerLabel = null
            pickStickerLauncher.launch(arrayOf("image/*"))
        }

        resetStickerButton.setOnClickListener {
            privacySettings.setStickerUri(null)
            StickerLoader.clearCache()
            updateStickerSummary()
            Toast.makeText(requireContext(), R.string.settings_sticker_reset_success, Toast.LENGTH_SHORT).show()
            DebugLogManager.addLog("设置", "已切换为默认贴纸")
        }

        refreshLabelChips()
        updateStickerSummary()
    }

    private fun saveCurrentPreset() {
        val activePreset = privacySettings.getActivePresetName()
        if (activePreset.isNullOrBlank()) {
            showPresetNamingDialog()
            return
        }
        privacySettings.savePreset(activePreset)
        updatePrivacyPresetSummary()
        Toast.makeText(
            requireContext(),
            getString(R.string.settings_privacy_preset_save_overwrite, activePreset),
            Toast.LENGTH_SHORT
        ).show()
        DebugLogManager.addLog("设置", "隐私预设已更新: $activePreset")
    }

    private fun showPresetNamingDialog() {
        val context = requireContext()
        val inputLayout = TextInputLayout(context).apply {
            hint = getString(R.string.settings_privacy_preset_name_hint)
        }
        val input = TextInputEditText(context).apply {
            inputType = InputType.TYPE_CLASS_TEXT
            setSingleLine(true)
        }
        inputLayout.addView(input)
        DialogUtils.ensureDialogLayoutParams(inputLayout)
        val dialog = DialogUtils.builder(context)
            .setTitle(R.string.settings_privacy_preset_name_title)
            .setView(inputLayout)
            .setPositiveButton(android.R.string.ok, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        dialog.setOnShowListener {
            DialogUtils.styleShownDialog(dialog)
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val name = input.text?.toString()?.trim().orEmpty()
                if (name.isEmpty()) {
                    Toast.makeText(context, R.string.settings_privacy_preset_name_empty, Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                privacySettings.savePreset(name)
                updatePrivacyPresetSummary()
                Toast.makeText(
                    context,
                    getString(R.string.settings_privacy_preset_saved, name),
                    Toast.LENGTH_SHORT
                ).show()
                DebugLogManager.addLog("设置", "隐私预设已保存: $name")
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun showPresetSwitchDialog() {
        val context = requireContext()
        val saved = privacySettings.listPresetNames()
        val options = mutableListOf(getString(R.string.settings_privacy_preset_option_empty))
        options.addAll(saved)
        val active = privacySettings.getActivePresetName()
        val checkedIndex = if (active.isNullOrBlank()) 0 else options.indexOf(active).takeIf { it >= 0 } ?: 0

        DialogUtils.builder(context)
            .setTitle(R.string.settings_privacy_preset_dialog_title)
            .setSingleChoiceItems(options.toTypedArray(), checkedIndex) { dialog, which ->
                if (which == 0) {
                    privacySettings.setActivePresetName(null)
                    updatePrivacyPresetSummary()
                    Toast.makeText(context, R.string.settings_privacy_preset_switched_empty, Toast.LENGTH_SHORT).show()
                    DebugLogManager.addLog("设置", "已切换为空预设")
                    dialog.dismiss()
                    return@setSingleChoiceItems
                }
                val name = options[which]
                val applied = privacySettings.applyPreset(name)
                if (applied) {
                    refreshPrivacyUiFromSettings()
                    updatePrivacyPresetSummary()
                    Toast.makeText(
                        context,
                        getString(R.string.settings_privacy_preset_applied, name),
                        Toast.LENGTH_SHORT
                    ).show()
                    DebugLogManager.addLog("设置", "已切换隐私预设: $name")
                } else {
                    Toast.makeText(context, R.string.settings_privacy_preset_no_saved, Toast.LENGTH_SHORT).show()
                }
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun updatePrivacyPresetSummary() {
        val active = privacySettings.getActivePresetName()
        privacyPresetSummary.text = if (active.isNullOrBlank()) {
            getString(R.string.settings_privacy_preset_empty)
        } else {
            getString(R.string.settings_privacy_preset_current, active)
        }
    }

    private fun showPresetShareDialog() {
        val context = requireContext()
        val saved = privacySettings.listPresetNames()
        if (saved.isEmpty()) {
            Toast.makeText(context, R.string.settings_privacy_preset_no_saved, Toast.LENGTH_SHORT).show()
            return
        }
        DialogUtils.builder(context)
            .setTitle(R.string.settings_privacy_preset_share_title)
            .setItems(saved.toTypedArray()) { _, which ->
                sharePresetPackage(saved[which])
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun sharePresetPackage(name: String) {
        val context = requireContext()
        val rawJson = privacySettings.buildPresetPackageJson(listOf(name))
        if (rawJson.isNullOrBlank()) {
            Toast.makeText(context, R.string.settings_privacy_preset_share_failed, Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val safeName = name.replace(Regex("[^a-zA-Z0-9._-]"), "_")
            val file = File(context.cacheDir, "preset_${safeName.ifBlank { "export" }}.svpreset.zip")
            val zipped = buildPresetZip(file, rawJson)
            if (!zipped) {
                Toast.makeText(context, R.string.settings_privacy_preset_share_failed, Toast.LENGTH_SHORT).show()
                return
            }
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "application/zip"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, getString(R.string.settings_privacy_preset_share_subject, name))
                putExtra(Intent.EXTRA_TEXT, getString(R.string.settings_privacy_preset_share_text, name))
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(shareIntent, getString(R.string.settings_privacy_preset_share_chooser)))
            DebugLogManager.addLog("设置", "已分享预设包: $name")
        } catch (e: Exception) {
            Toast.makeText(context, R.string.settings_privacy_preset_share_failed, Toast.LENGTH_SHORT).show()
            DebugLogManager.addLog("设置", "分享预设包失败: ${e.message}")
        }
    }

    private fun importPresetPackage(uri: Uri) {
        val context = requireContext()
        val content = try {
            extractPresetManifestFromZip(uri)
        } catch (e: Exception) {
            DebugLogManager.addLog("设置", "读取预设包失败: ${e.message}")
            null
        }
        if (content.isNullOrBlank()) {
            Toast.makeText(context, R.string.settings_privacy_preset_import_failed, Toast.LENGTH_SHORT).show()
            return
        }
        val result = privacySettings.importPresetPackageJson(content)
        if (result.importedCount <= 0) {
            Toast.makeText(context, R.string.settings_privacy_preset_import_failed, Toast.LENGTH_SHORT).show()
            DebugLogManager.addLog("设置", "导入预设包失败: 未导入任何预设")
            return
        }
        updatePrivacyPresetSummary()
        refreshPrivacyUiFromSettings()
        Toast.makeText(
            context,
            getString(R.string.settings_privacy_preset_import_success, result.importedCount),
            Toast.LENGTH_SHORT
        ).show()
        DebugLogManager.addLog("设置", "导入预设包成功: ${result.importedNames.joinToString(",")}")
    }

    private fun buildPresetZip(targetFile: File, rawJson: String): Boolean {
        val context = requireContext()
        return try {
            val root = JSONObject(rawJson)
            val data = root.optJSONObject("data") ?: JSONObject()
            val stickerRefs = mutableMapOf<String, String>()
            val uriToEntry = linkedMapOf<String, String>()
            var index = 1

            val dataKeys = data.keys()
            while (dataKeys.hasNext()) {
                val presetName = dataKeys.next()
                val presetObj = data.optJSONObject(presetName) ?: continue
                val globalSticker = presetObj.optString("stickerUri", "").trim()
                if (globalSticker.isNotEmpty() && globalSticker != "null") {
                    val entryName = uriToEntry.getOrPut(globalSticker) {
                        "stickers/sticker_${index++}.png"
                    }
                    stickerRefs[globalSticker] = entryName
                    presetObj.put("stickerUri", "internal:$entryName")
                }
                val labelMap = presetObj.optJSONObject("labelStickerUris") ?: JSONObject()
                val labelKeys = labelMap.keys()
                while (labelKeys.hasNext()) {
                    val label = labelKeys.next()
                    val uri = labelMap.optString(label, "").trim()
                    if (uri.isEmpty() || uri == "null") continue
                    val entryName = uriToEntry.getOrPut(uri) {
                        "stickers/sticker_${index++}.png"
                    }
                    stickerRefs[uri] = entryName
                    labelMap.put(label, "internal:$entryName")
                }
            }

            ZipOutputStream(FileOutputStream(targetFile)).use { zos ->
                val manifestEntry = ZipEntry("manifest.json")
                zos.putNextEntry(manifestEntry)
                zos.write(root.toString().toByteArray(Charsets.UTF_8))
                zos.closeEntry()

                stickerRefs.forEach { (sourceUri, entryName) ->
                    val bytes = runCatching {
                        context.contentResolver.openInputStream(Uri.parse(sourceUri))?.use { it.readBytes() }
                    }.getOrNull() ?: return@forEach
                    val stickerEntry = ZipEntry(entryName)
                    zos.putNextEntry(stickerEntry)
                    zos.write(bytes)
                    zos.closeEntry()
                }
            }
            true
        } catch (e: Exception) {
            DebugLogManager.addLog("设置", "打包预设zip失败: ${e.message}")
            false
        }
    }

    private fun extractPresetManifestFromZip(zipUri: Uri): String? {
        val context = requireContext()
        val unzipRoot = File(context.filesDir, "preset_imports/${System.currentTimeMillis()}_${UUID.randomUUID()}")
        if (!unzipRoot.exists()) unzipRoot.mkdirs()
        val extractedFiles = mutableMapOf<String, File>()
        var manifestContent: String? = null

        context.contentResolver.openInputStream(zipUri)?.use { input ->
            ZipInputStream(input).use { zis ->
                while (true) {
                    val entry = zis.nextEntry ?: break
                    if (entry.isDirectory) {
                        zis.closeEntry()
                        continue
                    }
                    val safeName = entry.name.replace("\\", "/")
                    if (safeName.contains("..")) {
                        zis.closeEntry()
                        continue
                    }
                    if (safeName == "manifest.json") {
                        manifestContent = zis.readBytes().toString(Charsets.UTF_8)
                    } else if (safeName.startsWith("stickers/")) {
                        val outFile = File(unzipRoot, safeName)
                        outFile.parentFile?.mkdirs()
                        FileOutputStream(outFile).use { fos -> fos.write(zis.readBytes()) }
                        extractedFiles[safeName] = outFile
                    }
                    zis.closeEntry()
                }
            }
        }

        val manifest = manifestContent ?: return null
        val root = JSONObject(manifest)
        val data = root.optJSONObject("data") ?: JSONObject()
        val dataKeys = data.keys()
        while (dataKeys.hasNext()) {
            val presetName = dataKeys.next()
            val presetObj = data.optJSONObject(presetName) ?: continue
            val globalSticker = presetObj.optString("stickerUri", "").trim()
            if (globalSticker.startsWith("internal:")) {
                val path = globalSticker.removePrefix("internal:")
                val file = extractedFiles[path]
                if (file != null && file.exists()) {
                    val fileUri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                    presetObj.put("stickerUri", fileUri.toString())
                } else {
                    presetObj.put("stickerUri", JSONObject.NULL)
                }
            }
            val labelMap = presetObj.optJSONObject("labelStickerUris") ?: JSONObject()
            val labelKeys = labelMap.keys()
            while (labelKeys.hasNext()) {
                val label = labelKeys.next()
                val value = labelMap.optString(label, "").trim()
                if (!value.startsWith("internal:")) continue
                val path = value.removePrefix("internal:")
                val file = extractedFiles[path]
                if (file != null && file.exists()) {
                    val fileUri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                    labelMap.put(label, fileUri.toString())
                } else {
                    labelMap.remove(label)
                }
            }
        }
        return root.toString()
    }

    private fun showPresetDeleteDialog() {
        val context = requireContext()
        val saved = privacySettings.listPresetNames()
        if (saved.isEmpty()) {
            Toast.makeText(context, R.string.settings_privacy_preset_no_saved, Toast.LENGTH_SHORT).show()
            return
        }
        DialogUtils.builder(context)
            .setTitle(R.string.settings_privacy_preset_delete_title)
            .setItems(saved.toTypedArray()) { _, which ->
                val name = saved[which]
                val deleted = privacySettings.deletePreset(name)
                if (deleted) {
                    updatePrivacyPresetSummary()
                    Toast.makeText(
                        context,
                        getString(R.string.settings_privacy_preset_deleted, name),
                        Toast.LENGTH_SHORT
                    ).show()
                    DebugLogManager.addLog("设置", "已删除隐私预设: $name")
                } else {
                    Toast.makeText(context, R.string.settings_privacy_preset_delete_failed, Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun refreshPrivacyUiFromSettings() {
        when (privacySettings.getBlurMode()) {
            PrivacySettingsManager.BLUR_MODE_MOSAIC -> blurModeMosaic.isChecked = true
            PrivacySettingsManager.BLUR_MODE_BLACK -> blurModeBlack.isChecked = true
            PrivacySettingsManager.BLUR_MODE_GAUSSIAN -> blurModeGaussian.isChecked = true
            PrivacySettingsManager.BLUR_MODE_SOBEL -> blurModeSobel.isChecked = true
            PrivacySettingsManager.BLUR_MODE_STICKER -> blurModeSticker.isChecked = true
        }
        circularMaskSwitch.isChecked = privacySettings.isCircularMaskEnabled()
        maskOutlineSwitch.isChecked = privacySettings.isMaskOutlineEnabled()
        reversePreRenderSwitch.isChecked = privacySettings.isReversePreRenderEnabled()

        val mosaicMin = PrivacySettingsManager.MOSAIC_BLOCK_MIN
        val mosaicProgress = (privacySettings.getMosaicBlockSize() - mosaicMin).coerceIn(0, mosaicIntensitySeekBar.max)
        mosaicIntensitySeekBar.progress = mosaicProgress
        updateMosaicIntensitySummary(privacySettings.getMosaicBlockSize())

        val gaussianMin = PrivacySettingsManager.GAUSSIAN_RADIUS_MIN
        val gaussianProgress = (privacySettings.getGaussianRadius() - gaussianMin).coerceIn(0, gaussianIntensitySeekBar.max)
        gaussianIntensitySeekBar.progress = gaussianProgress
        updateGaussianIntensitySummary(privacySettings.getGaussianRadius())
        val scaleMin = (PrivacySettingsManager.MASK_SCALE_MIN * 10).toInt()
        val scaleProgress = ((privacySettings.getMaskScale() * 10).toInt() - scaleMin)
            .coerceIn(0, maskScaleSeekBar.max)
        maskScaleSeekBar.progress = scaleProgress
        updateMaskScaleSummary(privacySettings.getMaskScale())

        updateMaskOutlineSummary()
        updateStickerSummary()
        refreshLabelChips()
    }

    private fun setupMosaicIntensity() {
        val min = PrivacySettingsManager.MOSAIC_BLOCK_MIN
        val max = PrivacySettingsManager.MOSAIC_BLOCK_MAX
        mosaicIntensitySeekBar.max = max - min
        val current = privacySettings.getMosaicBlockSize()
        mosaicIntensitySeekBar.progress = (current - min).coerceIn(0, mosaicIntensitySeekBar.max)
        updateMosaicIntensitySummary(current)
        mosaicIntensitySeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                val value = (min + progress).coerceIn(min, max)
                privacySettings.setMosaicBlockSize(value)
                updateMosaicIntensitySummary(value)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) = Unit

            override fun onStopTrackingTouch(seekBar: SeekBar) = Unit
        })
    }

    private fun setupGaussianIntensity() {
        val min = PrivacySettingsManager.GAUSSIAN_RADIUS_MIN
        val max = PrivacySettingsManager.GAUSSIAN_RADIUS_MAX
        gaussianIntensitySeekBar.max = max - min
        val current = privacySettings.getGaussianRadius()
        gaussianIntensitySeekBar.progress = (current - min).coerceIn(0, gaussianIntensitySeekBar.max)
        updateGaussianIntensitySummary(current)
        gaussianIntensitySeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                val value = (min + progress).coerceIn(min, max)
                privacySettings.setGaussianRadius(value)
                updateGaussianIntensitySummary(value)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) = Unit

            override fun onStopTrackingTouch(seekBar: SeekBar) = Unit
        })
    }

    private fun updateMosaicIntensitySummary(value: Int) {
        mosaicIntensitySummary.text = getString(R.string.settings_mosaic_intensity_summary, value)
    }

    private fun updateGaussianIntensitySummary(value: Int) {
        gaussianIntensitySummary.text = getString(R.string.settings_gaussian_intensity_summary, value)
    }

    private fun setupMaskScale() {
        val min = (PrivacySettingsManager.MASK_SCALE_MIN * 10).toInt()
        val max = (PrivacySettingsManager.MASK_SCALE_MAX * 10).toInt()
        maskScaleSeekBar.max = max - min
        val current = privacySettings.getMaskScale()
        maskScaleSeekBar.progress = ((current * 10).toInt() - min).coerceIn(0, maskScaleSeekBar.max)
        updateMaskScaleSummary(current)
        maskScaleSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                val value = ((min + progress) / 10f).coerceIn(
                    PrivacySettingsManager.MASK_SCALE_MIN,
                    PrivacySettingsManager.MASK_SCALE_MAX
                )
                privacySettings.setMaskScale(value)
                updateMaskScaleSummary(value)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) = Unit

            override fun onStopTrackingTouch(seekBar: SeekBar) = Unit
        })
    }

    private fun updateMaskScaleSummary(value: Float) {
        maskScaleSummary.text = getString(R.string.settings_mask_scale_summary, value)
    }

    private fun showMaskOutlineSelectionDialog() {
        val labels = privacySettings.getAllAvailableLabels()
        val displayNames = privacySettings.getDisplayNames(labels)
        val selected = privacySettings.getMaskOutlineLabels().toMutableSet()
        val checked = labels.map { selected.contains(it) }.toBooleanArray()

        DialogUtils.builder(requireContext())
            .setTitle(R.string.settings_mask_outline_dialog_title)
            .setMultiChoiceItems(displayNames.toTypedArray(), checked) { _, which, isChecked ->
                checked[which] = isChecked
            }
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val chosen = labels.filterIndexed { index, _ -> checked.getOrNull(index) == true }
                val finalSelection = if (chosen.isEmpty()) labels else chosen
                privacySettings.setMaskOutlineLabels(finalSelection)
                updateMaskOutlineSummary()
                Toast.makeText(requireContext(), R.string.settings_mask_outline_saved, Toast.LENGTH_SHORT).show()
                DebugLogManager.addLog(
                    "设置",
                    "遮挡红边部位: ${privacySettings.getDisplayNames(finalSelection).joinToString()}"
                )
            }
            .setNegativeButton(android.R.string.cancel, null)
            .setNeutralButton(R.string.settings_mask_outline_select_all) { _, _ ->
                privacySettings.setMaskOutlineLabels(labels)
                updateMaskOutlineSummary()
                Toast.makeText(requireContext(), R.string.settings_mask_outline_saved, Toast.LENGTH_SHORT).show()
                DebugLogManager.addLog("设置", "遮挡红边部位: 全部")
            }
            .show()
    }

    private fun updateMaskOutlineSummary() {
        val selected = privacySettings.getMaskOutlineLabels()
        val allLabels = privacySettings.getAllAvailableLabels()
        val selectionText = if (selected.size >= allLabels.size) {
            getString(R.string.settings_mask_outline_scope_all)
        } else {
            val names = privacySettings.getDisplayNames(selected)
            val preview = names.take(2).joinToString("、")
            val remaining = (names.size - 2).coerceAtLeast(0)
            if (remaining > 0) {
                getString(R.string.settings_mask_outline_scope_partial, preview, remaining)
            } else {
                preview.ifBlank { getString(R.string.settings_mask_outline_scope_all) }
            }
        }
        maskOutlineSummary.text = getString(
            R.string.settings_mask_outline_summary_with_selection,
            selectionText
        )
    }
    
    private fun setupUploadSettings() {
        uploadViaFileSystemSwitch.isChecked = appSettings.isFileSystemPickerEnabled()
        uploadViaFileSystemSwitch.setOnCheckedChangeListener { _, isChecked ->
            appSettings.setFileSystemPickerEnabled(isChecked)
            val message = if (isChecked) {
                getString(R.string.settings_upload_via_files_enabled)
            } else {
                getString(R.string.settings_upload_via_files_disabled)
            }
            Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
            DebugLogManager.addLog("设置", "媒体选择入口切换为${if (isChecked) "文件系统" else "相册"}")
        }
    }

    private fun setupVideoSpeedSettings() {
        updateVideoSpeedSummary()
        videoSpeedButton.setOnClickListener {
            showVideoSpeedDialog()
        }
    }

    private fun setupRandomPlaySettings() {
        randomBrowseSwitch.isChecked = appSettings.isRandomBrowseEnabled()
        updateRandomQueueSummary()
        randomBrowseSwitch.setOnCheckedChangeListener { _, isChecked ->
            appSettings.setRandomBrowseEnabled(isChecked)
            val toastText = if (isChecked) {
                R.string.settings_random_browse_toast_enabled
            } else {
                R.string.settings_random_browse_toast_disabled
            }
            Toast.makeText(requireContext(), toastText, Toast.LENGTH_SHORT).show()
            DebugLogManager.addLog("设置", "随机浏览开关: ${if (isChecked) "开启" else "关闭"}")
        }
        randomQueueButton.setOnClickListener {
            showRandomQueueDialog()
        }

        randomPlaySwitch.isChecked = appSettings.isRandomPlayEnabled()
        updateRandomPlaySummary()
        randomPlaySwitch.setOnCheckedChangeListener { _, isChecked ->
            appSettings.setRandomPlayEnabled(isChecked)
            updateRandomPlaySummary()
            val toastText = if (isChecked) {
                R.string.settings_random_play_toast_enabled
            } else {
                R.string.settings_random_play_toast_disabled
            }
            Toast.makeText(requireContext(), toastText, Toast.LENGTH_SHORT).show()
            DebugLogManager.addLog("设置", "随机播放开关: ${if (isChecked) "开启" else "关闭"}")
        }
        randomPlayIntervalButton.setOnClickListener {
            showRandomPlayIntervalDialog()
        }
    }

    private fun setupMetronomeSettings() {
        metronomeSwitch.isChecked = appSettings.isMetronomeEnabled()
        updateMetronomeSummary()
        metronomeSwitch.setOnCheckedChangeListener { _, isChecked ->
            appSettings.setMetronomeEnabled(isChecked)
            updateMetronomeSummary()
            val toastText = if (isChecked) {
                R.string.settings_metronome_toast_enabled
            } else {
                R.string.settings_metronome_toast_disabled
            }
            Toast.makeText(requireContext(), toastText, Toast.LENGTH_SHORT).show()
            DebugLogManager.addLog("设置", "节拍器开关: ${if (isChecked) "开启" else "关闭"}")
        }
        metronomeSpeedButton.setOnClickListener {
            showMetronomeSpeedDialog()
        }
    }

    private fun showVideoSpeedDialog() {
        val optionsText = videoSpeedOptions.map { getString(it.labelRes) }.toTypedArray()
        val currentStride = appSettings.getVideoSkipStride()
        val checkedIndex = videoSpeedOptions.indexOfFirst { it.skipStride == currentStride }
            .takeIf { it >= 0 } ?: 0

        DialogUtils.builder(requireContext())
            .setTitle(R.string.settings_video_speed_dialog_title)
            .setSingleChoiceItems(optionsText, checkedIndex) { dialog, which ->
                val selected = videoSpeedOptions[which]
                appSettings.setVideoSkipStride(selected.skipStride)
                updateVideoSpeedSummary()
                DebugLogManager.addLog("设置", "视频处理速度已调整为: ${optionsText[which]}")
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showMetronomeSpeedDialog() {
        val context = requireContext()
        val density = resources.displayMetrics.density
        val padding = (20 * density).toInt()
        val topSpacing = (14 * density).toInt()
        val minDialogBodyHeight = (150 * density).toInt()
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
            minimumHeight = minDialogBodyHeight
        }
        val inputLayout = TextInputLayout(context).apply {
            hint = getString(R.string.settings_metronome_dialog_hint)
            // 显式设置 hint 颜色，避免在深色/自定义主题下过灰导致看不清。
            defaultHintTextColor = colorStateList(R.attr.svColorTextPrimary)
            hintTextColor = colorStateList(R.attr.svColorTextPrimary)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = topSpacing
            }
        }
        val input = TextInputEditText(context).apply {
            setText(String.format(Locale.US, "%.2f", appSettings.getMetronomeIntervalSeconds()))
            hint = getString(R.string.settings_metronome_input_hint)
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setTextColor(resolveThemeColor(R.attr.svColorTextPrimary))
            setHintTextColor(resolveThemeColor(R.attr.svColorTextSecondary))
        }
        inputLayout.addView(input)
        container.addView(inputLayout)

        val scrollView = AndroidScrollView(context).apply {
            addView(container)
            isFillViewport = true
        }
        DialogUtils.ensureDialogLayoutParams(scrollView)

        val dialog = DialogUtils.builder(context)
            .setTitle(R.string.settings_metronome_dialog_title)
            .setView(scrollView)
            .setPositiveButton(android.R.string.ok, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create()

        dialog.setOnShowListener {
            DialogUtils.styleShownDialog(dialog)
            val positive = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            positive.setOnClickListener {
                val rawValue = input.text?.toString()?.trim().orEmpty()
                val interval = rawValue.toFloatOrNull()
                val minInterval = 0.1f
                val maxInterval = 5.0f
                if (interval == null || interval < minInterval || interval > maxInterval) {
                    inputLayout.error = getString(R.string.settings_metronome_invalid_input)
                    return@setOnClickListener
                }
                inputLayout.error = null
                appSettings.setMetronomeIntervalSeconds(interval)
                updateMetronomeSummary()
                DebugLogManager.addLog("设置", "节拍器间隔调整为: ${interval}s")
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun showRandomPlayIntervalDialog() {
        val optionsSeconds = listOf(5, 10, 20, 30, 60, 120)
        val optionsText = optionsSeconds.map { getString(R.string.settings_random_play_option_seconds, it) }.toTypedArray()
        val current = appSettings.getRandomPlayIntervalSeconds()
        val checkedIndex = optionsSeconds.indexOfFirst { it == current }.takeIf { it >= 0 } ?: 1

        DialogUtils.builder(requireContext())
            .setTitle(R.string.settings_random_play_dialog_title)
            .setSingleChoiceItems(optionsText, checkedIndex) { dialog, which ->
                val selected = optionsSeconds.getOrElse(which) { current }
                appSettings.setRandomPlayIntervalSeconds(selected)
                updateRandomPlaySummary()
                DebugLogManager.addLog("设置", "随机播放倒计时调整为: ${selected}s")
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showRandomQueueDialog() {
        val sourceOptions = listOf(
            AppSettingsManager.RandomQueueType.SAFENET to getString(R.string.settings_random_queue_option_safenet),
            AppSettingsManager.RandomQueueType.NO_DETECTION to getString(R.string.settings_random_queue_option_no_detection),
            AppSettingsManager.RandomQueueType.VIDEO_OUTPUT to getString(R.string.settings_random_queue_option_video)
        )
        val currentSources = appSettings.getRandomQueueTypes().toMutableSet()
        val currentLabels = appSettings.getRandomQueueLabels().toMutableSet()
        val allLabels = DetectionConfig.LABELS
        val context = requireContext()
        val padding = (16 * resources.displayMetrics.density).toInt()
        val smallMargin = (8 * resources.displayMetrics.density).toInt()
        val sectionMargin = (12 * resources.displayMetrics.density).toInt()

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
        }

        val sourceTitle = TextView(context).apply {
            text = getString(R.string.settings_random_queue_sources_title)
            setTextColor(resolveThemeColor(R.attr.svColorTextPrimary))
            textSize = 14f
        }
        container.addView(sourceTitle)

        sourceOptions.forEach { (type, label) ->
            CheckBox(context).apply {
                text = label
                isChecked = currentSources.contains(type)
                setTextColor(resolveThemeColor(R.attr.svColorTextPrimary))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = smallMargin
                }
                setOnCheckedChangeListener { _, checked ->
                    if (checked) currentSources.add(type) else currentSources.remove(type)
                }
            }.also(container::addView)
        }

        val labelsTitle = TextView(context).apply {
            text = getString(R.string.settings_random_queue_labels_title_with_count, allLabels.size)
            setTextColor(resolveThemeColor(R.attr.svColorTextPrimary))
            textSize = 14f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = sectionMargin
            }
        }
        container.addView(labelsTitle)

        val quickActionRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = smallMargin
            }
        }
        val selectAllButton = Button(context).apply {
            text = getString(R.string.settings_random_queue_select_all)
            layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            )
        }
        val clearAllButton = Button(context).apply {
            text = getString(R.string.settings_random_queue_clear_all)
            layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            ).apply {
                marginStart = smallMargin
            }
        }
        quickActionRow.addView(selectAllButton)
        quickActionRow.addView(clearAllButton)
        container.addView(quickActionRow)

        val labelChecks = allLabels.map { label ->
            CheckBox(context).apply {
                text = privacySettings.getLabelDisplayName(label)
                isChecked = currentLabels.contains(label)
                setTextColor(resolveThemeColor(R.attr.svColorTextPrimary))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = smallMargin
                }
                setOnCheckedChangeListener { _, checked ->
                    if (checked) currentLabels.add(label) else currentLabels.remove(label)
                }
            }.also(container::addView)
        }

        selectAllButton.setOnClickListener {
            labelChecks.forEach { it.isChecked = true }
        }
        clearAllButton.setOnClickListener {
            labelChecks.forEach { it.isChecked = false }
        }

        val scrollView = AndroidScrollView(context).apply {
            addView(container)
        }
        DialogUtils.ensureDialogLayoutParams(scrollView)

        DialogUtils.builder(context)
            .setTitle(R.string.settings_random_queue_dialog_title)
            .setView(scrollView)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val finalLabels = if (currentLabels.isEmpty()) {
                    Toast.makeText(context, R.string.settings_random_queue_labels_empty_reset, Toast.LENGTH_SHORT).show()
                    allLabels.toSet()
                } else {
                    currentLabels.toSet()
                }
                appSettings.setRandomQueueTypes(currentSources.toSet())
                appSettings.setRandomQueueLabels(finalLabels)
                updateRandomQueueSummary()
                Toast.makeText(requireContext(), R.string.settings_random_queue_saved, Toast.LENGTH_SHORT).show()
                DebugLogManager.addLog(
                    "设置",
                    "随机队列更新: 来源=${appSettings.getRandomQueueTypes().joinToString()} 标签=${appSettings.getRandomQueueLabels().size}/${DetectionConfig.LABELS.size}"
                )
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun updateRandomPlaySummary() {
        val seconds = appSettings.getRandomPlayIntervalSeconds()
        randomPlaySummary.text = getString(R.string.settings_random_play_summary_current, seconds)
        randomPlayIntervalButton.isEnabled = randomPlaySwitch.isChecked
    }

    private fun updateRandomQueueSummary() {
        val selected = appSettings.getRandomQueueTypes()
        val selectedSources = buildList {
            if (selected.contains(AppSettingsManager.RandomQueueType.SAFENET)) {
                add(getString(R.string.settings_random_queue_option_safenet))
            }
            if (selected.contains(AppSettingsManager.RandomQueueType.NO_DETECTION)) {
                add(getString(R.string.settings_random_queue_option_no_detection))
            }
            if (selected.contains(AppSettingsManager.RandomQueueType.VIDEO_OUTPUT)) {
                add(getString(R.string.settings_random_queue_option_video))
            }
        }
        val selectedLabels = appSettings.getRandomQueueLabels()
        val totalLabels = DetectionConfig.LABELS.size
        val labelSummary = if (selectedLabels.size >= totalLabels) {
            getString(R.string.settings_random_queue_labels_all)
        } else {
            getString(R.string.settings_random_queue_labels_partial, selectedLabels.size, totalLabels)
        }
        randomQueueSummary.text = getString(
            R.string.settings_random_queue_summary_with_labels,
            selectedSources.joinToString("、"),
            labelSummary
        )
    }

    private fun updateMetronomeSummary() {
        val interval = appSettings.getMetronomeIntervalSeconds()
        val rate = if (interval > 0f) 1f / interval else 0f
        metronomeSummary.text = getString(R.string.settings_metronome_summary, interval, rate)
        metronomeSpeedButton.isEnabled = metronomeSwitch.isChecked
    }

    private fun updateVideoSpeedSummary() {
        val currentStride = appSettings.getVideoSkipStride()
        val label = videoSpeedOptions.firstOrNull { it.skipStride == currentStride }
            ?.let { getString(it.labelRes) }
            ?: getString(R.string.settings_video_speed_best)
        videoSpeedSummary.text = getString(R.string.settings_video_speed_summary, label)
    }
    
    private fun setupDebugFeatures() {
        debugToggle.setOnClickListener {
            showDebugDialog()
        }
        shareLogsButton.setOnClickListener {
            shareLogs()
        }
    }

    private fun setupSupportSection() {
        supportButton.setOnClickListener {
            showSupportDialog()
        }
    }

    private fun setupLanguageSection() {
        languageButton.setOnClickListener {
            showLanguageDialog()
        }
    }

    private fun showLanguageDialog() {
        val context = requireContext()
        val options = listOf(
            LanguageOption(
                AppLanguageManager.FOLLOW_SYSTEM,
                getString(R.string.settings_language_follow_system)
            ),
            LanguageOption(
                AppLanguageManager.SIMPLIFIED_CHINESE,
                getString(R.string.settings_language_simplified_chinese)
            ),
            LanguageOption(
                AppLanguageManager.TRADITIONAL_CHINESE,
                getString(R.string.settings_language_traditional_chinese)
            ),
            LanguageOption(
                AppLanguageManager.ENGLISH,
                getString(R.string.settings_language_english)
            ),
            LanguageOption(
                AppLanguageManager.KOREAN,
                getString(R.string.settings_language_korean)
            )
        )
        val currentPreference = appSettings.getAppLanguage()
        val checkedIndex = options.indexOfFirst { it.preference == currentPreference }
            .takeIf { it >= 0 } ?: 0

        DialogUtils.builder(context)
            .setTitle(R.string.settings_language_dialog_title)
            .setSingleChoiceItems(options.map { it.label }.toTypedArray(), checkedIndex) { dialog, which ->
                val selected = options[which]
                if (selected.preference == currentPreference) {
                    dialog.dismiss()
                    return@setSingleChoiceItems
                }
                appSettings.setAppLanguage(selected.preference)
                val appliedLanguageTag = AppLanguageManager.resolveLanguageTagForPreference(selected.preference)
                AppCompatDelegate.setApplicationLocales(
                    AppLanguageManager.resolveLocalesForPreference(selected.preference)
                )
                val appliedLabel = options.firstOrNull { it.preference == appliedLanguageTag }?.label
                    ?: getString(R.string.settings_language_english)
                Toast.makeText(
                    context,
                    getString(R.string.settings_language_changed, appliedLabel),
                    Toast.LENGTH_SHORT
                ).show()
                DebugLogManager.addLog(
                    "设置",
                    "应用语言切换: preference=${selected.preference}, effective=$appliedLanguageTag"
                )
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun setupCommunitySection() {
        joinGroupButton.setOnClickListener {
            val uri = Uri.parse("https://t.me/+TRXOtmlDZ7c5M2Fl")
            val intent = Intent(Intent.ACTION_VIEW, uri)
            startActivity(intent)
            DebugLogManager.addLog("设置", "打开官方群组链接: $uri")
        }
    }

    private fun showDebugDialog() {
        val logs = DebugLogManager.getLogs().ifBlank {
            getString(R.string.settings_debug_empty)
        }
        
        val scrollView = AndroidScrollView(requireContext())
        val textView = TextView(requireContext()).apply {
            text = logs
            setPadding(32, 32, 32, 32)
            textSize = 12f
            typeface = android.graphics.Typeface.MONOSPACE
            setTextIsSelectable(true)
        }
        scrollView.addView(textView)
        DialogUtils.ensureDialogLayoutParams(scrollView)
        
        DialogUtils.builder(requireContext())
            .setTitle(getString(R.string.settings_debug_dialog_title))
            .setView(scrollView)
            .setPositiveButton(R.string.common_close, null)
            .setNeutralButton(R.string.common_copy) { _, _ ->
                DebugLogManager.copyToClipboard(requireContext())
            }
            .show()
    }

    private fun shareLogs() {
        val logFile = DebugLogManager.getCurrentLogFile(requireContext())
        if (logFile == null || !logFile.exists()) {
            Toast.makeText(requireContext(), R.string.settings_share_logs_error, Toast.LENGTH_SHORT).show()
            DebugLogManager.addLog("设置", "分享日志失败: 日志文件不存在")
            return
        }

        try {
            val uri = FileProvider.getUriForFile(
                requireContext(),
                "${requireContext().packageName}.fileprovider",
                logFile
            )

            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, getString(R.string.settings_share_logs))
                putExtra(Intent.EXTRA_TEXT, getString(R.string.settings_share_logs_message, logFile.name))
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            startActivity(Intent.createChooser(intent, getString(R.string.settings_share_logs)))
            DebugLogManager.addLog("设置", "分享日志文件: ${logFile.absolutePath}")
        } catch (e: Exception) {
            Toast.makeText(requireContext(), R.string.settings_share_logs_error, Toast.LENGTH_LONG).show()
            DebugLogManager.addLog("设置", "分享日志失败: ${e.message}")
        }
    }

    private fun showSupportDialog() {
        val context = requireContext()
        val scrollView = AndroidScrollView(context)
        val padding = (16 * resources.displayMetrics.density).toInt()
        val imageTopMargin = (12 * resources.displayMetrics.density).toInt()
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
        }

        val messageView = TextView(context).apply {
            text = getString(R.string.support_message)
            textSize = 14f
            setTextColor(resolveThemeColor(R.attr.svColorTextPrimary))
        }

        val imageView = ImageView(context).apply {
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = imageTopMargin
            }
        }

        val stickerBitmap = try {
            context.assets.open("support.jpg").use { stream ->
                BitmapFactory.decodeStream(stream)
            }
        } catch (e: Exception) {
            DebugLogManager.addLog("设置", "加载赞赏码失败: ${e.message}")
            null
        }

        if (stickerBitmap != null) {
            imageView.setImageBitmap(stickerBitmap)
        }

        container.addView(messageView)
        container.addView(imageView)
        scrollView.addView(container)
        DialogUtils.ensureDialogLayoutParams(scrollView)

        DialogUtils.builder(context)
            .setTitle(R.string.support_title)
            .setView(scrollView)
            .setPositiveButton(android.R.string.ok, null)
            .show()

        DebugLogManager.addLog("设置", "打开赞赏弹窗")
    }

    private data class LanguageOption(val preference: String, val label: String)

    private data class VideoSpeedOption(val skipStride: Int, val labelRes: Int)
}
