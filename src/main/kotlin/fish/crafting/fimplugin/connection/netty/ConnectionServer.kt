package fish.crafting.fimplugin.connection.netty

import ai.grazie.utils.chainIf
import com.google.common.util.concurrent.ThreadFactoryBuilder
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.externalSystem.util.wsl.connectRetrying
import fish.crafting.fimplugin.plugin.util.EditorUtil
import io.ktor.util.date.getTimeMillis
import io.netty.bootstrap.ServerBootstrap
import io.netty.buffer.ByteBuf
import io.netty.channel.Channel
import io.netty.channel.ChannelFuture
import io.netty.channel.ChannelInitializer
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.socket.nio.NioServerSocketChannel
import io.netty.handler.ipfilter.IpFilterRule
import io.netty.handler.ipfilter.IpFilterRuleType
import io.netty.handler.ipfilter.RuleBasedIpFilter
import io.netty.handler.timeout.ReadTimeoutHandler
import java.net.InetSocketAddress
import java.util.*

class ConnectionServer internal constructor() {

    companion object{
        val LOOP_GROUP_SUPPLIER : NioEventLoopGroup by lazy {
            NioEventLoopGroup(
                0,
                ThreadFactoryBuilder().setNameFormat("Netty Server #%d").setDaemon(true).build()
            )
        }

        private var lastRefreshedGutters = 0L
    }

    private val port = ConnectionConstants.CONNECTION_PORT
    private val channels = Collections.synchronizedList(arrayListOf<ChannelFuture>())
    private val connections = Collections.synchronizedList(arrayListOf<MinecraftHandlerInstance>())
    private val queuedEndConnections = Collections.synchronizedList(arrayListOf<MinecraftHandlerInstance>())
    private var latestInstance: MinecraftHandlerInstance? = null

    fun getLatestInstance(): MinecraftHandlerInstance? {
        if(latestInstance != null) return latestInstance
        return connections.firstOrNull()
    }

    fun sendToAllConnections(buf: ByteBuf){
        connections.forEach {
            it.send(buf)
        }
    }

    fun markAsLatest(instance: MinecraftHandlerInstance) {
        if(latestInstance in connections){
            latestInstance = instance
        }
    }

    fun run(){
        thisLogger().info("Starting netty server!")
        synchronized(channels){
            val group = LOOP_GROUP_SUPPLIER

            val bootstrap = ServerBootstrap()
                .group(group)
                .channel(NioServerSocketChannel::class.java)
                .childHandler(object : ChannelInitializer<Channel>() {
                    override fun initChannel(ch: Channel?) {
                        if(ch != null) handleNewChannel(ch)
                    }
                })

            channels.add(bootstrap.bind(port).syncUninterruptibly())

            thisLogger().info("Netty server successfully started.")
        }
    }

    fun queueEndConnection(handler: MinecraftHandlerInstance) {
        synchronized(queuedEndConnections) {
            queuedEndConnections.add(handler)
        }
    }

    fun shutdown() {
        thisLogger().info("Shutting down netty server!")

        for (channelFuture in channels) {
            try {
                channelFuture.channel().close().sync()
            } catch (e: InterruptedException) {
                thisLogger().error("Interrupted whilst closing channel")
            }
        }

        //Is this correct?
        LOOP_GROUP_SUPPLIER.shutdownGracefully()

        thisLogger().info("Netty server successfully shut down.")
    }

    fun tick() {
        synchronized(connections) {
            val iterator = connections.iterator()

            synchronized(queuedEndConnections) {
                queuedEndConnections.forEach {
                    if(it.channel.isOpen){
                        it.channel.close()
                    }
                }

                queuedEndConnections.clear()
            }

            var cleaned = 0

            while(iterator.hasNext()) {
                val handler = iterator.next()
                if(!handler.channel.isOpen){
                    if(latestInstance == handler) {
                        latestInstance = null
                    }

                    iterator.remove()
                    cleaned++

                }else if(handlerNotInitializedCheck(handler)) {
                    thisLogger().warn("A handler was closed because it hadn't been initialized in a correct amount of time!")

                    handler.channel.close()
                    iterator.remove()
                    cleaned++
                }
            }

            if(cleaned > 0) {
                thisLogger().info("Cleaned $cleaned connections!")
            }
        }
    }

    /**
     * Checks whether the provided handler hasn't been initialized for a long
     * enough time, where we should just remove it
     */
    private fun handlerNotInitializedCheck(handler: MinecraftHandlerInstance): Boolean {
        return !handler.initialized() && (getTimeMillis() - handler.createdAt) > ConnectionConstants.NOT_INITIALIZED_TIMEOUT
    }

    private fun handleNewChannel(ch: Channel) {
        synchronized(connections) {
            val remoteAddress = ch.remoteAddress()
            if(remoteAddress == null) return

            val pipeline = ch.pipeline()

            val connectionHandler = MinecraftHandlerInstance(ch)
            pipeline.addLast("ipfilter", RuleBasedIpFilter(LocalhostFilter()))
            pipeline.addLast("timeout", ReadTimeoutHandler(30));
            pipeline.addLast("handler", connectionHandler)

            connections.add(connectionHandler)
            if(connections.size == 1) { //Wow! First Connection!
                val d = System.currentTimeMillis() - lastRefreshedGutters

                if(d > 15_000){ //15s
                    EditorUtil.refreshGutters()
                    lastRefreshedGutters = System.currentTimeMillis()
                }
            }
        }
    }
    fun hasInstance() = !connections.isEmpty()

    inner class LocalhostFilter: IpFilterRule {
        override fun matches(remoteAddress: InetSocketAddress?): Boolean {
            if(remoteAddress == null) return true
            if(remoteAddress.address.isLoopbackAddress) return false
            return true
        }

        override fun ruleType() = IpFilterRuleType.REJECT

    }

}