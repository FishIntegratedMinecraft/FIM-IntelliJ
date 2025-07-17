package fish.crafting.fimplugin.plugin.minimessage

import com.intellij.json.psi.JsonStringLiteral
import com.intellij.json.psi.impl.JsonRecursiveElementVisitor
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.DumbService
import com.intellij.psi.JavaRecursiveElementVisitor
import com.intellij.psi.PsiBinaryExpression
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiCodeBlock
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiExpression
import com.intellij.psi.PsiExpressionList
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiLiteralExpression
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiMethodCallExpression
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.util.PsiUtil
import com.intellij.ui.ColorHexUtil
import com.intellij.util.concurrency.AppExecutorUtil
import fish.crafting.fimplugin.plugin.util.javakotlin.isJava
import fish.crafting.fimplugin.plugin.util.javakotlin.isJson
import fish.crafting.fimplugin.plugin.util.javakotlin.isKotlin
import fish.crafting.fimplugin.plugin.util.javakotlin.isYaml
import io.ktor.util.reflect.instanceOf
import org.jetbrains.kotlin.idea.caches.resolve.analyze
import org.jetbrains.kotlin.js.parser.sourcemaps.JsonString
import org.jetbrains.kotlin.psi.KtBlockExpression
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtStringTemplateExpression
import org.jetbrains.kotlin.psi.KtTreeVisitorVoid
import org.jetbrains.kotlin.psi.KtValueArgument
import org.jetbrains.kotlin.psi.KtValueArgumentList
import org.jetbrains.kotlin.psi.psiUtil.getQualifiedExpressionForSelector
import org.jetbrains.uast.UCallExpression
import org.jetbrains.uast.toUElement
import org.jetbrains.uast.toUElementOfType
import org.jetbrains.yaml.psi.YAMLQuotedText
import org.jetbrains.yaml.psi.YamlRecursivePsiElementVisitor
import java.awt.Color

fun PsiElement.checkMCFormatBGT(controller: MiniMessageInlayController): Boolean {
    if(DumbService.isDumb(project)) return false
    if(quickShouldFormatMCText(controller)) return true
    return shouldFormatMCText()
}

fun PsiElement.checkMCFormatAndRun(controller: MiniMessageInlayController, run: (Boolean) -> Unit) {
    if(DumbService.isDumb(project)) {
        run.invoke(false)
        return
    }

    if(quickShouldFormatMCText(controller)) {
        run.invoke(true)
        return
    }

    ReadAction.nonBlocking<Boolean> { shouldFormatMCText() }
        .finishOnUiThread(ApplicationManager.getApplication().defaultModalityState, run)
        .submit(AppExecutorUtil.getAppExecutorService())
}

fun PsiElement.shouldOnlyRenderIfValid(): Boolean{
    return this is JsonStringLiteral || this is YAMLQuotedText
}

fun PsiElement.getLiteralValue(): Any? {
    return when(this) {
        is PsiLiteralExpression -> this.value
        is KtStringTemplateExpression -> this.entries.joinToString("") { it.text }
        is JsonStringLiteral -> this.value
        is YAMLQuotedText -> this.textValue
        else -> ""
    }
}

fun PsiElement.getParentLiteral(): PsiElement? {
    val clazz = if(this.language.isJava) PsiLiteralExpression::class.java
    else if(this.language.isKotlin) KtStringTemplateExpression::class.java
    else if(this.language.isJson) JsonStringLiteral::class.java
    else if(this.language.isYaml) YAMLQuotedText::class.java
    else return null

    return PsiTreeUtil.getParentOfType(this, clazz, false)
}

fun PsiElement.visitExpressions(run: (PsiElement) -> Unit) {
    if(language.isJava) {
        accept(object : JavaRecursiveElementVisitor() {
            override fun visitCodeBlock(block: PsiCodeBlock) {
                if (!block.isValid || block.containingFile == null) return
                super.visitCodeBlock(block)
            }
            override fun visitLiteralExpression(expression: PsiLiteralExpression) {
                run.invoke(expression)
            }
        })
    }else if(language.isKotlin){
        accept(object : KtTreeVisitorVoid() {
            override fun visitBlockExpression(expression: KtBlockExpression) {
                if (!expression.isValid || expression.containingFile == null) return
                super.visitBlockExpression(expression)
            }
            override fun visitStringTemplateExpression(expression: KtStringTemplateExpression) {
                run.invoke(expression)
            }
        })
    }else if(language.isJson) {
        accept(object : JsonRecursiveElementVisitor() {
            override fun visitStringLiteral(o: JsonStringLiteral) {
                run.invoke(o)
            }
        })
    }else if(language.isYaml){
        accept(object : YamlRecursivePsiElementVisitor() {
            override fun visitQuotedText(quotedText: YAMLQuotedText) {
                run.invoke(quotedText)
            }
        })
    }
}

private fun PsiElement.getRuntimeContainingClass(): PsiClass? {
    var callExpression: UCallExpression? = null
    if(this.language.isJava){
        val exprList = parent as? PsiExpressionList ?: return null
        callExpression = (exprList.parent as? PsiMethodCallExpression)?.toUElementOfType()
    }else if(this.language.isKotlin){
        val valArg = parent as? KtValueArgument ?: return null
        val exprList = valArg.parent as? KtValueArgumentList ?: return null
        callExpression = (exprList.parent as? KtCallExpression)?.toUElementOfType()
    }

    if(callExpression == null) return null
    return PsiUtil.resolveClassInType(callExpression.receiverType)
}

private fun PsiElement.resolveMethodFromLiteral(): PsiMethod? {
    if(this.language.isJava){
        var exprList = parent as? PsiExpressionList
        if(exprList == null && parent is PsiBinaryExpression) { //"" + ""
           exprList = parent.parent as? PsiExpressionList
        }

        exprList ?: return null

        val call = exprList.parent as? PsiMethodCallExpression ?: return null
        return call.resolveMethod()
    }else if(this.language.isKotlin){
        val valArg = parent as? KtValueArgument ?: return null
        val exprList = valArg.parent as? KtValueArgumentList ?: return null
        val call = exprList.parent as? KtCallExpression ?: return null

        val uElement = call.toUElement() as? UCallExpression ?: return null
        return uElement.resolve()
    }

    return null
}

/**
 * This method checks whether the Literal Expression is being edited right now.
 * OR when no checks are necessary (formatting is always enabled (JSON, YAML))
 */
fun PsiElement.quickShouldFormatMCText(controller: MiniMessageInlayController): Boolean {
    if(this is JsonStringLiteral) {
        val next = this.nextSibling
        if(next != null && next.text == ":") return false
        return true
    }

    if(this is YAMLQuotedText) {
        return true
    }

    //We are editing this rn
    return controller.matchesCached(this)
}

fun PsiElement.shouldFormatMCText(): Boolean {
    val method = resolveMethodFromLiteral() ?: return false
    if(TextFormatRegistryService.instance.isMethodValid(method)) return true

    val containingClass = method.containingClass
    containingClass ?: return false

    val declaredClassValid = TextFormatRegistryService.instance.isClassValid(containingClass)
    if(declaredClassValid) return true

    val runtimeContainingClass = getRuntimeContainingClass() ?: return false
    return TextFormatRegistryService.instance.isClassValid(runtimeContainingClass)
}

fun String.toHexOrNull(): Color? {
    try {
        val hex = ColorHexUtil.fromHexOrNull(this)
        return hex
    }catch (ignored: Exception) {}
    return null
}