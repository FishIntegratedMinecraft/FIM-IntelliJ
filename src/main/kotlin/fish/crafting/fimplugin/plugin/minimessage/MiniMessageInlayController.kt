package fish.crafting.fimplugin.plugin.minimessage

import ai.grazie.utils.attributes.value
import com.github.weisj.jsvg.bg
import com.intellij.ide.AppLifecycleListener
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.application.runInEdt
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.FoldRegion
import com.intellij.openapi.editor.Inlay
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.util.Disposer
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.SmartPointerManager
import com.intellij.psi.SmartPsiElementPointer
import com.intellij.psi.util.endOffset
import com.intellij.psi.util.startOffset
import com.intellij.util.Alarm
import com.intellij.util.concurrency.AppExecutorUtil
import fish.crafting.fimplugin.plugin.action.inlay.isOurs
import fish.crafting.fimplugin.plugin.listener.PluginDisposable
import fish.crafting.fimplugin.plugin.minimessage.parser.MiniMessageParser
import fish.crafting.fimplugin.plugin.util.DataKeys
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

class MiniMessageInlayController {

    var currentlyEditing: SmartPsiElementPointer<PsiElement>? = null
    var lastCaretExpression: SmartPsiElementPointer<PsiElement>? = null
    private var blockInlay: Inlay<*>? = null

    companion object {
        fun removeAllFolds(removeInlays: Boolean = false) {
            val projects = ProjectManager.getInstance().openProjects
            for (project in projects) {
                val fileEditorManager = FileEditorManager.getInstance(project)
                for (fileEditor in fileEditorManager.allEditors) {
                    val textEditor = fileEditor as? TextEditor ?: continue
                    val editor = textEditor.editor
                    val controller = editor.getUserData(DataKeys.INLAY_CONTROLLER) ?: return

                    controller.removeFolds(editor)

                    if(removeInlays) {
                        controller.removeBlockInlay(editor, false)
                        controller.removeAllInlineInlays(editor)
                    }
                }
            }
        }
    }

    fun dispose(editor: Editor) {
        clearCaches()
        blockInlay?.dispose()
        blockInlay = null

        removeFolds(editor)
        removeAllInlineInlays(editor)
    }

    fun removeFolds(editor: Editor) {
        editor.foldingModel.runBatchFoldingOperation {
            editor.foldingModel.allFoldRegions.forEach {
                if(it.placeholderText.isEmpty()){
                    editor.foldingModel.removeFoldRegion(it)
                }
            }
        }
        (editor as? EditorEx)?.reinitSettings()
    }

    /**
     * If the user updates which methods are valid/invalid, this is all worthless now
     */
    fun clearCaches() {
        setCache(null)
        lastCaretExpression = null
    }

    fun setCache(expression: PsiElement?) {
        if(expression == null) {
            currentlyEditing = null
            return
        }

        currentlyEditing = SmartPointerManager.createPointer(expression)
    }

    fun matchesCached(expression: PsiElement): Boolean {
        val pointer = currentlyEditing ?: return false
        val element = pointer.element ?: return false
        return element == expression
    }

    fun getCachedExpr(): PsiElement? {
        val pointer = currentlyEditing ?: return null
        val element = pointer.element

        if(element == null) {
            clearCaches()
            return null
        }

        return element
    }

    fun getAndReplace(newExpression: PsiElement?): PsiElement? {
        val cachedExpr = getCachedExpr()
        setCache(newExpression)
        return cachedExpr
    }

    fun uncollapse(editor: Editor, expression: PsiElement) {
        val foldingModel = editor.foldingModel

        val startOffset = expression.startOffset
        val endOffset = expression.endOffset

        foldingModel.runBatchFoldingOperation {
            val region = foldingModel.getFoldRegion(startOffset, endOffset)
            if (region != null) {
                foldingModel.removeFoldRegion(region)
            }
        }
    }

