package com.safe.vision

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.widget.doAfterTextChanged
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import java.util.Locale

object ThemeSelectionDialog {
    data class Selection(val theme: AppTheme, val palette: CustomPalette)

    private data class ThemeOption(
        val card: MaterialCardView,
        val radio: RadioButton,
        val swatches: List<View>
    )

    private data class ColorEditor(
        val input: TextInputEditText,
        val swatch: MaterialButton
    )

    fun show(
        context: Context,
        currentTheme: AppTheme,
        currentPalette: CustomPalette,
        onApply: (Selection) -> Unit
    ) {
        val density = context.resources.displayMetrics.density
        fun dp(value: Int): Int = (value * density).toInt()

        val primaryText = DialogUtils.resolveThemeColor(context, R.attr.svColorTextPrimary)
        val secondaryText = DialogUtils.resolveThemeColor(context, R.attr.svColorTextSecondary)
        val cardColor = DialogUtils.resolveThemeColor(context, R.attr.svColorCard)
        val chipColor = DialogUtils.resolveThemeColor(context, R.attr.svColorChip)
        val borderColor = DialogUtils.resolveThemeColor(context, R.attr.svColorBorder)
        val accentColor = DialogUtils.resolveThemeColor(context, R.attr.svColorAccent)

        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(8))
        }
        val optionsContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        content.addView(optionsContainer)

        val options = linkedMapOf<AppTheme, ThemeOption>()
        var selectedTheme = if (currentTheme == AppTheme.BLACK_RED) AppTheme.DEFAULT else currentTheme

        fun createSwatch(color: Int): View {
            return View(context).apply {
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                    cornerRadius = dp(5).toFloat()
                    setColor(color)
                    setStroke(dp(1), borderColor)
                }
                layoutParams = LinearLayout.LayoutParams(dp(22), dp(22)).apply {
                    marginStart = dp(4)
                }
            }
        }

        fun addThemeOption(theme: AppTheme, labelRes: Int, palette: CustomPalette): ThemeOption {
            val row = LinearLayout(context).apply {
                gravity = Gravity.CENTER_VERTICAL
                orientation = LinearLayout.HORIZONTAL
                setPadding(dp(12), dp(8), dp(8), dp(8))
            }
            row.addView(TextView(context).apply {
                text = context.getString(labelRes)
                textSize = 16f
                setTextColor(primaryText)
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

            val swatches = paletteColors(palette).map { color -> createSwatch(color) }
            swatches.forEach(row::addView)
            val radio = RadioButton(context).apply {
                isClickable = false
                buttonTintList = ColorStateList.valueOf(accentColor)
            }
            row.addView(radio, LinearLayout.LayoutParams(dp(44), dp(44)).apply {
                marginStart = dp(4)
            })

            val card = MaterialCardView(context).apply {
                radius = dp(8).toFloat()
                cardElevation = 0f
                setCardBackgroundColor(cardColor)
                strokeColor = borderColor
                strokeWidth = dp(1)
                isClickable = true
                isFocusable = true
                addView(row)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(8) }
            }
            optionsContainer.addView(card)
            return ThemeOption(card, radio, swatches).also { options[theme] = it }
        }

        addThemeOption(
            AppTheme.DEFAULT,
            R.string.settings_theme_name_black_red,
            CustomPalette("#000000", "#FF3B30", "#7D3CFF")
        )
        addThemeOption(
            AppTheme.PASTEL,
            R.string.settings_theme_name_pastel,
            CustomPalette("#BBD8FF", "#FFD4C4", "#2B3A67")
        )
        addThemeOption(
            AppTheme.DEEP_SEA,
            R.string.settings_theme_name_deep_sea,
            CustomPalette("#114468", "#EF6837", "#2DE2E6")
        )
        val customOption = addThemeOption(
            AppTheme.CUSTOM,
            R.string.settings_theme_name_custom,
            currentPalette
        )

        val customPanel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(4), 0, dp(4))
        }
        content.addView(customPanel)

        fun updateCustomPreview() {
            val inputs = customPanel.tag as? List<*> ?: return
            val editors = inputs.filterIsInstance<ColorEditor>()
            if (editors.size != 3) return
            editors.forEachIndexed { index, editor ->
                parseColorOrNull(editor.input.text?.toString())?.let { color ->
                    editor.swatch.backgroundTintList = ColorStateList.valueOf(color)
                    customOption.swatches[index].background =
                        android.graphics.drawable.GradientDrawable().apply {
                            shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                            cornerRadius = dp(5).toFloat()
                            setColor(color)
                            setStroke(dp(1), borderColor)
                        }
                }
            }
        }

        fun addColorEditor(labelRes: Int, initialValue: String): ColorEditor {
            val row = LinearLayout(context).apply {
                gravity = Gravity.CENTER_VERTICAL
                orientation = LinearLayout.HORIZONTAL
            }
            val inputLayout = TextInputLayout(context).apply {
                hint = context.getString(labelRes)
                boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
                boxStrokeColor = accentColor
                defaultHintTextColor = ColorStateList.valueOf(secondaryText)
            }
            val input = TextInputEditText(context).apply {
                setText(initialValue)
                isSingleLine = true
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
                setTextColor(primaryText)
                setHintTextColor(secondaryText)
            }
            inputLayout.addView(input)
            row.addView(inputLayout, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

            val swatch = MaterialButton(context).apply {
                text = ""
                contentDescription = context.getString(labelRes)
                minimumWidth = dp(48)
                minimumHeight = dp(48)
                insetTop = 0
                insetBottom = 0
                cornerRadius = dp(8)
                backgroundTintList = ColorStateList.valueOf(
                    parseColorOrNull(initialValue) ?: Color.TRANSPARENT
                )
            }
            row.addView(swatch, LinearLayout.LayoutParams(dp(48), dp(48)).apply {
                marginStart = dp(12)
            })
            customPanel.addView(row, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8) })

            val editor = ColorEditor(input, swatch)
            input.doAfterTextChanged { updateCustomPreview() }
            swatch.setOnClickListener {
                val color = parseColorOrNull(input.text?.toString()) ?: Color.BLACK
                showRgbPicker(context, context.getString(labelRes), color) { selected ->
                    input.setText(formatColor(selected))
                    input.setSelection(input.text?.length ?: 0)
                }
            }
            return editor
        }

        val editors = listOf(
            addColorEditor(R.string.settings_theme_custom_base, currentPalette.baseHex),
            addColorEditor(R.string.settings_theme_custom_primary, currentPalette.primaryHex),
            addColorEditor(R.string.settings_theme_custom_accent, currentPalette.accentHex)
        )
        customPanel.tag = editors

        fun selectTheme(theme: AppTheme) {
            selectedTheme = theme
            options.forEach { (optionTheme, option) ->
                val selected = optionTheme == theme
                option.radio.isChecked = selected
                option.card.strokeColor = if (selected) accentColor else borderColor
                option.card.strokeWidth = dp(if (selected) 2 else 1)
                option.card.setCardBackgroundColor(if (selected) chipColor else cardColor)
            }
            customPanel.visibility = if (theme == AppTheme.CUSTOM) View.VISIBLE else View.GONE
        }

        options.forEach { (theme, option) ->
            option.card.setOnClickListener { selectTheme(theme) }
        }
        selectTheme(selectedTheme)
        updateCustomPreview()

        val scrollView = ScrollView(context).apply { addView(content) }
        DialogUtils.ensureDialogLayoutParams(scrollView)
        val dialog = DialogUtils.builder(context)
            .setTitle(R.string.settings_theme_title)
            .setView(scrollView)
            .setPositiveButton(android.R.string.ok, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create()

        dialog.setOnShowListener {
            DialogUtils.styleShownDialog(dialog)
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val palette = if (selectedTheme == AppTheme.CUSTOM) {
                    val values = editors.map { it.input.text?.toString()?.trim().orEmpty() }
                    if (values.any { parseColorOrNull(it) == null }) {
                        Toast.makeText(
                            context,
                            R.string.settings_theme_custom_hint,
                            Toast.LENGTH_SHORT
                        ).show()
                        return@setOnClickListener
                    }
                    CustomPalette(values[0], values[1], values[2])
                } else {
                    currentPalette
                }
                onApply(Selection(selectedTheme, palette))
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun showRgbPicker(
        context: Context,
        title: String,
        initialColor: Int,
        onSelected: (Int) -> Unit
    ) {
        val density = context.resources.displayMetrics.density
        fun dp(value: Int): Int = (value * density).toInt()

        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(4))
        }
        val preview = MaterialCardView(context).apply {
            radius = dp(8).toFloat()
            cardElevation = 0f
            strokeWidth = dp(1)
            strokeColor = DialogUtils.resolveThemeColor(context, R.attr.svColorBorder)
        }
        content.addView(preview, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(64)
        ).apply { bottomMargin = dp(12) })

        val alpha = Color.alpha(initialColor)
        val channelValues = intArrayOf(
            Color.red(initialColor),
            Color.green(initialColor),
            Color.blue(initialColor)
        )

        fun selectedColor(): Int = Color.argb(
            alpha,
            channelValues[0],
            channelValues[1],
            channelValues[2]
        )

        fun updatePreview() {
            preview.setCardBackgroundColor(selectedColor())
        }

        listOf("R", "G", "B").forEachIndexed { index, label ->
            val row = LinearLayout(context).apply {
                gravity = Gravity.CENTER_VERTICAL
                orientation = LinearLayout.HORIZONTAL
            }
            row.addView(TextView(context).apply {
                text = label
                gravity = Gravity.CENTER
            }, LinearLayout.LayoutParams(dp(28), dp(48)))
            val valueText = TextView(context).apply {
                text = channelValues[index].toString()
                gravity = Gravity.END or Gravity.CENTER_VERTICAL
            }
            val seekBar = SeekBar(context).apply {
                max = 255
                progress = channelValues[index]
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                        channelValues[index] = progress
                        valueText.text = progress.toString()
                        updatePreview()
                    }

                    override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                    override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
                })
            }
            row.addView(seekBar, LinearLayout.LayoutParams(0, dp(48), 1f))
            row.addView(valueText, LinearLayout.LayoutParams(dp(44), dp(48)))
            content.addView(row)
        }
        updatePreview()

        val dialog = DialogUtils.builder(context)
            .setTitle(title)
            .setView(content)
            .setPositiveButton(android.R.string.ok) { _, _ -> onSelected(selectedColor()) }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        dialog.show()
    }

    private fun paletteColors(palette: CustomPalette): List<Int> = listOf(
        Color.parseColor(palette.baseHex),
        Color.parseColor(palette.primaryHex),
        Color.parseColor(palette.accentHex)
    )

    private fun parseColorOrNull(value: String?): Int? {
        return try {
            Color.parseColor(value?.trim().orEmpty())
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private fun formatColor(color: Int): String {
        return if (Color.alpha(color) == 255) {
            String.format(Locale.US, "#%06X", color and 0xFFFFFF)
        } else {
            String.format(Locale.US, "#%08X", color.toLong() and 0xFFFFFFFFL)
        }
    }
}
