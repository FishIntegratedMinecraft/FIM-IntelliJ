package fish.crafting.fimplugin.connection.packetsystem

import fish.crafting.fimplugin.connection.netty.MinecraftHandlerInstance
import fish.crafting.fimplugin.connection.packets.F2IDoBoundingBoxEditPacket
import fish.crafting.fimplugin.connection.packets.F2IDoLocationEditPacket
import fish.crafting.fimplugin.connection.packets.F2IDoVectorEditPacket
import fish.crafting.fimplugin.connection.packets.F2IFocusPacket
import fish.crafting.fimplugin.connection.packets.F2IInitPacket
import fish.crafting.fimplugin.connection.packets.F2IMinecraftFocusedPacket
import io.netty.buffer.ByteBuf
import io.netty.buffer.ByteBufInputStream
import io.netty.channel.ChannelHandlerContext

object PacketManager {

    private val packetMap = mutableMapOf<String, InPacket>()

    init {
        register(PacketId("f2i_init"), F2IInitPacket)
        register(PacketId("f2i_focus"), F2IFocusPacket)
        register(PacketId("f2i_edit_vector"), F2IDoVectorEditPacket)
        register(PacketId("f2i_edit_location"), F2IDoLocationEditPacket)
        register(PacketId("f2i_mc_focused"), F2IMinecraftFocusedPacket)
        register(PacketId("f2i_edit_boundingbox"), F2IDoBoundingBoxEditPacket)
    }

    fun handleReceivedPacket(handler: MinecraftHandlerInstance, buf: ByteBuf) {
        ByteBufInputStream(buf).use { stream ->
            val packetID = stream.readUTF()

            val inPacket: InPacket? = packetMap[packetID]
            inPacket?.readAndExecute(handler, stream)
        }
    }

    private fun register(id: PacketId, packet: InPacket) {
        packetMap.put(id.compile(), packet)
    }

}