    fun uncollapseOurOnly(editor: Editor, offset: Int) {
        val foldingModel = editor.foldingModel

        foldingModel.runBatchFoldingOperation {
            val region = foldingModel.getCollapsedRegionAtOffset(offset - 1)
            if (region != null && region.placeholderText.isEmpty()) {
                foldingModel.removeFoldRegion(region)
            }
        }
    }

    fun collapse(editor: Editor, expression: PsiElement, text: String) {
        if(!shouldCollapse(expression, text)) return
        val foldingModel = editor.foldingModel

        val startOffset = expression.startOffset
        val endOffset = expression.endOffset

        foldingModel.runBatchFoldingOperation {
            val existingRegion = foldingModel.getFoldRegion(startOffset, endOffset)

            if (existingRegion != null) {
                existingRegion.isExpanded = false
            } else {
                val region: FoldRegion? = foldingModel.addFoldRegion(
                    startOffset,
                    endOffset,
                    ""
                )

                if (region != null) {
                    region.isExpanded = false
                }
            }
        }
    }

    fun blockInlay(editor: Editor, expression: PsiElement, text: String, bgtTracker: BGTInlayTracker? = null){
        if(blockInlay != null){ //We don't need to check shouldOnlyRenderIfValid(), because if the user had at one point in this block inlay had tags, it should just stay
            if(matchesCached(expression)){ //The current inlay is correct, just update text.
                val renderer = blockInlay!!.renderer as MiniMessageRenderer
                val changed = renderer.updateText(text)

                if(changed) {
                    onEDTorNow(bgtTracker) {
                        blockInlay!!.update()
                    }
                }

                //If the user is editing text and adds &k, try and add an update timer
                //This won't remove a timer if the user removes a &k, but that won't really be a problem
                //since there is only one block inlay at a time
                attachObfuscatedTimer(renderer, blockInlay)

                return
            }
        }

        onEDTorNow(bgtTracker){
            //If there is no inlay, or it needs to update position, make a new one!
            val oldFocused = getAndReplace(expression)
            blockInlay?.dispose()

            removeInlineInlay(editor, expression, bgtTracker = bgtTracker)

            var appliedRenderer: MiniMessageRenderer? = null
            if(expression.shouldOnlyRenderIfValid()){
                val hadTags = AtomicBoolean(false)
                val parseOrLegacy = MiniMessageParser.parseOrLegacy(text, hadTags)

                if(hadTags.get()){
                    appliedRenderer = MiniMessageRenderer(parseOrLegacy, text)
                    blockInlay = editor.inlayModel.addBlockElement(
                        expression.endOffset,
                        false,
                        false,
                        100,
                        appliedRenderer
                    )
                }else{
                    blockInlay = null
                }
            }else{
                appliedRenderer = MiniMessageRenderer(text)
                blockInlay = editor.inlayModel.addBlockElement(
                    expression.endOffset,
                    false,
                    false,
                    100,
                    appliedRenderer
                )
            }

            attachObfuscatedTimer(appliedRenderer, blockInlay)

            if(oldFocused != null){
                updateInlineFor(editor, oldFocused, false)
            }
        }
    }

    @OptIn(FlowPreview::class)
    fun attachObfuscatedTimer(renderer: MiniMessageRenderer?, inlay: Inlay<*>?) {
        if(renderer != null && inlay != null && renderer.hasObfuscation()) {
            if(renderer.hasAttachedTimer) return
            renderer.attachTimer()

            //Register our inlay as disposable of our plugin so no memory leaks! :D
            Disposer.register(PluginDisposable.getInstance(), inlay)

            CoroutineScope(Dispatchers.Default + SupervisorJob()).launch {
                flow {
                    while (isActive) {
                        emit(Unit)
                        delay(50)
                    }
                }
                    .sample(50)
                    .flowOn(Dispatchers.Default)
                    .collect {
                        withContext(Dispatchers.EDT) {
                            inlay.update()
                        }
                    }
            }.also { scope ->
                Disposer.register(inlay) { scope.cancel() }
            }
        }
    }

