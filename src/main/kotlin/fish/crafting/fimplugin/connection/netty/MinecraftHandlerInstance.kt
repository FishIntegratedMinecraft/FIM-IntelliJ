package fish.crafting.fimplugin.connection.netty

import com.intellij.openapi.diagnostic.thisLogger
import fish.crafting.fimplugin.connection.focuser.ProgramFocuser
import fish.crafting.fimplugin.connection.packets.I2FConfirmPacket
import fish.crafting.fimplugin.connection.packetsystem.PacketManager
import io.ktor.util.date.getTimeMillis
import io.netty.buffer.ByteBuf
import io.netty.channel.Channel
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.SimpleChannelInboundHandler
import java.util.UUID

class MinecraftHandlerInstance(val channel: Channel) : SimpleChannelInboundHandler<ByteBuf>() {

    private var uuid: UUID? = null
    private var windowFocuser: ProgramFocuser? = null
    private var markedAsEnd = false
    val createdAt = getTimeMillis()

    override fun channelRead0(ctx: ChannelHandlerContext?, msg: ByteBuf?) {
        if(ctx == null || msg == null) return

        PacketManager.handleReceivedPacket(this, msg)
    }

    fun focus(): Boolean{
        return windowFocuser?.focus() ?: false
    }

    fun initialized() = uuid != null

    fun send(buf: ByteBuf) {
        if(channel.isActive) {
            val future = channel.writeAndFlush(buf)

            //TODO fix this eventually, maybe add an actual queue system??
            future.sync()
        }
    }

    fun init(uuid: UUID, compVer: Int, focuser: ProgramFocuser?) {
        if(this.uuid != null) {
            thisLogger().error("Minecraft Handler received initialize packet of UUID '$uuid', but it has already been initialized!")
            return
        }

        if(compVer != ConnectionConstants.COMPATIBILITY_VERSION) {
            thisLogger().warn("Mismatched compatibility versions! IntelliJ: ${ConnectionConstants.COMPATIBILITY_VERSION}, Minecraft: $compVer ($uuid)")

            I2FConfirmPacket(false).send(this)

            endConnection()
            return
        }

        I2FConfirmPacket(true).send(this)
        thisLogger().info("Successfully initialized connection with client ID '$uuid'!")
        this.uuid = uuid
        this.windowFocuser = focuser
    }

    private fun endConnection() {
        if(markedAsEnd) return
        markedAsEnd = true
        ConnectionManager.getServer()?.queueEndConnection(this)
    }


}