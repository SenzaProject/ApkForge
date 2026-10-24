package com.senza.apkforge

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

object Pal {
    val primary = 0xFF4F46E5.toInt()
    val primaryDark = 0xFF3730A3.toInt()
    val bg = 0xFFF4F5FA.toInt()
    val card = 0xFFFFFFFF.toInt()
    val text = 0xFF1F2937.toInt()
    val sub = 0xFF6B7280.toInt()
    val ok = 0xFF16A34A.toInt()
    val okBg = 0xFFE8F7EE.toInt()
    val err = 0xFFDC2626.toInt()
    val errBg = 0xFFFDECEC.toInt()
    val warn = 0xFFB45309.toInt()
    val warnBg = 0xFFFFF4E0.toInt()
    val editorBg = 0xFF1E1E2E.toInt()
    val editorFg = 0xFFD4D4D4.toInt()
    val chip = 0xFFE8EAF2.toInt()
}

const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

fun Context.shape(fill: Int, radiusDp: Int = 12): GradientDrawable =
    GradientDrawable().apply {
        setColor(fill)
        cornerRadius = dp(radiusDp).toFloat()
    }

fun Context.pressable(fill: Int, radiusDp: Int = 12): Drawable =
    RippleDrawable(ColorStateList.valueOf(0x33000000), shape(fill, radiusDp), shape(Color.WHITE, radiusDp))

fun Context.lp(
    w: Int = MATCH, h: Int = WRAP,
    l: Int = 0, t: Int = 0, r: Int = 0, b: Int = 0,
    weight: Float = 0f
) = LinearLayout.LayoutParams(w, h, weight).apply { setMargins(dp(l), dp(t), dp(r), dp(b)) }

fun Context.label(text: CharSequence, sp: Float = 14f, color: Int = Pal.text, bold: Boolean = false) =
    TextView(this).apply {
        this.text = text
        textSize = sp
        setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

fun Context.button(text: String, fill: Int, fg: Int = Color.WHITE, sp: Float = 16f, onClick: () -> Unit) =
    TextView(this).apply {
        this.text = text
        textSize = sp
        setTextColor(fg)
        setTypeface(typeface, Typeface.BOLD)
        gravity = Gravity.CENTER
        setPadding(dp(16), dp(14), dp(16), dp(14))
        background = pressable(fill, 14)
        isClickable = true
        isFocusable = true
        setOnClickListener { onClick() }
    }

fun Context.card(fill: Int = Pal.card, padDp: Int = 16): LinearLayout =
    LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = shape(fill, 16)
        elevation = dp(2).toFloat()
        setPadding(dp(padDp), dp(padDp), dp(padDp), dp(padDp))
    }

fun EditText.onChanged(cb: (Editable?) -> Unit) = addTextChangedListener(object : TextWatcher {
    override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
    override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
    override fun afterTextChanged(s: Editable?) = cb(s)
})
