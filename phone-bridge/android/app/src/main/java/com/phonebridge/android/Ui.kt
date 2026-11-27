package com.phonebridge.android

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.Dialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * A small UI kit made of plain Views (no extra libraries). The look is the road sign of the app icon:
 * amber and ink on warm paper, light and dark. Colors live in res/values(-night)/colors.xml.
 */

const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

fun Context.dp(v: Number): Int = (v.toFloat() * resources.displayMetrics.density + 0.5f).toInt()
fun View.dp(v: Number): Int = context.dp(v)

/** Adds [v] to a vertical or horizontal box. Sizes are pixels (MATCH, WRAP or dp()); margins are dp. */
fun LinearLayout.add(
    v: View, w: Int = WRAP, h: Int = WRAP, weight: Float = 0f,
    l: Number = 0, t: Number = 0, r: Number = 0, b: Number = 0, gravity: Int = -1,
): LinearLayout {
    val lp = LinearLayout.LayoutParams(w, h, weight)
    lp.setMargins(dp(l), dp(t), dp(r), dp(b))
    if (gravity != -1) lp.gravity = gravity
    addView(v, lp)
    return this
}

/** A hairline between rows. */
fun LinearLayout.addDivider(startDp: Number = 72, endDp: Number = 16): LinearLayout {
    val line = View(context).apply { setBackgroundColor(context.getColor(R.color.stroke)) }
    return add(line, MATCH, maxOf(1, dp(0.6f)), l = startDp, r = endDp)
}

/** A soft press: the view shrinks a little while a finger is on it. */
@Suppress("ClickableViewAccessibility")
fun View.pressScale(to: Float = 0.97f) {
    setOnTouchListener { v, e ->
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> v.animate().scaleX(to).scaleY(to).setDuration(90).start()
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> v.animate().scaleX(1f).scaleY(1f).setDuration(140).start()
        }
        false
    }
}

class Kit(val ctx: Context) {
    fun dp(v: Number): Int = ctx.dp(v)
    fun c(id: Int): Int = ctx.getColor(id)

    enum class Weight { REGULAR, MEDIUM, SEMIBOLD }
    enum class Look { PRIMARY, GHOST, TONAL }
    enum class Tone { DONE, TODO, QUIET }

    private fun face(w: Weight): Typeface = when (w) {
        Weight.REGULAR -> Typeface.DEFAULT
        Weight.MEDIUM -> Typeface.create(Typeface.DEFAULT, 500, false)
        Weight.SEMIBOLD -> Typeface.create(Typeface.DEFAULT, 600, false)
    }

    fun text(s: CharSequence, sp: Float, color: Int, w: Weight = Weight.REGULAR): TextView = TextView(ctx).apply {
        text = s
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        setTextColor(color)
        typeface = face(w)
        includeFontPadding = false
    }

