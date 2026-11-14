package com.home.cctvtv

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

/** Small helpers for building TV-friendly (big, focusable) views in code. */
object Ui {
    fun dp(ctx: Context, v: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), ctx.resources.displayMetrics).toInt()

    fun title(ctx: Context, text: String) = TextView(ctx).apply {
        this.text = text
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 30f)
        typeface = Typeface.DEFAULT_BOLD
    }

    fun label(ctx: Context, text: String, sizeSp: Float = 16f, color: Int = 0xFF9AA5B1.toInt()) = TextView(ctx).apply {
        this.text = text
        setTextColor(color)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
    }

    fun button(ctx: Context, text: String, onClick: (View) -> Unit) = Button(ctx).apply {
        this.text = text
        isAllCaps = false
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
        setBackgroundResource(R.drawable.bg_button)
        val p = dp(ctx, 14)
        setPadding(p * 2, p, p * 2, p)
        isFocusable = true
        isFocusableInTouchMode = false
        gravity = Gravity.START or Gravity.CENTER_VERTICAL
        stateListAnimator = null
        setOnClickListener(onClick)
    }

    fun input(ctx: Context, hint: String, value: String = "", type: Int = InputType.TYPE_CLASS_TEXT) = EditText(ctx).apply {
        this.hint = hint
        setText(value)
        inputType = type
        setSingleLine(true)
        setTextColor(Color.WHITE)
        setHintTextColor(0xFF6B7682.toInt())
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
        setBackgroundResource(R.drawable.bg_input)
        val p = dp(ctx, 12)
        setPadding(p, p, p, p)
    }

    fun vertical(ctx: Context, paddingDp: Int = 0) = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        val p = dp(ctx, paddingDp)
        setPadding(p, p, p, p)
    }

    fun horizontal(ctx: Context) = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }

    /** LayoutParams with a bottom/right gap. */
    fun lp(ctx: Context, w: Int = ViewGroup.LayoutParams.MATCH_PARENT, h: Int = ViewGroup.LayoutParams.WRAP_CONTENT,
           bottom: Int = 10, right: Int = 0, weight: Float = 0f) =
        LinearLayout.LayoutParams(w, h, weight).apply {
            bottomMargin = dp(ctx, bottom); rightMargin = dp(ctx, right)
        }
}
