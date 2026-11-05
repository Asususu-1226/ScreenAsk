package com.screenask.app

import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Rect
import android.graphics.Typeface
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewParent
import android.view.inputmethod.EditorInfo
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout

/**
 * Shared Material 3 widgets used by the settings screen and the onboarding wizard.
 *
 * Input fields deliberately keep the floating label short and show the explanation
 * as TextInputLayout helper text. Setting a second hint on the EditText itself made
 * the label and the placeholder overlap in the outlined box, which was the main
 * visual bug reported on the first build.
 */
class UiKit(private val activity: AppCompatActivity) {

    fun dp(value: Int): Int = (value * activity.resources.displayMetrics.density).toInt()

    fun color(resId: Int): Int = ContextCompat.getColor(activity, resId)

    fun isDark(): Boolean {
        val mode = activity.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        return mode == Configuration.UI_MODE_NIGHT_YES
    }

    fun themeColor(attr: Int, fallback: Int): Int {
        val typed = TypedValue()
        if (!activity.theme.resolveAttribute(attr, typed, true)) return fallback
        return if (typed.resourceId != 0) ContextCompat.getColor(activity, typed.resourceId) else typed.data
    }

    fun surface(): Int = themeColor(com.google.android.material.R.attr.colorSurface, Color.WHITE)

    fun secondary(): Int = themeColor(
        com.google.android.material.R.attr.colorOnSurfaceVariant,
        if (isDark()) Color.parseColor("#A6ACB8") else Color.parseColor("#6B7280")
    )

    fun outline(): Int = if (isDark()) 0x33FFFFFF else 0x1F000000

    fun brand(): Int = color(R.color.brand)

    fun heading(text: String, size: Float = 15f): TextView = TextView(activity).apply {
        this.text = text
        textSize = size
        setTypeface(typeface, Typeface.BOLD)
        setPadding(0, dp(2), 0, dp(2))
    }

    fun caption(text: String): TextView = TextView(activity).apply {
        this.text = text
        textSize = 12f
        setTextColor(secondary())
        setPadding(0, dp(2), 0, dp(4))
    }