    fun removeInlineInlay(editor: Editor, expression: PsiElement, uncollapse: Boolean = true, bgtTracker: BGTInlayTracker? = null) {
        val startOffset = expression.startOffset
        val endOffset = expression.endOffset

        if(uncollapse){
            onEDTorNow(bgtTracker) {
                uncollapse(editor, expression)
            }
        }

        editor.inlayModel.getInlineElementsInRange(startOffset, endOffset)
            .filter { it.renderer is MiniMessageRenderer }
            .forEach { it.dispose() }
    }

    fun removeAllInlineInlays(editor: Editor){
        val document = editor.document

        editor.inlayModel.getInlineElementsInRange(0, document.textLength)
            .filter { it.renderer is MiniMessageRenderer }
            .forEach { it.dispose() }
    }

    fun removeBlockInlay(editor: Editor, inlineOld: Boolean = true){
        blockInlay?.dispose()
        blockInlay = null
        val oldFocused = getAndReplace(null)
        if(inlineOld && oldFocused != null){
            updateInlineFor(editor, oldFocused)
        }
    }

    fun refreshInlays(editor: Editor, providedStringLiteral: PsiElement? = null) {
        val project = editor.project ?: return
        val stringLiteral = providedStringLiteral ?: getLiteralExpression(editor)

        var newCaretElement = false
        if(stringLiteral == null){
            lastCaretExpression = null
        }else if(lastCaretExpression == null || lastCaretExpression?.element != stringLiteral) {
            lastCaretExpression = SmartPointerManager.createPointer(stringLiteral)
            newCaretElement = true
        }

        stringLiteral ?: return

        stringLiteral.checkMCFormatAndRun(this) { result ->
            if (result) {
                PsiDocumentManager.getInstance(project).performLaterWhenAllCommitted {
                    val text = stringLiteral.getLiteralValue() as? String ?: ""

                    removeInlineInlay(editor, stringLiteral)
                    blockInlay(editor, stringLiteral, text)
                }
            }else{
                removeBlockInlay(editor)
                if(newCaretElement){
                    removeInlayAndFold(editor, editor.caretModel.offset)
                }
            }
        }

    }
    /**
     * Even though this does return PsiElement, it will return null if the element is not a LiteralExpression.
     * The reason it returns PsiElement is for compatibility across languages/filetypes
     */
    private fun getLiteralExpression(editor: Editor): PsiElement? {
        if(editor.project == null) return null

        val caretOffset = editor.caretModel.offset
        val psiFile = PsiDocumentManager.getInstance(editor.project!!).getPsiFile(editor.document) ?: return null

        var psiElement = psiFile.findElementAt(caretOffset)
        var stringLiteral = psiElement?.getParentLiteral()

        if(stringLiteral == null){ //Try at offset - 1
            psiElement = psiFile.findElementAt(caretOffset - 1) ?: return null
            stringLiteral = psiElement.getParentLiteral()
        }

        return stringLiteral
    }

    private fun updateInlineStringLiteral(editor: Editor, text: String, expression: PsiElement){
        val offset = expression.textRange.endOffset
        val inlayModel = editor.inlayModel

        removeInlineInlay(editor, expression, false)
        if(!shouldShowInlineInlay(expression, text)) return

        var appliedRenderer: MiniMessageRenderer? = null
        var appliedInlay: Inlay<*>? = null

        if(expression.shouldOnlyRenderIfValid()){
            val hadTags = AtomicBoolean(false)
            val parseOrLegacy = MiniMessageParser.parseOrLegacy(text, hadTags)
            if(hadTags.get()){
                appliedRenderer = MiniMessageRenderer(parseOrLegacy, text)
                appliedInlay = inlayModel.addInlineElement(offset, true, appliedRenderer)
                collapse(editor, expression, text)
            }
        }else{
            appliedRenderer = MiniMessageRenderer(text)
            appliedInlay = inlayModel.addInlineElement(offset, true, appliedRenderer)
            collapse(editor, expression, text)
        }

        attachObfuscatedTimer(appliedRenderer, appliedInlay)
    }

