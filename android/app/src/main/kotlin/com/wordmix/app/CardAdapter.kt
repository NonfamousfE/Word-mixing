package com.wordmix.app

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 卡片式列表：和桌面端保持一致的视觉结构。
 *
 *     plague   /ˈpleɪɡ/          ← 单词（加粗）+ 音标（灰）
 *     ▬▬▬▬▬▬▬▬▬▬                  ← 灰色长条：释义默认被盖住，点一下显示
 *     ────────────────           ← 卡片之间的分隔线
 *
 * 默认浅色风格（白卡片、浅灰底），与桌面端对齐。
 */
class CardAdapter(
    private val ctx: Context,
    private var rows: List<Row>,
    private val isDarkTheme: () -> Boolean = { false },
    private val onReveal: (Row) -> Unit,
    private val onSpeak: ((String) -> Unit)? = null,
) : BaseAdapter() {

    /** 绿色喇叭发音按钮（自定义绘制，无需第三方库与矢量图资源） */
    private class SpeakerButton(
        ctx: Context,
        private val colorProvider: () -> Int,
    ) : View(ctx) {
        private val bodyPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            style = android.graphics.Paint.Style.FILL
        }
        private val wavePaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            style = android.graphics.Paint.Style.STROKE
            strokeCap = android.graphics.Paint.Cap.ROUND
        }
        private val bodyPath = android.graphics.Path()
        private val waveRect1 = android.graphics.RectF()
        private val waveRect2 = android.graphics.RectF()

        override fun onDraw(canvas: android.graphics.Canvas) {
            super.onDraw(canvas)
            val color = colorProvider()
            bodyPaint.color = color
            wavePaint.color = color

            val w = width.toFloat()
            val h = height.toFloat()
            if (w <= 0 || h <= 0) return

            val size = minOf(w, h) * 0.72f
            val left = (w - size) / 2f
            val top = (h - size) / 2f

            wavePaint.strokeWidth = size * 0.10f

            // 喇叭主体：左侧方块 + 中间喇叭扩散梯形
            bodyPath.reset()
            val sx = left
            val sy = top + size * 0.15f
            val sh = size * 0.7f
            val sw = size * 0.44f

            bodyPath.moveTo(sx, sy + sh * 0.28f)
            bodyPath.lineTo(sx + sw * 0.38f, sy + sh * 0.28f)
            bodyPath.lineTo(sx + sw, sy)
            bodyPath.lineTo(sx + sw, sy + sh)
            bodyPath.lineTo(sx + sw * 0.38f, sy + sh * 0.72f)
            bodyPath.lineTo(sx, sy + sh * 0.72f)
            bodyPath.close()
            canvas.drawPath(bodyPath, bodyPaint)

            // 两道声波弧线
            val cx = sx + sw * 0.45f
            val cy = sy + sh * 0.5f

            val r1 = size * 0.26f
            waveRect1.set(cx - r1, cy - r1, cx + r1, cy + r1)
            canvas.drawArc(waveRect1, -45f, 90f, false, wavePaint)

            val r2 = size * 0.45f
            waveRect2.set(cx - r2, cy - r2, cx + r2, cy + r2)
            canvas.drawArc(waveRect2, -45f, 90f, false, wavePaint)
        }
    }

    /** 一行：要么是分组标题，要么是词条。 */
    class Row(
        val isGroup: Boolean,
        val label: String = "",
        val entry: Map<String, Any?>? = null,
        val ipa: String = "",
        val hidden: Boolean = false,
        val isChild: Boolean = false,
        val hasIssue: Boolean = false,
        val group: Map<String, Any?>? = null,
    )

    fun submit(newRows: List<Row>) {
        rows = newRows
        notifyDataSetChanged()
    }

    override fun getCount(): Int = rows.size
    override fun getItem(position: Int): Any = rows[position]
    override fun getItemId(position: Int): Long = position.toLong()

    private fun dp(v: Float): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, ctx.resources.displayMetrics).toInt()

    private fun c(dark: Int, light: Int) = if (isDarkTheme()) dark else light

    override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
        val row = rows[position]
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        if (row.isGroup) {
            // ---- 分组标题：小字、淡色、上下留白 ----
            root.setBackgroundColor(c(0xFF14171D.toInt(), 0xFFF5F6F8.toInt()))
            val tv = TextView(ctx).apply {
                text = row.label
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setPadding(dp(18f), dp(14f), dp(18f), dp(6f))
                setTextColor(c(0xFF828B99.toInt(), 0xFF8B93A1.toInt()))
                isAllCaps = false
            }
            root.addView(tv)
            val line = View(ctx).apply {
                setBackgroundColor(c(0xFF2B313C.toInt(), 0xFFEEF0F3.toInt()))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(1f)
                ).also { it.leftMargin = dp(18f); it.rightMargin = dp(18f) }
            }
            root.addView(line)
            return root
        }

        // ---- 词条卡片：桌面端表面色（浅色为纯白 #FFFFFF）----
        root.setBackgroundColor(c(0xFF1B1F27.toInt(), 0xFFFFFFFF.toInt()))
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                if (row.isChild) dp(34f) else dp(18f), dp(12f), dp(18f), dp(12f)
            )
        }

        // 第一行：左侧(单词 + 音标) + 右侧(绿色发音小喇叭按钮)
        val head = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        val leftInfo = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
            )
        }

        val wordStr = Items.word(row.entry!!)
        val wordText = if (row.isChild) "└ " + wordStr else wordStr

        val w = TextView(ctx).apply {
            text = wordText
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(
                when {
                    row.hasIssue -> c(0xFFE0A33A.toInt(), 0xFFC77700.toInt())
                    else -> c(0xFFEEF1F6.toInt(), 0xFF1F2430.toInt())
                }
            )
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        leftInfo.addView(w)

        if (row.ipa.isNotEmpty()) {
            val ipa = TextView(ctx).apply {
                text = row.ipa
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setTextColor(c(0xFF828B99.toInt(), 0xFF8B93A1.toInt()))
                setPadding(dp(10f), 0, 0, 0)
                setOnClickListener { onSpeak?.invoke(wordStr) }
            }
            leftInfo.addView(ipa)
        }
        head.addView(leftInfo)

        // 右侧绿色小喇叭按钮（触摸热区扩大至 44dp x 44dp，并增加水波纹反馈）
        val speakerTouch = FrameLayout(ctx).apply {
            contentDescription = "朗读"
            layoutParams = LinearLayout.LayoutParams(dp(44f), dp(44f)).apply {
                gravity = Gravity.CENTER_VERTICAL
            }
            val outValue = TypedValue()
            if (ctx.theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, outValue, true)) {
                setBackgroundResource(outValue.resourceId)
            }
            val speaker = SpeakerButton(ctx) { c(0xFF4CC38A.toInt(), 0xFF2F8F5B.toInt()) }.apply {
                isClickable = false
                isFocusable = false
                val iconSize = dp(24f)
                layoutParams = FrameLayout.LayoutParams(iconSize, iconSize).apply {
                    gravity = Gravity.CENTER
                }
            }
            addView(speaker)
            setOnClickListener { onSpeak?.invoke(wordStr) }
        }
        head.addView(speakerTouch)

        card.addView(head)

        // 第二行：释义，或灰色遮挡条
        val meaning = Items.meaning(row.entry!!)
        if (row.hidden && meaning.isNotEmpty()) {
            val bar = View(ctx).apply {
                val bg = GradientDrawable().apply {
                    cornerRadius = dp(4f).toFloat()
                    setColor(c(0xFF2B313C.toInt(), 0xFFE4E7EB.toInt()))
                }
                background = bg
                // 条的长度按释义文字长度估算，像桌面端那样"长得像内容"
                val widthDp = (meaning.length * 10).coerceIn(90, 260)
                layoutParams = LinearLayout.LayoutParams(dp(widthDp.toFloat()), dp(20f)).also {
                    it.topMargin = dp(8f)
                }
                setOnClickListener { onReveal(row) }
            }
            card.addView(bar)
        } else {
            val m = TextView(ctx).apply {
                text = if (meaning.isEmpty()) "（还没写释义）" else meaning
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setTextColor(
                    if (meaning.isEmpty()) c(0xFF828B99.toInt(), 0xFF8B93A1.toInt())
                    else c(0xFFB6BDC9.toInt(), 0xFF4A5361.toInt())
                )
                setPadding(0, dp(6f), 0, 0)
                setOnClickListener { onReveal(row) }
            }
            card.addView(m)
        }

        root.addView(card)

        // 卡片之间的细分隔线
        val sep = View(ctx).apply {
            setBackgroundColor(c(0xFF262C37.toInt(), 0xFFEEF0F3.toInt()))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1f)
            ).also { it.leftMargin = dp(18f); it.rightMargin = dp(18f) }
        }
        root.addView(sep)
        return root
    }
}
