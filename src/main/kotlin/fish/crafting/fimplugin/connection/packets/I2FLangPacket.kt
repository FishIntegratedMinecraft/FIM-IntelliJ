package fish.crafting.fimplugin.connection.packets

import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.ProjectManager
import com.intellij.psi.PsiManager
import fish.crafting.fimplugin.connection.focuser.FocuserType
import fish.crafting.fimplugin.connection.netty.ConnectionConstants
import fish.crafting.fimplugin.connection.netty.MinecraftHandlerInstance
import fish.crafting.fimplugin.connection.packetsystem.OutPacket
import fish.crafting.fimplugin.connection.packetsystem.PacketId
import fish.crafting.fimplugin.plugin.util.javakotlin.isJava
import fish.crafting.fimplugin.plugin.util.javakotlin.isKotlin
import io.netty.buffer.ByteBufOutputStream

/**
 * Tells Minecraft which language to use when copying values like vectors
 */
class I2FLangPacket(val kotlin: Boolean) : OutPacket() {

    companion object{
        private val ID = PacketId("i2f_lang")

        fun sendCurrentFile(instance: MinecraftHandlerInstance? = null) {
            val project = ProjectManager.getInstance().openProjects.firstOrNull() ?: return
            val fileEditorManager = FileEditorManager.getInstance(project)
            val psiFile = fileEditorManager.selectedEditor?.file
                ?.let { PsiManager.getInstance(project).findFile(it) } ?: return

            val language = psiFile.language
            val packet = if(language.isKotlin) {
                I2FLangPacket(true)
            }else if(language.isJava){
                I2FLangPacket(false)
            }else {
                null
            }

            packet ?: return

            if(instance != null){
                packet.send(instance)
            }else{
                packet.sendToAll()
            }
        }
    }

    override fun getId() = ID

    override fun write(stream: ByteBufOutputStream) {
        stream.writeBoolean(kotlin)
    }
}