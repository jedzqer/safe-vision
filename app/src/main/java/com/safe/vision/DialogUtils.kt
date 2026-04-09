package com.safe.vision

import android.content.res.ColorStateList
import android.content.Context
import android.util.TypedValue
import android.widget.Button
import android.widget.CheckedTextView
import android.widget.CheckBox
import android.widget.CompoundButton
import android.widget.RadioButton
import android.widget.ListView
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import androidx.annotation.AttrRes
import androidx.appcompat.app.AlertDialog
import androidx.core.graphics.drawable.DrawableCompat
import androidx.core.content.ContextCompat
import androidx.core.widget.CompoundButtonCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputLayout

object DialogUtils {
    fun builder(context: Context): MaterialAlertDialogBuilder = SafeVisionMaterialAlertDialogBuilder(context)

    fun resolveThemeColor(context: Context, @AttrRes attr: Int): Int {
        val typedValue = TypedValue()
        val resolved = context.theme.resolveAttribute(attr, typedValue, true)
        if (!resolved) return 0
        return if (typedValue.type in TypedValue.TYPE_FIRST_COLOR_INT..TypedValue.TYPE_LAST_COLOR_INT) {
            typedValue.data
        } else {
            ContextCompat.getColor(context, typedValue.resourceId)
        }
    }

    fun styleTextView(textView: TextView, @AttrRes attr: Int = R.attr.svColorTextPrimary) {
        textView.setTextColor(resolveThemeColor(textView.context, attr))
    }

    fun styleEditText(editText: EditText) {
        editText.setTextColor(resolveThemeColor(editText.context, R.attr.svColorTextPrimary))
        editText.setHintTextColor(resolveThemeColor(editText.context, R.attr.svColorTextSecondary))
        editText.highlightColor = resolveThemeColor(editText.context, R.attr.svColorChip)
    }

    fun ensureDialogLayoutParams(view: View) {
        if (view.layoutParams == null) {
            view.layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
    }

    fun styleShownDialog(dialog: AlertDialog) {
        val context = dialog.context
        val primary = resolveThemeColor(context, R.attr.svColorTextPrimary)
        val secondary = resolveThemeColor(context, R.attr.svColorTextSecondary)
        val accent = resolveThemeColor(context, R.attr.svColorAccent)

        dialog.findViewById<TextView>(android.R.id.message)?.setTextColor(secondary)
        dialog.findViewById<TextView>(androidx.appcompat.R.id.alertTitle)?.setTextColor(primary)

        styleDialogButton(dialog.getButton(AlertDialog.BUTTON_POSITIVE), accent)
        styleDialogButton(dialog.getButton(AlertDialog.BUTTON_NEGATIVE), accent)
        styleDialogButton(dialog.getButton(AlertDialog.BUTTON_NEUTRAL), accent)

        dialog.listView?.let { listView ->
            styleDialogList(listView, primary, secondary, accent)
            listView.post {
                styleDialogList(listView, primary, secondary, accent)
            }
        }

        dialog.window?.decorView?.let { decorView ->
            styleDialogViewTree(decorView, primary, secondary, accent)
        }
    }

    private fun styleDialogButton(button: Button?, color: Int) {
        button?.setTextColor(color)
    }

    private fun styleDialogList(listView: ListView, primary: Int, secondary: Int, accent: Int) {
        for (index in 0 until listView.childCount) {
            when (val child = listView.getChildAt(index)) {
                is CheckedTextView -> {
                    child.setTextColor(primary)
                    tintCheckedTextViewDrawables(child, secondary, accent)
                }
                is TextView -> child.setTextColor(primary)
            }
        }
    }

    private fun tintCheckedTextViewDrawables(view: CheckedTextView, normal: Int, checked: Int) {
        val tint = ColorStateList(
            arrayOf(
                intArrayOf(android.R.attr.state_checked),
                intArrayOf()
            ),
            intArrayOf(checked, normal)
        )
        val drawables = view.compoundDrawablesRelative
        drawables.forEach { drawable ->
            drawable ?: return@forEach
            val wrapped = DrawableCompat.wrap(drawable.mutate())
            DrawableCompat.setTintList(wrapped, tint)
        }
        view.setCompoundDrawablesRelativeWithIntrinsicBounds(
            drawables[0],
            drawables[1],
            drawables[2],
            drawables[3]
        )
    }

    private fun styleDialogViewTree(view: View, primary: Int, secondary: Int, accent: Int) {
        when (view) {
            is Button -> return
            is TextInputLayout -> {
                val hintColors = ColorStateList.valueOf(primary)
                view.defaultHintTextColor = hintColors
                view.hintTextColor = hintColors
            }
            is EditText -> styleEditText(view)
            is RadioButton -> styleCompoundButton(view, primary, accent)
            is CheckBox -> styleCompoundButton(view, primary, accent)
            is CheckedTextView -> {
                view.setTextColor(primary)
                tintCheckedTextViewDrawables(view, secondary, accent)
            }
            is TextView -> {
                if (view.id != android.R.id.message) {
                    view.setTextColor(primary)
                }
            }
        }
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                styleDialogViewTree(view.getChildAt(index), primary, secondary, accent)
            }
        }
    }

    private fun styleCompoundButton(button: CompoundButton, textColor: Int, accent: Int) {
        button.setTextColor(textColor)
        CompoundButtonCompat.setButtonTintList(button, ColorStateList.valueOf(accent))
    }
}

private class SafeVisionMaterialAlertDialogBuilder(context: Context) : MaterialAlertDialogBuilder(context) {
    override fun create(): AlertDialog {
        val dialog = super.create()
        dialog.setOnShowListener {
            DialogUtils.styleShownDialog(dialog)
        }
        return dialog
    }
}
