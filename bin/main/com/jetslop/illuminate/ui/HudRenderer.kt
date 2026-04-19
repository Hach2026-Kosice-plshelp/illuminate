package com.jetslop.illuminate.ui

import com.intellij.openapi.editor.EditorCustomElementRenderer
import com.intellij.openapi.editor.Inlay
import com.intellij.openapi.editor.colors.EditorFontType
import com.intellij.openapi.editor.markup.TextAttributes
import java.awt.*
import java.awt.event.MouseEvent
import java.awt.geom.RoundRectangle2D

class HudRenderer(
    private val icon: String,
    private val label: String,
    private val description: String,
    private val accentColor: Color,
    private val tag: String? = null,
    val onClick: (() -> Unit)? = null
) : EditorCustomElementRenderer {

    companion object {
        private const val HEIGHT = 22
        private const val H_PADDING = 10
        private const val ICON_WIDTH = 18
        private const val TAG_H_PAD = 6
        private const val GAP = 6
        private const val CORNER = 6
    }

    override fun calcWidthInPixels(inlay: Inlay<*>): Int {
        val editor = inlay.editor
        val metrics = editor.contentComponent.getFontMetrics(
            editor.colorsScheme.getFont(EditorFontType.PLAIN).deriveFont(11f)
        )
        var w = H_PADDING + ICON_WIDTH + GAP
        w += metrics.stringWidth(label) + GAP
        w += metrics.stringWidth(description)
        if (tag != null) {
            w += GAP + TAG_H_PAD * 2 + metrics.stringWidth(tag)
        }
        // Click hint
        if (onClick != null) {
            w += GAP + metrics.stringWidth("  ▶ Click for details")
        }
        w += H_PADDING
        return w
    }

    override fun calcHeightInPixels(inlay: Inlay<*>): Int = HEIGHT

    override fun paint(inlay: Inlay<*>, g: Graphics, targetRegion: Rectangle, textAttributes: TextAttributes) {
        val g2 = g.create() as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB)

        val x = targetRegion.x
        val y = targetRegion.y
        val w = targetRegion.width
        val h = targetRegion.height

        // Background — glass-like translucent bar
        val bgColor = Color(accentColor.red, accentColor.green, accentColor.blue, 18)
        g2.color = bgColor
        g2.fill(RoundRectangle2D.Float(x.toFloat(), y.toFloat(), w.toFloat(), h.toFloat(), CORNER.toFloat(), CORNER.toFloat()))

        // Left accent line
        g2.color = accentColor
        g2.fillRect(x, y + 4, 3, h - 8)

        val font = inlay.editor.colorsScheme.getFont(EditorFontType.PLAIN).deriveFont(11f)
        val boldFont = font.deriveFont(Font.BOLD)
        g2.font = font
        val fm = g2.fontMetrics
        val textY = y + (h + fm.ascent - fm.descent) / 2

        var cx = x + H_PADDING

        // Icon
        g2.font = font.deriveFont(13f)
        g2.color = accentColor
        g2.drawString(icon, cx, textY)
        cx += ICON_WIDTH

        // Label (bold)
        g2.font = boldFont
        g2.color = Color(230, 237, 243) // --text
        g2.drawString(label, cx, textY)
        cx += fm.stringWidth(label) + GAP

        // Separator dot
        g2.color = Color(139, 148, 158, 120) // muted
        g2.fillOval(cx, y + h / 2 - 2, 3, 3)
        cx += GAP + 3

        // Description
        g2.font = font
        g2.color = Color(139, 148, 158) // --text-muted
        g2.drawString(description, cx, textY)
        cx += fm.stringWidth(description)

        // Tag badge
        if (tag != null) {
            cx += GAP
            val tagW = fm.stringWidth(tag) + TAG_H_PAD * 2
            val tagH = h - 8
            val tagY = y + 4
            g2.color = Color(accentColor.red, accentColor.green, accentColor.blue, 30)
            g2.fill(RoundRectangle2D.Float(cx.toFloat(), tagY.toFloat(), tagW.toFloat(), tagH.toFloat(), 4f, 4f))
            g2.color = accentColor
            g2.font = font.deriveFont(10f)
            g2.drawString(tag, cx + TAG_H_PAD, textY - 1)
            cx += tagW
        }

        // Click hint
        if (onClick != null) {
            cx += GAP
            g2.font = font.deriveFont(10f)
            g2.color = Color(124, 58, 237, 160)
            g2.drawString("  ▶ Click for details", cx, textY)
        }

        g2.dispose()
    }
}