    fun shape(fill: Int, radius: Number, stroke: Int = 0, strokeDp: Number = 1): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(fill)
            cornerRadius = dp(radius).toFloat()
            if (stroke != 0) setStroke(maxOf(1, dp(strokeDp)), stroke)
        }

    fun oval(fill: Int): GradientDrawable = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(fill) }

    /** A background with a touch ripple that keeps the same rounded shape. */
    fun pressable(base: Drawable?, radius: Number): RippleDrawable =
        RippleDrawable(ColorStateList.valueOf(c(R.color.ripple)), base, shape(Color.WHITE, radius))

    fun vbox(): LinearLayout = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
    fun hbox(): LinearLayout = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }

    fun icon(res: Int, tint: Int): ImageView = ImageView(ctx).apply {
        setImageResource(res)
        imageTintList = ColorStateList.valueOf(tint)
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    fun card(): LinearLayout = vbox().apply {
        background = shape(c(R.color.card), 24, c(R.color.stroke))
        clipToOutline = true
    }

    fun pill(s: String, fg: Int, bg: Int): TextView = text(s, 12f, fg, Weight.SEMIBOLD).apply {
        setPadding(dp(10), dp(6), dp(10), dp(6))
        background = shape(bg, 12)
    }

    /** A chip on the dark hero card. */
    fun chip(iconRes: Int, label: String): LinearLayout = hbox().apply {
        background = shape(0x1CFFFFFF, 15)
        minimumHeight = dp(30)
        setPadding(dp(10), 0, dp(12), 0)
        add(icon(iconRes, 0xD9EFEADC.toInt()), dp(15), dp(15))
        add(text(label, 12.5f, 0xFFEFEADC.toInt(), Weight.MEDIUM).apply {
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END; maxWidth = dp(210)
        }, l = 6)
    }

    /** A full width button with an icon and a label. */
    inner class Button {
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = true
        }
        private val ic = ImageView(ctx).apply { importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }
        private val label = text("", 16.5f, c(R.color.ink), Weight.SEMIBOLD)

        init {
            root.add(ic, dp(22), dp(22))
            root.add(label, l = 8)
            root.pressScale(0.98f)
        }

        private var sig = ""

        fun bind(text: String, iconRes: Int?, look: Look) {
            val next = "$text|$iconRes|$look"
            if (next == sig) return // keep the ripple alive while the screen refreshes
            sig = next
            val (bg, fg) = when (look) {
                Look.PRIMARY -> c(R.color.amber) to c(R.color.ink)
                Look.GHOST -> 0x24FFFFFF to Color.WHITE
                Look.TONAL -> c(R.color.field) to c(R.color.text)
            }
            root.background = pressable(shape(bg, 18), 18)
            label.setTextColor(fg)
            label.text = text
            if (iconRes == null) ic.visibility = View.GONE else {
                ic.visibility = View.VISIBLE
                ic.setImageResource(iconRes)
                ic.imageTintList = ColorStateList.valueOf(fg)
            }
        }
    }

    /** One line of the checklist: a round badge, a title with a sub line, and a button or a chevron. */
    inner class Row {
        val root = hbox().apply {
            minimumHeight = dp(68)
            setPadding(dp(16), dp(12), dp(14), dp(12))
            background = pressable(null, 0)
            isClickable = true
            isFocusable = true
        }
        private val badge = FrameLayout(ctx)
        private val badgeIcon = ImageView(ctx).apply { importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }
        private val title = text("", 16f, c(R.color.text), Weight.SEMIBOLD)
        private val sub = text("", 13f, c(R.color.text2)).apply { setLineSpacing(0f, 1.12f) }
        private val action = text("", 13.5f, c(R.color.ink), Weight.SEMIBOLD).apply {
            gravity = Gravity.CENTER
            setSingleLine()
            minHeight = dp(36)
            minWidth = dp(72)
            setPadding(dp(18), 0, dp(18), 0)
            background = shape(c(R.color.amber), 18)
        }
        val chevron = icon(R.drawable.ic_chevron, c(R.color.text3))
        private var sig = ""

        init {
            badge.addView(badgeIcon, FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER))
            root.add(badge, dp(42), dp(42))
            val mid = vbox()
            mid.add(title)
            mid.add(sub, t = 3)
            root.add(mid, 0, WRAP, 1f, l = 14, r = 10)
            root.add(action, WRAP, WRAP)
            root.add(chevron, dp(22), dp(22))
        }

        fun bind(
            tone: Tone, iconRes: Int, t: String, s: String,
            actionText: String? = null, chevronOn: Boolean = false,
        ) {
            val next = "$tone|$iconRes"
            if (next != sig) {
                sig = next
                val (bg, fg, ic) = when (tone) {
                    Tone.DONE -> Triple(c(R.color.ok_soft), c(R.color.ok), R.drawable.ic_check)
                    Tone.TODO -> Triple(c(R.color.amber_soft), c(R.color.amber_ink), iconRes)
                    Tone.QUIET -> Triple(c(R.color.field), c(R.color.text2), iconRes)
                }
                badge.background = oval(bg)
                badgeIcon.setImageResource(ic)
                badgeIcon.imageTintList = ColorStateList.valueOf(fg)
            }
            if (title.text.toString() != t) title.text = t
            if (sub.text.toString() != s) sub.text = s
            if (actionText != null) {
                if (action.text.toString() != actionText) action.text = actionText
                action.visibility = View.VISIBLE
                chevron.visibility = View.GONE
            } else {
                action.visibility = View.GONE
                chevron.visibility = if (chevronOn) View.VISIBLE else View.GONE
            }
        }
    }

    /** A text input in a soft field with a small label; the field gets an amber ring while it has focus. */
    fun field(label: String, current: String, hint: String, input: Int, done: android.widget.EditText.() -> Unit = {}): Pair<LinearLayout, android.widget.EditText> {
        val box = vbox().apply {
            background = shape(c(R.color.field), 16)
            setPadding(dp(14), dp(10), dp(14), dp(10))
        }
        box.add(text(label, 12f, c(R.color.text2), Weight.MEDIUM))
        val edit = android.widget.EditText(ctx).apply {
            setText(current)
            this.hint = hint
            setSingleLine()
            background = null
            setPadding(0, dp(3), 0, dp(2))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setTextColor(c(R.color.text))
            setHintTextColor(c(R.color.text3))
            inputType = input
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_DONE
            done()
        }
        box.add(edit, MATCH)
        edit.setOnFocusChangeListener { _, focus ->
            box.background = shape(c(R.color.field), 16, if (focus) c(R.color.amber) else 0, 2)
        }
        return box to edit
    }

    /** A numbered step for the guides. */
    fun step(n: Int, title: String, body: String): LinearLayout = hbox().apply {
        gravity = Gravity.TOP
        val num = text(n.toString(), 13.5f, c(R.color.amber_ink), Weight.SEMIBOLD).apply {
            gravity = Gravity.CENTER
            background = oval(c(R.color.amber_soft))
        }
        add(num, dp(28), dp(28))
        val col = vbox()
        col.add(text(title, 15.5f, c(R.color.text), Weight.SEMIBOLD))
        col.add(text(body, 13.5f, c(R.color.text2)).apply { setLineSpacing(0f, 1.15f) }, t = 3)
        add(col, 0, WRAP, 1f, l = 14)
    }

    /** A grey line of live status inside a sheet. */
    fun strip(s: String): TextView = text(s, 13.5f, c(R.color.text2)).apply {
        background = shape(c(R.color.field), 14)
        setPadding(dp(14), dp(12), dp(14), dp(12))
        setLineSpacing(0f, 1.15f)
    }

    /** A text-only button for the quiet actions in a sheet. */
    fun textButton(s: String, onClick: () -> Unit): TextView = text(s, 14.5f, c(R.color.amber_ink), Weight.SEMIBOLD).apply {
        gravity = Gravity.CENTER
        setPadding(dp(12), dp(12), dp(12), dp(12))
        background = pressable(null, 14)
        isClickable = true
        setOnClickListener { onClick() }
    }

    /** A bottom sheet. [build] fills the body under the title; it gets the dialog to close it. */
    fun sheet(title: String, subtitle: String? = null, build: LinearLayout.(Dialog) -> Unit): Dialog {
        val dlg = Dialog(ctx, R.style.Sheet)
        val r = dp(28).toFloat()
        val root = vbox().apply {
            background = GradientDrawable().apply {
                setColor(c(R.color.card))
                cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
            }
        }
        root.add(View(ctx).apply { background = shape(c(R.color.stroke), 2) }, dp(40), dp(4), t = 10, b = 2, gravity = Gravity.CENTER_HORIZONTAL)
        val body = vbox().apply { setPadding(dp(22), dp(14), dp(22), dp(22)) }
        body.add(text(title, 22f, c(R.color.text), Weight.SEMIBOLD))
        if (subtitle != null) body.add(text(subtitle, 14f, c(R.color.text2)).apply { setLineSpacing(0f, 1.15f) }, t = 6)
        val scroll = MaxHeightScroll(ctx, (ctx.resources.displayMetrics.heightPixels * 0.86f).toInt()).apply {
            isVerticalScrollBarEnabled = false
            addView(body, MATCH, WRAP)
        }
        root.addView(scroll, MATCH, WRAP)
        dlg.setContentView(root, ViewGroup.LayoutParams(MATCH, WRAP))
        dlg.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(MATCH, WRAP)
            setGravity(Gravity.BOTTOM)
            setDimAmount(0.55f)
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
        body.build(dlg)
        dlg.show()
        return dlg
    }

    /** Fade and lift the sections in one after another. */
    fun stagger(views: List<View>) {
        views.forEachIndexed { i, v ->
            v.alpha = 0f
            v.translationY = dp(18).toFloat()
            v.animate().alpha(1f).translationY(0f).setStartDelay(i * 45L).setDuration(340)
                .setInterpolator(DecelerateInterpolator(1.6f)).start()
        }
    }
}

