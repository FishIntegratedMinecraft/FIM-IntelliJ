package fish.crafting.fimplugin.plugin.util

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.DumbUtil
import com.intellij.openapi.project.ProjectManager
import com.intellij.psi.PsiManager

object EditorUtil {

    fun refreshGutters() {
        ReadAction.run<RuntimeException>{
            val project = ProjectManager.getInstance().openProjects.firstOrNull() ?: return@run
            val fileEditorManager = FileEditorManager.getInstance(project)
            val psiFile = fileEditorManager.selectedEditor?.file
                ?.let { PsiManager.getInstance(project).findFile(it) } ?: return@run
            if(DumbService.isDumb(project)) return@run

            DaemonCodeAnalyzer.getInstance(project).restart(psiFile)
        }
    }

}