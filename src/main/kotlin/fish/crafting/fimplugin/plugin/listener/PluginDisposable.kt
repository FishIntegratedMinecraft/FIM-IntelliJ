package fish.crafting.fimplugin.plugin.listener

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.WindowManager
import fish.crafting.fimplugin.connection.netty.ConnectionManager
import fish.crafting.fimplugin.connection.packets.I2FIntelliJFocusedPacket
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent

@Service
class PluginDisposable: Disposable {

    companion object {
        fun getInstance(): Disposable {
            return ApplicationManager.getApplication().getService(PluginDisposable::class.java)
        }
    }

    override fun dispose() {
        ConnectionManager.shutdown()
    }
}