/** A message that slides up from the bottom of the screen and goes away by itself. */
class Snack(private val k: Kit, host: FrameLayout) {
    private val view = k.text("", 14.5f, k.c(R.color.bg), Kit.Weight.MEDIUM).apply {
        background = k.shape(k.c(R.color.text), 16)
        setPadding(k.dp(18), k.dp(14), k.dp(18), k.dp(14))
        maxLines = 5
        elevation = k.dp(8).toFloat()
        setLineSpacing(0f, 1.1f)
        alpha = 0f
        visibility = View.GONE
    }
    private val hide = Runnable {
        view.animate().alpha(0f).translationY(k.dp(16).toFloat()).setDuration(200)
            .withEndAction { view.visibility = View.GONE }.start()
    }

    init {
        host.addView(view, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
            .apply { setMargins(k.dp(20), 0, k.dp(20), k.dp(28)) })
    }

    fun show(message: String, ms: Long = 4200) {
        view.removeCallbacks(hide)
        view.animate().cancel()
        view.text = message
        if (view.visibility != View.VISIBLE) {
            view.visibility = View.VISIBLE
            view.alpha = 0f
            view.translationY = k.dp(16).toFloat()
        }
        view.animate().alpha(1f).translationY(0f).setDuration(220).setInterpolator(DecelerateInterpolator()).start()
        view.postDelayed(hide, ms)
    }
}