    private fun handleCaretUpdate(editor: Editor) {
        val stringLiteral = getLiteralExpression(editor)

        if(stringLiteral == null) {
            lastCaretExpression = null
            removeBlockInlay(editor)
            return
        }

        refreshInlays(editor, stringLiteral)
    }

    fun updateAllInlines(editor: Editor){
        val project = editor.project ?: return
        val file = PsiDocumentManager.getInstance(project).getPsiFile(editor.document) ?: return

        //Batch BGT checks so we don't request a separate action for each literal
        ReadAction.nonBlocking<BGTInlayTracker> {
            val bgtTracker = BGTInlayTracker()
            file.visitExpressions {
                updateInlineFor(editor, it, bgtTracker = bgtTracker)
            }

            bgtTracker
        }.finishOnUiThread(ApplicationManager.getApplication().defaultModalityState) { tracker ->
            editor.inlayModel.getInlineElementsInRange(0, editor.document.textLength)
                .filter { it.renderer is MiniMessageRenderer }
                .forEach { it.dispose() }

            tracker.successful.forEach { updateInlineStringLiteral(editor, it.first, it.second) }
            tracker.failed.forEach { removeInlineInlay(editor, it) }
            tracker.runOnEDT.forEach { it.invoke() }

            tracker.clear()
        }.submit(AppExecutorUtil.getAppExecutorService())
    }

    fun updateInlineFor(editor: Editor,
                        expression: PsiElement,
                        checkBlockInline: Boolean = true,
                        bgtTracker: BGTInlayTracker? = null) {
        val value = expression.getLiteralValue()
        if(value !is String) return

        if(checkBlockInline && matchesCached(expression)) {
            blockInlay(editor, expression, value, bgtTracker)
            return
        }

        if(bgtTracker == null) { //This is run from EDT, so we use checkMCFormatAndRun() to automatically do BGT Checks + EDT Updates
            expression.checkMCFormatAndRun(this) { result ->
                if(result){
                    updateInlineStringLiteral(editor, value, expression)
                }else{
                    removeInlineInlay(editor, expression)
                }
            }
        }else { //This is run from a BGT batch, store the results in the tracker
            if(expression.checkMCFormatBGT(this)){
                bgtTracker.successful.add(Pair(value, expression))
            }else{
                bgtTracker.failed.add(expression)
            }
        }

    }

    private fun shouldCollapse(element: PsiElement, text: String): Boolean {
        return shouldShowInlineInlay(element, text)
    }

    fun removeInlayAndFold(editor: Editor, offset: Int) {
        val inlays = editor.inlayModel.getInlineElementsInRange(offset - 1, offset + 1)

        for (inlay in inlays) {
            if(inlay.isOurs()) {
                removeInlayAndFold(editor, inlay)
            }
        }
    }

    fun removeInlayAndFold(editor: Editor, inlay: Inlay<*>) {
        val offset = inlay.offset
        inlay.dispose()
        ApplicationManager.getApplication().invokeLater {
            uncollapseOurOnly(editor, offset)
        }
    }

    private fun shouldShowInlineInlay(element: PsiElement, text: String): Boolean {
        if(text.isBlank()) return false //Don't collapse "" so that it shows up
        return true

    }

    fun handleDocumentChanged(editor: Editor) {
        refreshInlays(editor)
    }

    fun handleCaretChanged(editor: Editor) {
        handleCaretUpdate(editor)
    }

    data class BGTInlayTracker(val successful: ArrayList<Pair<String, PsiElement>> = arrayListOf(),
                               val failed: ArrayList<PsiElement> = arrayListOf(),
                               val runOnEDT: ArrayList<() -> Unit> = arrayListOf()) {

        fun clear() {
            successful.clear()
            failed.clear()
            runOnEDT.clear()
        }
        fun runOnEDT(run: () -> Unit) {
            runOnEDT.add(run)
        }
    }

    private fun onEDTorNow(bgtTracker: BGTInlayTracker?, run: () -> Unit){
        if(bgtTracker == null) run.invoke()
        else bgtTracker.runOnEDT(run)
    }
}
