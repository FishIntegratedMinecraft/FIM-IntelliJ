package fish.crafting.fimplugin.plugin.minimessage

import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorCustomElementRenderer
import com.intellij.openapi.editor.Inlay
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.colors.EditorFontType
import com.intellij.openapi.editor.markup.TextAttributes
import fish.crafting.fimplugin.plugin.minimessage.parser.MiniMessageParser
import fish.crafting.fimplugin.plugin.minimessage.parser.TextComponent
import fish.crafting.fimplugin.plugin.minimessage.parser.TextStyling
import fish.crafting.fimplugin.plugin.minimessage.parser.resolver.GradientTagResolver
import fish.crafting.fimplugin.plugin.minimessage.parser.resolver.RainbowTagResolver
import fish.crafting.fimplugin.plugin.util.ObfuscationUtil
import java.awt.*
import kotlin.math.max
import kotlin.math.min

class MiniMessageRenderer(private val components: ArrayList<TextComponent>,
                          private var text: String,
                          private var width: Int = 1,
                          private var renderIndex: Int = 0,
                          private var attachedTimer: Boolean = false,
                          private var renderBG: Boolean = false): EditorCustomElementRenderer {

    constructor(text: String) : this(MiniMessageParser.parseOrLegacy(text), text)

    private var lastColor: Color? = null
    private var isObfuscated: Boolean? = null
    fun hasObfuscation(): Boolean {
        if(isObfuscated == null) {
            isObfuscated = false

            for (component in components) {
                if(component.styling.obfuscated) {
                    isObfuscated = true
                    break
                }
            }
        }

        return isObfuscated ?: false
    }

    val hasAttachedTimer get() = attachedTimer
    fun attachTimer() {
        attachedTimer = true
    }

    fun updateText(newText: String): Boolean {
        if(text == newText) return false

        components.clear()
        components.addAll(MiniMessageParser.parseOrLegacy(newText))
        isObfuscated = null //Re-calculate obfuscation
        return true
    }

    override fun calcWidthInPixels(inlay: Inlay<*>): Int {
        return width
    }

    override fun paint(inlay: Inlay<*>, g: Graphics, targetRegion: Rectangle, textAttributes: TextAttributes) {
        renderIndex++

        val g2 = g as Graphics2D
        val editor = inlay.editor

        var blank = true
        var width = 0
        var x = targetRegion.x
        val y = targetRegion.y + editor.ascent

        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB)
        g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR)
        g2.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_OFF)

        var styledFont: Font? = null
        for (component in components) {
            var baseFont = if (component.styling.bold) {
                MinecraftFont.bold
            } else {
                MinecraftFont.font
            }

            baseFont = validateFont(editor, baseFont, component)

            var text = component.content

            if (component.styling.obfuscated) {
                val metrics = g.getFontMetrics(baseFont)
                text = ObfuscationUtil.obfuscate(g, metrics, baseFont, text)
            }

            styledFont = applyStylingToFont(inlay.editor, baseFont, component.styling)
            g2.font = styledFont

            // Drawing Text

            val baseColor = component.styling.color

            val addedWidth = when(baseColor) {
                is GradientTagResolver.GradientColorElement -> {
                    renderIndividuallyColoredChars(g2, text, x, y, component, baseColor::getColor)
                }
                is RainbowTagResolver.RainbowColorElement -> {
                    renderIndividuallyColoredChars(g2, text, x, y, component, baseColor::getColor)
                }
                is TextStyling.SolidColorElement -> {
                    renderSolidColor(g2, text, baseColor, x, y, component)
                }
                else -> 0
            }

            if(blank) blank = text.isBlank()

            x += addedWidth
            width += addedWidth

        }

        val updateWidth = this.width != width

        val newRenderBG = width == 0 || blank
        val updateBG = renderBG != newRenderBG
        renderBG = newRenderBG

        if(renderBG){
            if(styledFont == null) styledFont = MinecraftFont.font.deriveFont(editor.colorsScheme.editorFontSize + 3f)
            val height = g2.getFontMetrics(styledFont).ascent
            if(width == 0) width = height

            if(!updateBG){ //Was supposed to render bg, and that didn't change
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
                g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)

                val arc = height / 2
                g2.color = lastColor ?: Color(255, 0, 0, 50)
                g2.fillRoundRect(targetRegion.x, (y - height * 0.8).toInt(), this.width, height, arc, arc)
            }
        }

        this.width = width
        if(updateWidth || updateBG) inlay.update()
    }

    //Yes I know this is terrible
    private fun renderIndividuallyColoredChars(g2: Graphics2D,
                                               text: String,
                                               x: Int, y: Int,
                                               component: TextComponent,
                                               colorGetter: (Int, Int) -> Color): Int{
        var width = 0
        var x2 = x
        for (ch in text) {
            val letter = ch.toString()
            val color = colorGetter.invoke(1, renderIndex)

            drawText(g2, x2 + 2, y + 2, letter, component.styling.getShadow(color), component.styling)
            val w = drawText(g2, x2, y, letter, color, component.styling)

            x2 += w
            width += w
        }

        return width
    }

    private fun renderSolidColor(g2: Graphics2D,
                                 text: String,
                                 colorElement: TextStyling.SolidColorElement,
                                 x: Int, y: Int,
                                 component: TextComponent): Int {
        val color = colorElement.color
        lastColor = Color(color.red, color.green, color.blue, 50)
        drawText(g2, x + 2, y + 2, text, component.styling.getShadow(color), component.styling)
        return drawText(g2, x, y, text, color, component.styling)
    }

    private fun drawText(g2: Graphics2D, x: Int, y: Int, text: String, color: Color, styling: TextStyling): Int {
        g2.color = color
        val width = g2.fontMetrics.stringWidth(text)
        g2.drawString(text, x, y)

        if (styling.underlined) {
            val underlineY = y + 1
            g2.drawLine(x, underlineY, x + width, underlineY)
        }

        if (styling.strikethrough) {
            val strikeY = y - g2.fontMetrics.height / 3
            g2.drawLine(x, strikeY, x + width, strikeY)
        }

        return width
    }

    private fun applyStylingToFont(editor: Editor, base: Font, style: TextStyling): Font {
        val fontSize: Int = editor.colorsScheme.editorFontSize
        var styleFlags = Font.PLAIN
        if (style.bold) styleFlags = styleFlags or Font.BOLD
        if (style.italic) styleFlags = styleFlags or Font.ITALIC
        return base.deriveFont(styleFlags, fontSize.toFloat() + 3f)
    }

    private fun validateFont(editor: Editor, base: Font, component: TextComponent): Font {
        if (base.canDisplayUpTo(component.content) != -1) {
            return editor.colorsScheme.getFont(EditorFontType.PLAIN)
        }
        return base
    }
}