/** The dark card at the top: ink with an amber glow and the road-sign diamond of the app icon. */
class HeroDrawable(ctx: Context) : Drawable() {
    private val d = ctx.resources.displayMetrics.density
    private val radius = 28 * d
    private val base = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ctx.getColor(R.color.hero) }
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = d
        color = ctx.getColor(R.color.hero_stroke)
    }
    private val clip = Path()

    override fun onBoundsChange(b: Rect) {
        glow.shader = RadialGradient(
            b.right - 80 * d, b.top + 60 * d, 150 * d,
            intArrayOf(0x6BFBB21C, 0x00FBB21C), null, Shader.TileMode.CLAMP,
        )
        clip.reset()
        clip.addRoundRect(RectF(b), radius, radius, Path.Direction.CW)
    }

    override fun draw(canvas: Canvas) {
        val b = RectF(bounds)
        canvas.save()
        canvas.clipPath(clip)
        canvas.drawRect(b, base)
        canvas.drawRect(b, glow)
        val cx = b.right - 40 * d
        val cy = b.top + 40 * d
        canvas.save()
        canvas.rotate(45f, cx, cy)
        line.strokeWidth = 3 * d
        line.color = 0x8CFBB21C.toInt()
        canvas.drawRoundRect(RectF(cx - 66 * d, cy - 66 * d, cx + 66 * d, cy + 66 * d), 22 * d, 22 * d, line)
        line.strokeWidth = 2 * d
        line.color = 0x4DFBB21C
        canvas.drawRoundRect(RectF(cx - 46 * d, cy - 46 * d, cx + 46 * d, cy + 46 * d), 16 * d, 16 * d, line)
        canvas.restore()
        canvas.restore()
        val edge = RectF(b).apply { inset(d / 2, d / 2) }
        canvas.drawRoundRect(edge, radius, radius, border)
    }

    override fun getOutline(outline: Outline) { outline.setRoundRect(bounds, radius) }
    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: ColorFilter?) {}
    @Suppress("OVERRIDE_DEPRECATION")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