    fun card(parent: LinearLayout, title: String, subtitle: String? = null): LinearLayout {
        val card = MaterialCardView(activity).apply {
            radius = dp(18).toFloat()
            cardElevation = 0f
            strokeWidth = dp(1)
            strokeColor = outline()
            setCardBackgroundColor(surface())
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(12) }
        }
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        card.addView(content)
        content.addView(heading(title, 16f))
        if (!subtitle.isNullOrBlank()) content.addView(caption(subtitle))
        parent.addView(card)
        return content
    }

    /**
     * @param helper explanation shown below the box (never as a second hint)
     * @param number digits only; @param signed allows a leading minus
     * @param decimal decimal number; @param uri URL keyboard
     */
    fun input(
        parent: LinearLayout,
        label: String,
        value: String,
        helper: String = "",
        password: Boolean = false,
        multiline: Boolean = false,
        number: Boolean = false,
        decimal: Boolean = false,
        signed: Boolean = false,
        uri: Boolean = false
    ): EditText {
        val layout = android.view.LayoutInflater.from(activity)
            .inflate(R.layout.view_input, parent, false) as TextInputLayout
        layout.apply {
            hint = label
            isHelperTextEnabled = helper.isNotBlank()
            if (helper.isNotBlank()) helperText = helper
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(10) }
        }
        val edit = layout.findViewById<TextInputEditText>(R.id.input_edit)
        edit.apply {
            setText(value)
            textSize = 14.5f
            when {
                multiline -> {
                    inputType = InputType.TYPE_CLASS_TEXT or
                        InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                        InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                    minLines = 3
                    maxLines = 7
                    gravity = Gravity.TOP or Gravity.START
                    imeOptions = EditorInfo.IME_FLAG_NO_ENTER_ACTION
                    setHorizontallyScrolling(false)
                    isVerticalScrollBarEnabled = true
                    scrollBarStyle = View.SCROLLBARS_INSIDE_INSET
                }
                password -> {
                    inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                    imeOptions = EditorInfo.IME_ACTION_NEXT
                    layout.endIconMode = TextInputLayout.END_ICON_PASSWORD_TOGGLE
                }
                number -> {
                    inputType = InputType.TYPE_CLASS_NUMBER or
                        (if (signed) InputType.TYPE_NUMBER_FLAG_SIGNED else 0)
                    imeOptions = EditorInfo.IME_ACTION_NEXT
                }
                decimal -> {
                    inputType = InputType.TYPE_CLASS_NUMBER or
                        InputType.TYPE_NUMBER_FLAG_DECIMAL or
                        InputType.TYPE_NUMBER_FLAG_SIGNED
                    imeOptions = EditorInfo.IME_ACTION_NEXT
                }
                uri -> {
                    inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
                    imeOptions = EditorInfo.IME_ACTION_DONE
                }
                else -> {
                    inputType = InputType.TYPE_CLASS_TEXT
                    imeOptions = EditorInfo.IME_ACTION_NEXT
                }
            }
        }
        // Keep the focused field visible above the soft keyboard.
        edit.setOnFocusChangeListener { view, hasFocus ->
            if (!hasFocus) return@setOnFocusChangeListener
            view.postDelayed({
                var parentView: ViewParent? = view.parent
                while (parentView != null && parentView !is ScrollView) {
                    parentView = parentView.parent
                }
                val scroll = parentView as? ScrollView ?: return@postDelayed
                val rect = Rect()
                view.getDrawingRect(rect)
                runCatching { scroll.requestChildRectangleOnScreen(view, rect, true) }
            }, 320L)
        }
        parent.addView(layout)
        return edit
    }

    fun switchRow(
        parent: LinearLayout,
        title: String,
        summary: String,
        checked: Boolean,
        onChange: ((Boolean) -> Unit)? = null
    ): MaterialSwitch {
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(10), 0, dp(6))
        }
        val texts = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        texts.addView(heading(title, 14.5f))
        if (summary.isNotBlank()) texts.addView(caption(summary))
        val toggle = MaterialSwitch(activity).apply {
            isChecked = checked
            setOnCheckedChangeListener { _, value -> onChange?.invoke(value) }
        }
        row.addView(texts)
        row.addView(toggle)
        parent.addView(row)
        return toggle
    }

    fun button(
        parent: LinearLayout,
        text: String,
        outlined: Boolean = false,
        onClick: () -> Unit
    ): MaterialButton {
        val style = if (outlined) com.google.android.material.R.attr.materialButtonOutlinedStyle
        else com.google.android.material.R.attr.materialButtonStyle
        val button = MaterialButton(activity, null, style).apply {
            this.text = text
            textSize = 13.5f
            isAllCaps = false
            cornerRadius = dp(12)
            setOnClickListener { onClick() }
        }
        parent.addView(
            button,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(48)
            ).apply { topMargin = dp(10) }
        )
        return button
    }

    fun buttonRow(
        parent: LinearLayout,
        leftText: String,
        rightText: String,
        left: () -> Unit,
        right: () -> Unit
    ) {
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(10) }
        }
        val outlined = com.google.android.material.R.attr.materialButtonOutlinedStyle
        val filled = com.google.android.material.R.attr.materialButtonStyle
        val leftButton = MaterialButton(activity, null, filled).apply {
            text = leftText
            textSize = 13.5f
            isAllCaps = false
            cornerRadius = dp(12)
            setOnClickListener { left() }
        }
        val rightButton = MaterialButton(activity, null, outlined).apply {
            text = rightText
            textSize = 13.5f
            isAllCaps = false
            cornerRadius = dp(12)
            setOnClickListener { right() }
        }
        row.addView(leftButton, LinearLayout.LayoutParams(0, dp(48), 1f))
        row.addView(rightButton, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginStart = dp(8) })
        parent.addView(row)
    }

    fun spinner(
        parent: LinearLayout,
        label: String,
        options: List<String>,
        selected: Int,
        onSelected: ((Int) -> Unit)? = null
    ): Spinner {
        parent.addView(caption(label))
        val spinner = Spinner(activity, Spinner.MODE_DROPDOWN).apply {
            adapter = ArrayAdapter(activity, android.R.layout.simple_spinner_dropdown_item, options)
            setSelection(selected.coerceIn(0, options.size - 1))
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(
                    parentView: AdapterView<*>?,
                    view: View?,
                    position: Int,
                    id: Long
                ) {
                    onSelected?.invoke(position)
                }

                override fun onNothingSelected(parentView: AdapterView<*>?) {
                }
            }
        }
        parent.addView(
            spinner,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48))
        )
        return spinner
    }

    fun divider(parent: LinearLayout) {
        parent.addView(View(activity).apply {
            setBackgroundColor(outline())
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1))
        })
    }
}
