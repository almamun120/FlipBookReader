package com.bookflip.reader.views

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ScaleGestureDetector
import android.view.ViewConfiguration
import android.view.animation.DecelerateInterpolator
import android.view.animation.AccelerateDecelerateInterpolator
import android.util.LruCache
import com.bookflip.reader.utils.PdfPageProvider
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * বাস্তব বইয়ের মতো PDF পেজ-কার্ল ভিউ।
 *
 *  • আঙুলে টেনে পাতা ওল্টানো — ভাঁজ-রেখা (fold line) জ্যামিতিকভাবে হিসাব করে পাতার পিছনের দিক,
 *    ভাঁজের ছায়া/হাইলাইট, নিচের পাতার উপর ক্যাস্ট-শ্যাডো আঁকা হয়।
 *  • পোর্ট্রেটে এক পাতা, ল্যান্ডস্কেপে (ঐচ্ছিক) দুই-পাতার খোলা বই — মাঝখানে স্পাইন-শ্যাডো,
 *    দুই পাশে পাতার স্তূপের কিনারা।
 *  • পিঞ্চ-জুম + প্যান (জুমে হাই-রেজ রি-রেন্ডার), ট্যাপে পাতা/মেনু।
 *
 * মডেল: "স্প্রেড" ইনডেক্স। একক-পাতা মোডে স্প্রেড = পাতা। দুই-পাতা মোডে স্প্রেড 0 = [ফাঁকা | পাতা0],
 * স্প্রেড s = [পাতা(2s-1) | পাতা(2s)]। প্রতিটি টার্ন = ডান লিফের টার্ন k → k+1।
 */