/** A status dot that breathes while the bridge is on. */
class PulseDot(ctx: Context) : View(ctx) {
    var color: Int = Color.GRAY
        set(v) { field = v; invalidate() }
    var pulsing: Boolean = false
        set(v) { if (field != v) { field = v; update() } }

    private val core = Paint(Paint.ANTI_ALIAS_FLAG)
    private val halo = Paint(Paint.ANTI_ALIAS_FLAG)
    private var t = 0f
    private val anim = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 1700
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener { t = it.animatedFraction; invalidate() }
    }

    private fun update() {
        if (pulsing && isAttachedToWindow) { if (!anim.isStarted) anim.start() } else { anim.cancel(); t = 0f; invalidate() }
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); update() }
    override fun onDetachedFromWindow() { anim.cancel(); super.onDetachedFromWindow() }

    override fun onMeasure(w: Int, h: Int) { val s = dp(22); setMeasuredDimension(s, s) }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = dp(4.5f).toFloat()
        halo.color = color
        if (pulsing) {
            halo.alpha = ((1f - t) * 110).toInt()
            canvas.drawCircle(cx, cy, r + t * dp(6.5f), halo)
        } else {
            halo.alpha = 56
            canvas.drawCircle(cx, cy, r + dp(4), halo)
        }
        core.color = color
        canvas.drawCircle(cx, cy, r, core)
    }
}

/** A thin progress bar that eases to its new value. */
class Bar(ctx: Context) : View(ctx) {
    var fraction: Float = 0f
        set(v) { field = v.coerceIn(0f, 1f); invalidate() }
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ctx.getColor(R.color.field) }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ctx.getColor(R.color.amber) }
    private var target = -1f

    fun animateTo(f: Float) {
        if (f == target) return
        target = f
        ObjectAnimator.ofFloat(this, "fraction", fraction, f).setDuration(380).start()
    }

    override fun onDraw(canvas: Canvas) {
        val h = height.toFloat()
        canvas.drawRoundRect(0f, 0f, width.toFloat(), h, h / 2, h / 2, track)
        if (fraction > 0f) canvas.drawRoundRect(0f, 0f, width * fraction, h, h / 2, h / 2, fill)
    }
}

/** Lays children out left to right and wraps them to the next line. */
class Flow(ctx: Context, private val hGap: Int, private val vGap: Int) : ViewGroup(ctx) {
    override fun onMeasure(wSpec: Int, hSpec: Int) {
        val avail = MeasureSpec.getSize(wSpec) - paddingLeft - paddingRight
        var x = 0
        var y = 0
        var rowH = 0
        var any = false
        for (i in 0 until childCount) {
            val v = getChildAt(i)
            if (v.visibility == View.GONE) continue
            any = true
            v.measure(MeasureSpec.makeMeasureSpec(avail, MeasureSpec.AT_MOST), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
            if (x > 0 && x + v.measuredWidth > avail) { x = 0; y += rowH + vGap; rowH = 0 }
            x += v.measuredWidth + hGap
            rowH = maxOf(rowH, v.measuredHeight)
        }
        val h = if (any) y + rowH else 0
        setMeasuredDimension(resolveSize(avail + paddingLeft + paddingRight, wSpec), h + paddingTop + paddingBottom)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val avail = r - l - paddingLeft - paddingRight
        var x = 0
        var y = 0
        var rowH = 0
        for (i in 0 until childCount) {
            val v = getChildAt(i)
            if (v.visibility == View.GONE) continue
            if (x > 0 && x + v.measuredWidth > avail) { x = 0; y += rowH + vGap; rowH = 0 }
            val left = paddingLeft + x
            val top = paddingTop + y
            v.layout(left, top, left + v.measuredWidth, top + v.measuredHeight)
            x += v.measuredWidth + hGap
            rowH = maxOf(rowH, v.measuredHeight)
        }
    }
}

/** A ScrollView that never grows taller than [maxPx]. */
class MaxHeightScroll(ctx: Context, private val maxPx: Int) : ScrollView(ctx) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val cap = minOf(maxPx, MeasureSpec.getSize(heightMeasureSpec).takeIf { it > 0 } ?: maxPx)
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(cap, MeasureSpec.AT_MOST))
    }
}