class PageCurlView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    interface Listener {
        fun onPageChanged(page: Int, pageCount: Int)
        fun onSingleTap()
        fun onTurnCommitted(forward: Boolean)
        fun onReachedEnd()
    }

    var listener: Listener? = null

    var twoPageMode: Boolean = false
        set(value) {
            if (field == value) return
            val page = currentPage
            field = value
            index = spreadOfPage(page)
            relayout()
        }

    var nightMode: Boolean = false
        set(value) { field = value; updateFilters(); invalidate() }

    var deskColor: Int = 0xFF2B2119.toInt()
        set(value) { field = value; invalidate() }

    // ── PDF state ────────────────────────────────────────────────────────
    private var provider: PdfPageProvider? = null
    private var pageCount = 0
    private var index = 0                       // বর্তমান স্প্রেড

    val currentPage: Int get() = pageOfSpread(index)
    val totalPages: Int get() = pageCount

    private val spreadCount: Int
        get() = if (pageCount == 0) 0 else if (twoPageMode) pageCount / 2 + 1 else pageCount

    private fun leftFace(s: Int): Int = if (twoPageMode && s > 0) 2 * s - 1 else -1
    private fun rightFace(s: Int): Int {
        if (s < 0) return -1
        val p = if (twoPageMode) 2 * s else s
        return if (p < pageCount) p else -1
    }
    private fun spreadOfPage(page: Int): Int =
        if (twoPageMode) (if (page <= 0) 0 else (page + 1) / 2) else page.coerceIn(0, max(0, pageCount - 1))
    private fun pageOfSpread(s: Int): Int =
        if (twoPageMode) (if (s == 0) 0 else 2 * s - 1) else s

    // ── geometry ─────────────────────────────────────────────────────────
    private val density = resources.displayMetrics.density
    private fun dp(v: Float) = v * density

    private val rightRect = RectF()
    private val leftRect = RectF()
    private val bookRect = RectF()
    private var bmpW = 0
    private var bmpH = 0
    private var generation = 0

    // ── bitmap cache ─────────────────────────────────────────────────────
    private val cache = object : LruCache<Int, Bitmap>(cacheBytes()) {
        override fun sizeOf(key: Int, value: Bitmap) = value.byteCount
    }
    private val hiCache = object : LruCache<Int, Bitmap>(cacheBytes() / 2) {
        override fun sizeOf(key: Int, value: Bitmap) = value.byteCount
    }
    private val inflight = HashSet<Int>()
    private val inflightHi = HashSet<Int>()

    private fun cacheBytes(): Int {
        val max = Runtime.getRuntime().maxMemory()
        return min(max / 4, 110L * 1024 * 1024).toInt().coerceAtLeast(16 * 1024 * 1024)
    }

    // ── paints ───────────────────────────────────────────────────────────
    private val pagePaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val paperPaint = Paint().apply { color = 0xFFF8F2E4.toInt() }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shadePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tmpMatrix = Matrix()
    private val tmpPath = Path()
    private val flapPath = Path()
    private val flatPath = Path()
    private val revealPath = Path()

    // ── turn state ───────────────────────────────────────────────────────
    private var turnK = -1                      // কোন স্প্রেড থেকে কোন স্প্রেডে (k → k+1)
    private var turnP = 0f                      // 0 = সমতল, 1 = পুরো উল্টানো
    private var originTop = false
    private var yShift = 0f
    private var turnAnimator: ValueAnimator? = null

    // ── zoom ─────────────────────────────────────────────────────────────
    private var zoom = 1f
    private var panX = 0f
    private var panY = 0f
    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(d: ScaleGestureDetector): Boolean {
            val sf = d.scaleFactor
            val newZoom = (zoom * sf).coerceIn(1f, 4f)
            val eff = newZoom / zoom
            val cx = width / 2f
            val cy = height / 2f
            panX = (d.focusX - cx) * (1 - eff) + panX * eff
            panY = (d.focusY - cy) * (1 - eff) + panY * eff
            zoom = newZoom
            clampPan()
            invalidate()
            return true
        }
        override fun onScaleEnd(d: ScaleGestureDetector) {
            if (zoom < 1.03f) { zoom = 1f; panX = 0f; panY = 0f }
            scheduleHiRes()
            invalidate()
        }
    })

    // ── touch ────────────────────────────────────────────────────────────
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var dragging = false
    private var blocked = false
    private var dragForward = true
    private var moved = false
    private var velocity: VelocityTracker? = null
    private var pendingTap: Runnable? = null
    private var lastTapTime = 0L

    init {
        isClickable = true
        isFocusable = true
        updateFilters()
    }

    // ═════════════════════════════════════════════════════════════════════
    //  Public API
    // ═════════════════════════════════════════════════════════════════════

    fun setPdf(p: PdfPageProvider?, startPage: Int) {
        provider = p
        pageCount = p?.pageCount ?: 0
        cache.evictAll(); hiCache.evictAll(); inflight.clear(); inflightHi.clear()
        generation++
        zoom = 1f; panX = 0f; panY = 0f
        index = spreadOfPage(startPage.coerceIn(0, max(0, pageCount - 1)))
        relayout()
        listener?.onPageChanged(currentPage, pageCount)
    }

    fun goToPage(page: Int) {
        if (pageCount == 0) return
        cancelTurn()
        val target = spreadOfPage(page.coerceIn(0, pageCount - 1))
        if (target == index) return
        index = target
        resetZoom()
        prefetch()
        invalidate()
        listener?.onPageChanged(currentPage, pageCount)
    }

    fun next() { if (zoom <= 1.02f) animateProgrammatic(forward = true) }
    fun prev() { if (zoom <= 1.02f) animateProgrammatic(forward = false) }

    fun release() {
        cancelTurn()
        removeCallbacks(hiResRunnable)
        provider = null
        cache.evictAll(); hiCache.evictAll()
        inflight.clear(); inflightHi.clear()
        generation++
    }

    fun resetZoom() { zoom = 1f; panX = 0f; panY = 0f }

    // ═════════════════════════════════════════════════════════════════════
    //  Layout
    // ═════════════════════════════════════════════════════════════════════

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        relayout()
    }

    private fun relayout() {
        if (width == 0 || height == 0) return
        val aspect = provider?.aspect ?: 0.707f
        val slots = if (twoPageMode) 2 else 1
        val padH = dp(18f)
        val padV = dp(22f)
        val areaW = width - 2 * padH
        val areaH = height - 2 * padV
        var leafH = min(areaH, areaW / slots / aspect)
        var leafW = leafH * aspect
        if (leafW <= 0f || leafH <= 0f) { leafW = 1f; leafH = 1f }
        val spreadW = leafW * slots
        val left = (width - spreadW) / 2f
        val top = (height - leafH) / 2f
        rightRect.set(left + if (twoPageMode) leafW else 0f, top, left + spreadW, top + leafH)
        leftRect.set(left, top, left + leafW, top + leafH)
        bookRect.set(left, top, left + spreadW, top + leafH)

        val q = 1.35f
        val nw = (leafW * q).toInt().coerceAtLeast(1)
        val nh = (leafH * q).toInt().coerceAtLeast(1)
        if (nw != bmpW || nh != bmpH) {
            bmpW = nw; bmpH = nh
            cache.evictAll(); hiCache.evictAll()
            inflight.clear(); inflightHi.clear()
            generation++
        }
        cancelTurn()
        resetZoom()
        prefetch()
        invalidate()
    }

    private fun updateFilters() {
        val sr = 0.985f; val sg = 0.965f; val sb = 0.915f          // কাগজের উষ্ণ রং (ক্রিম)
        val cm = if (nightMode) {
            ColorMatrix(floatArrayOf(
                -sr * 0.82f, 0f, 0f, 0f, 255f * sr * 0.82f,
                0f, -sg * 0.82f, 0f, 0f, 255f * sg * 0.82f,
                0f, 0f, -sb * 0.82f, 0f, 255f * sb * 0.82f,
                0f, 0f, 0f, 1f, 0f
            ))
        } else {
            ColorMatrix(floatArrayOf(
                sr, 0f, 0f, 0f, 0f,
                0f, sg, 0f, 0f, 0f,
                0f, 0f, sb, 0f, 0f,
                0f, 0f, 0f, 1f, 0f
            ))
        }
        pagePaint.colorFilter = ColorMatrixColorFilter(cm)
        paperPaint.color = if (nightMode) 0xFF24211C.toInt() else 0xFFF8F2E4.toInt()
    }

    // ═════════════════════════════════════════════════════════════════════
    //  Rendering requests
    // ═════════════════════════════════════════════════════════════════════

    private fun request(page: Int) {
        val p = provider ?: return
        if (page < 0 || page >= pageCount || cache.get(page) != null || !inflight.add(page)) return
        val gen = generation
        p.render(page, bmpW, bmpH) { bmp ->
            inflight.remove(page)
            if (bmp != null && gen == generation) {
                cache.put(page, bmp)
                invalidate()
            }
        }
    }

    private fun requestHi(page: Int) {
        val p = provider ?: return
        if (page < 0 || page >= pageCount || hiCache.get(page) != null || !inflightHi.add(page)) return
        val gen = generation
        val factor = (zoom * 1.15f).coerceIn(1.6f, 3.2f)
        var w = (rightRect.width() * factor).toInt()
        var h = (rightRect.height() * factor).toInt()
        val maxDim = 3000f
        val s = min(1f, maxDim / max(w, h).toFloat())
        w = (w * s).toInt().coerceAtLeast(1); h = (h * s).toInt().coerceAtLeast(1)
        p.render(page, w, h) { bmp ->
            inflightHi.remove(page)
            if (bmp != null && gen == generation) { hiCache.put(page, bmp); invalidate() }
        }
    }

    private val hiResRunnable = Runnable {
        if (zoom > 1.1f && turnK < 0) {
            requestHi(rightFace(index)); requestHi(leftFace(index))
        }
    }
    private fun scheduleHiRes() { removeCallbacks(hiResRunnable); postDelayed(hiResRunnable, 220) }

    private fun prefetch() {
        // বর্তমান স্প্রেড আগে, তারপর পরেরটা, তারপর আগেরটা
        request(rightFace(index)); request(leftFace(index))
        if (index + 1 < spreadCount) { request(rightFace(index + 1)); request(leftFace(index + 1)) }
        if (index - 1 >= 0) { request(rightFace(index - 1)); request(leftFace(index - 1)) }
        if (index + 2 < spreadCount) request(rightFace(index + 2))
    }

    // ═════════════════════════════════════════════════════════════════════
    //  Drawing
    // ═════════════════════════════════════════════════════════════════════

    override fun onDraw(c: Canvas) {
        c.drawColor(deskColor)
        if (pageCount == 0 || bookRect.isEmpty) return

        c.save()
        if (zoom > 1f) {
            c.translate(width / 2f + panX, height / 2f + panY)
            c.scale(zoom, zoom)
            c.translate(-width / 2f, -height / 2f)
        }

        drawBookBase(c)

        val k = turnK
        if (k >= 0 && turnP > 0.002f && turnP < 0.998f) {
            drawTurning(c, k, turnP)
        } else {
            val s = when {
                k >= 0 && turnP >= 0.998f -> k + 1
                k >= 0 -> k
                else -> index
            }
            drawStatic(c, s)
        }
        drawSpine(c)
        c.restore()
    }

    private fun faceBitmap(page: Int): Bitmap? {
        if (page < 0) return null
        if (zoom > 1.1f) hiCache.get(page)?.let { return it }
        val b = cache.get(page)
        if (b == null) request(page)
        return b
    }

    private fun drawFace(c: Canvas, page: Int, dst: RectF) {
        if (page < 0) return
        val bmp = faceBitmap(page)
        if (bmp != null) c.drawBitmap(bmp, null, dst, pagePaint) else c.drawRect(dst, paperPaint)
    }

    private fun drawStatic(c: Canvas, s: Int) {
        if (twoPageMode) drawFace(c, leftFace(s), leftRect)
        drawFace(c, rightFace(s), rightRect)
    }

    /** ডেস্কের ছায়া + দুই পাশের পাতা-স্তূপের কিনারা */
    private fun drawBookBase(c: Canvas) {
        // ড্রপ শ্যাডো (স্তরে স্তরে নরম)
        fillPaint.style = Paint.Style.FILL
        for (i in 8 downTo 1) {
            fillPaint.color = Color.argb(13, 0, 0, 0)
            val g = dp(1.4f) * i
            c.drawRoundRect(
                bookRect.left - g + dp(2f), bookRect.top - g + dp(5f),
                bookRect.right + g + dp(2f), bookRect.bottom + g + dp(5f),
                dp(4f) + g, dp(4f) + g, fillPaint
            )
        }
        // কভারের কিনারা (বইয়ের মলাট)
        fillPaint.color = if (nightMode) 0xFF3A2A1E.toInt() else 0xFF6B4A32.toInt()
        c.drawRoundRect(bookRect.left - dp(3f), bookRect.top - dp(3f), bookRect.right + dp(3f), bookRect.bottom + dp(3f), dp(3f), dp(3f), fillPaint)

        // পাতার স্তূপ
        val remainingRight = max(0, spreadCount - 1 - index)
        val readLeft = index
        val rightLines = min(5, (remainingRight + 1) / 2)
        val leftLines = if (twoPageMode) min(5, (readLeft + 1) / 2) else 0
        edgePaint.style = Paint.Style.FILL
        for (i in 1..rightLines) {
            edgePaint.color = if (i % 2 == 0) 0xFFEDE5D2.toInt() else 0xFFD9CFB8.toInt()
            val inset = dp(1.2f) * i
            c.drawRect(rightRect.right + dp(1.1f) * (i - 1), rightRect.top + inset, rightRect.right + dp(1.1f) * i, rightRect.bottom - inset * 0.2f + dp(0.5f), edgePaint)
        }
        for (i in 1..leftLines) {
            edgePaint.color = if (i % 2 == 0) 0xFFEDE5D2.toInt() else 0xFFD9CFB8.toInt()
            val inset = dp(1.2f) * i
            c.drawRect(leftRect.left - dp(1.1f) * i, leftRect.top + inset, leftRect.left - dp(1.1f) * (i - 1), leftRect.bottom - inset * 0.2f + dp(0.5f), edgePaint)
        }
        // পাতা না থাকলে নীচে কাগজ-রং, যাতে ভাঁজের নীচে ফাঁকা না দেখায়
        c.drawRect(rightRect, paperPaint)
        if (twoPageMode && leftFace(if (turnK >= 0) turnK else index) >= 0) c.drawRect(leftRect, paperPaint)
    }

    /** স্পাইন (মাঝের বাঁধাই) বা একক-পাতায় বাম কিনারার ছায়া */
    private fun drawSpine(c: Canvas) {
        val w = dp(26f)
        if (twoPageMode) {
            val x = rightRect.left
            val l = if (index == 0 && turnK < 0) x else x - w
            shadePaint.shader = LinearGradient(x - w, 0f, x, 0f, intArrayOf(0x00000000, 0x30000000), null, Shader.TileMode.CLAMP)
            if (!(index == 0 && turnK < 0)) c.drawRect(l, rightRect.top, x, rightRect.bottom, shadePaint)
            shadePaint.shader = LinearGradient(x, 0f, x + w, 0f, intArrayOf(0x44000000, 0x00000000), null, Shader.TileMode.CLAMP)
            c.drawRect(x, rightRect.top, x + w, rightRect.bottom, shadePaint)
            shadePaint.shader = null
            // মাঝের চিকন ক্রিজ
            edgePaint.color = 0x33000000
            c.drawRect(x - dp(0.6f), rightRect.top, x + dp(0.6f), rightRect.bottom, edgePaint)
        } else {
            val x = rightRect.left
            shadePaint.shader = LinearGradient(x, 0f, x + w, 0f, intArrayOf(0x40000000, 0x00000000), null, Shader.TileMode.CLAMP)
            c.drawRect(x, rightRect.top, x + w, rightRect.bottom, shadePaint)
            shadePaint.shader = null
        }
    }

    // ── পাতা ওল্টানো ─────────────────────────────────────────────────────

    private fun drawTurning(c: Canvas, k: Int, p: Float) {
        val r = rightRect
        val front = rightFace(k)
        val under = rightFace(k + 1)
        val back = leftFace(k + 1)

        if (twoPageMode) drawFace(c, leftFace(k), leftRect)

        // ── ভাঁজ-রেখা ──
        val ox = r.right
        val oy = if (originTop) r.top else r.bottom
        val lift = r.height() * 0.17f * sin(Math.PI * p).toFloat()
        val tx = r.right - 2f * r.width() * p
        val ty = (if (originTop) oy + lift else oy - lift) + yShift
        var dx = tx - ox
        var dy = ty - oy
        val len = max(sqrt(dx * dx + dy * dy), 0.0001f)
        val nx = dx / len
        val ny = dy / len
        val mx = (ox + tx) / 2f
        val my = (oy + ty) / 2f

        val rectPts = floatArrayOf(r.left, r.top, r.right, r.top, r.right, r.bottom, r.left, r.bottom)
        val flat = clipPoly(rectPts, nx, ny, mx, my, true)      // ভাঁজের T-দিক: এখনও সমতল
        val oSide = clipPoly(rectPts, nx, ny, mx, my, false)    // O-দিক: উল্টে যাচ্ছে / উন্মোচিত

        toPath(flat, flatPath)
        toPath(oSide, revealPath)
        // O-দিকের পলিগনকে ভাঁজ-রেখায় প্রতিফলিত করলেই ফ্ল্যাপের আকার
        val refl = FloatArray(oSide.size)
        var i = 0
        while (i < oSide.size) {
            val px = oSide[i]; val py = oSide[i + 1]
            val f = (px - mx) * nx + (py - my) * ny
            refl[i] = px - 2f * f * nx
            refl[i + 1] = py - 2f * f * ny
            i += 2
        }
        toPath(refl, flapPath)

        val shadowW = dp(46f)

        // ① নিচের পাতা (উন্মোচিত অংশে) + ফ্ল্যাপের ক্যাস্ট-শ্যাডো
        if (oSide.isNotEmpty()) {
            c.save()
            c.clipPath(revealPath)
            if (under >= 0) drawFace(c, under, r) else c.drawRect(r, paperPaint)
            shadePaint.shader = LinearGradient(
                mx, my, mx - nx * shadowW, my - ny * shadowW,
                intArrayOf(0x88000000.toInt(), 0x33000000, 0x00000000), floatArrayOf(0f, 0.35f, 1f), Shader.TileMode.CLAMP
            )
            c.drawRect(r, shadePaint)
            shadePaint.shader = null
            c.restore()
        }

        // ② সমতল (এখনও লেগে থাকা) অংশ
        if (flat.isNotEmpty()) {
            c.save()
            c.clipPath(flatPath)
            drawFace(c, front, r)
            shadePaint.shader = LinearGradient(
                mx, my, mx + nx * shadowW, my + ny * shadowW,
                intArrayOf(0x66000000, 0x1A000000, 0x00000000), floatArrayOf(0f, 0.4f, 1f), Shader.TileMode.CLAMP
            )
            c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), shadePaint)
            shadePaint.shader = null
            c.restore()
        }

        // ③ ফ্ল্যাপ (উল্টানো পাতার পিছনের দিক)
        if (oSide.isNotEmpty()) {
            c.save()
            c.clipRect(bookRect.left - dp(2f), 0f, bookRect.right + dp(2f), height.toFloat())
            c.clipPath(flapPath)

            // ভাঁজ-রেখায় প্রতিফলনের ম্যাট্রিক্স: p' = R p + 2(M·n) n
            val mn = mx * nx + my * ny
            tmpMatrix.setValues(floatArrayOf(
                1f - 2f * nx * nx, -2f * nx * ny, 2f * nx * mn,
                -2f * nx * ny, 1f - 2f * ny * ny, 2f * ny * mn,
                0f, 0f, 1f
            ))
            c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paperPaint)
            c.save()
            c.concat(tmpMatrix)
            if (twoPageMode && back >= 0) {
                // পিছনের পাতা: লিফ-স্পেসে আয়না-উল্টানো
                val b = faceBitmap(back)
                c.translate(r.right, r.top)
                c.scale(-1f, 1f)
                val dst = RectF(0f, 0f, r.width(), r.height())
                if (b != null) c.drawBitmap(b, null, dst, pagePaint) else c.drawRect(dst, paperPaint)
            } else {
                // একক-পাতা: সামনের পাতার হালকা "শো-থ্রু"
                val b = faceBitmap(front)
                if (b != null) {
                    val a = Paint(pagePaint).apply { alpha = if (nightMode) 40 else 62 }
                    c.drawBitmap(b, null, r, a)
                }
            }
            c.restore()

            // ভাঁজের কাছে গোলাকার শেডিং (গাঢ় → হাইলাইট → স্বাভাবিক)
            val gw = min(dp(70f), len * 0.9f)
            shadePaint.shader = LinearGradient(
                mx, my, mx + nx * gw, my + ny * gw,
                intArrayOf(0x77000000, 0x22000000, 0x2BFFFFFF, 0x00FFFFFF),
                floatArrayOf(0f, 0.35f, 0.62f, 1f), Shader.TileMode.CLAMP
            )
            c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), shadePaint)
            shadePaint.shader = null
            c.restore()

            // ফ্ল্যাপের বাইরের কিনারায় সূক্ষ্ম ছায়া
            tmpPath.set(flapPath)
            fillPaint.style = Paint.Style.STROKE
            fillPaint.strokeWidth = dp(1.2f)
            fillPaint.color = 0x33000000
            c.drawPath(tmpPath, fillPaint)
            fillPaint.style = Paint.Style.FILL
        }
    }

    /** Sutherland–Hodgman: পলিগনকে (nx,ny)·(p−M) ≥ 0 (keepPositive) বা ≤ 0 দিকে কাটে */
    private fun clipPoly(pts: FloatArray, nx: Float, ny: Float, mx: Float, my: Float, keepPositive: Boolean): FloatArray {
        val sign = if (keepPositive) 1f else -1f
        val out = ArrayList<Float>(12)
        val n = pts.size / 2
        for (i in 0 until n) {
            val ax = pts[2 * i]; val ay = pts[2 * i + 1]
            val bx = pts[2 * ((i + 1) % n)]; val by = pts[2 * ((i + 1) % n) + 1]
            val fa = ((ax - mx) * nx + (ay - my) * ny) * sign
            val fb = ((bx - mx) * nx + (by - my) * ny) * sign
            if (fa >= 0f) { out.add(ax); out.add(ay) }
            if ((fa >= 0f) != (fb >= 0f)) {
                val t = fa / (fa - fb)
                out.add(ax + (bx - ax) * t); out.add(ay + (by - ay) * t)
            }
        }
        return out.toFloatArray()
    }

    private fun toPath(pts: FloatArray, path: Path) {
        path.rewind()
        if (pts.size < 6) return
        path.moveTo(pts[0], pts[1])
        var i = 2
        while (i < pts.size) { path.lineTo(pts[i], pts[i + 1]); i += 2 }
        path.close()
    }

    // ═════════════════════════════════════════════════════════════════════
    //  Turn control
    // ═════════════════════════════════════════════════════════════════════

    private fun canGoForward() = index + 1 < spreadCount
    private fun canGoBack() = index - 1 >= 0

    private fun cancelTurn() {
        turnAnimator?.let { it.removeAllListeners(); it.cancel() }
        turnAnimator = null
        if (turnK >= 0) {
            // অসমাপ্ত টার্ন: কাছের প্রান্তে ঠেকাই
            index = if (turnP >= 0.5f) turnK + 1 else turnK
            turnK = -1; turnP = 0f
        }
        dragging = false
    }

    private fun beginDrag(forward: Boolean, y: Float) {
        dragForward = forward
        turnK = if (forward) index else index - 1
        turnP = if (forward) 0f else 1f
        originTop = y < height * 0.38f
        yShift = 0f
        dragging = true
        prefetch()
    }

    private fun travel(): Float = rightRect.width() * if (twoPageMode) 1.35f else 0.95f

    private fun updateDrag(x: Float, y: Float) {
        val t = travel()
        turnP = if (dragForward) ((downX - x) / t).coerceIn(0f, 1f)
        else (1f - (x - downX) / t).coerceIn(0f, 1f)
        yShift = ((y - downY) * 0.22f).coerceIn(-height * 0.12f, height * 0.12f)
        invalidate()
    }

    private fun settleDrag(vx: Float) {
        val fwdFlick = vx < -900f
        val backFlick = vx > 900f
        val toOne = if (dragForward) {
            if (backFlick) false else (turnP > 0.33f || fwdFlick)
        } else {
            // backward: p=1 → 0 is "commit"
            if (fwdFlick) true else !(turnP < 0.67f || backFlick)
        }
        animateTurnTo(if (toOne) 1f else 0f)
    }

    private fun animateProgrammatic(forward: Boolean) {
        if (turnK >= 0 || pageCount == 0) return
        if (forward && !canGoForward()) { listener?.onReachedEnd(); return }
        if (!forward && !canGoBack()) return
        beginDrag(forward, height * 0.9f)
        invalidate()
        animateTurnTo(if (forward) 1f else 0f, programmatic = true)
    }

    private fun animateTurnTo(target: Float, programmatic: Boolean = false) {
        turnAnimator?.let { it.removeAllListeners(); it.cancel() }
        val from = turnP
        val dist = abs(target - from)
        val a = ValueAnimator.ofFloat(from, target)
        a.duration = (if (programmatic) 520L else 160L + (dist * 380L).toLong()).coerceAtLeast(120L)
        a.interpolator = if (programmatic) AccelerateDecelerateInterpolator() else DecelerateInterpolator(1.4f)
        a.addUpdateListener {
            turnP = it.animatedValue as Float
            if (programmatic) yShift *= 0.97f
            invalidate()
        }
        a.addListener(object : AnimatorListenerAdapter() {
            private var cancelled = false
            override fun onAnimationCancel(animation: Animator) { cancelled = true }
            override fun onAnimationEnd(animation: Animator) {
                if (cancelled) return
                finishTurn(target)
            }
        })
        turnAnimator = a
        a.start()
    }

    private fun finishTurn(target: Float) {
        val old = index
        index = if (target >= 0.5f) turnK + 1 else turnK
        turnK = -1; turnP = 0f; yShift = 0f
        dragging = false
        turnAnimator = null
        prefetch()
        invalidate()
        if (index != old) {
            listener?.onTurnCommitted(index > old)
            listener?.onPageChanged(currentPage, pageCount)
            if (index == spreadCount - 1 && index > old) listener?.onReachedEnd()
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    //  Touch
    // ═════════════════════════════════════════════════════════════════════

    private fun clampPan() {
        val maxX = (zoom - 1f) * width / 2f
        val maxY = (zoom - 1f) * height / 2f
        panX = panX.coerceIn(-maxX, maxX)
        panY = panY.coerceIn(-maxY, maxY)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (pageCount == 0) return super.onTouchEvent(e)
        scaleDetector.onTouchEvent(e)
        if (scaleDetector.isInProgress) {
            moved = true
            return true
        }

        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                downX = e.x; downY = e.y; lastX = e.x; lastY = e.y
                moved = false; blocked = false; dragging = false
                velocity?.recycle()
                velocity = VelocityTracker.obtain().also { it.addMovement(e) }
                if (turnAnimator?.isRunning == true) {            // চলমান অ্যানিমেশন সাথে সাথে শেষ
                    turnAnimator?.let { a -> a.removeAllListeners(); a.cancel() }
                    turnAnimator = null
                    if (turnK >= 0) finishTurn(if (turnP >= 0.5f) 1f else 0f)
                }
            }
            MotionEvent.ACTION_MOVE -> {
                velocity?.addMovement(e)
                val dx = e.x - downX
                val dy = e.y - downY
                if (zoom > 1.02f) {
                    if (e.pointerCount == 1) {
                        panX += e.x - lastX; panY += e.y - lastY
                        clampPan(); invalidate()
                        if (abs(dx) > slop || abs(dy) > slop) moved = true
                    }
                } else if (!dragging && !blocked) {
                    if (abs(dx) > slop && abs(dx) > abs(dy) * 0.8f) {
                        moved = true
                        val forward = dx < 0
                        if (forward && canGoForward()) beginDrag(true, downY)
                        else if (!forward && canGoBack()) beginDrag(false, downY)
                        else { blocked = true; if (forward) listener?.onReachedEnd() }
                    } else if (abs(dy) > slop) {
                        // উল্লম্ব টানা — টার্ন নয়
                        moved = true; blocked = true
                    }
                }
                if (dragging) updateDrag(e.x, e.y)
                lastX = e.x; lastY = e.y
            }
            MotionEvent.ACTION_UP -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                velocity?.addMovement(e)
                velocity?.computeCurrentVelocity(1000)
                val vx = velocity?.xVelocity ?: 0f
                if (dragging) {
                    settleDrag(vx)
                } else if (!moved) {
                    handleTap(e.x, e.y)
                } else if (zoom > 1.02f) {
                    scheduleHiRes()
                }
                velocity?.recycle(); velocity = null
            }
            MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                if (dragging) animateTurnTo(if (dragForward) 0f else 1f)
                velocity?.recycle(); velocity = null
            }
        }
        return true
    }

    private fun handleTap(x: Float, y: Float) {
        val now = System.currentTimeMillis()
        val zone = width * 0.27f
        if (zoom <= 1.02f && x < zone) { prev(); return }
        if (zoom <= 1.02f && x > width - zone) { next(); return }

        // মাঝের অংশ: ডাবল-ট্যাপ = জুম; একক-ট্যাপ = মেনু টগল
        if (now - lastTapTime < 280L) {
            pendingTap?.let { removeCallbacks(it) }
            pendingTap = null
            lastTapTime = 0L
            if (zoom > 1.02f) { resetZoom() } else {
                val target = 2.4f
                val cx = width / 2f; val cy = height / 2f
                panX = (cx - x) * (target - 1f); panY = (cy - y) * (target - 1f)
                zoom = target
                clampPan()
                scheduleHiRes()
            }
            invalidate()
        } else {
            lastTapTime = now
            val r = Runnable { pendingTap = null; listener?.onSingleTap() }
            pendingTap = r
            postDelayed(r, 270)
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        cancelTurn()
        pendingTap?.let { removeCallbacks(it) }
        removeCallbacks(hiResRunnable)
    }
}
