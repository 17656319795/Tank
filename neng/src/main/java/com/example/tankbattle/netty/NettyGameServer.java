package com.example.tankbattle.netty;

import com.example.tankbattle.config.TankBattleProperties;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

@Component
public class NettyGameServer implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(NettyGameServer.class);

    private final TankBattleProperties properties;
    private final GameWebSocketInitializer initializer;

    private volatile boolean running;
    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private Channel serverChannel;
    private Thread serverThread;

    public NettyGameServer(TankBattleProperties properties, GameWebSocketInitializer initializer) {
        this.properties = properties;
        this.initializer = initializer;
    }

    @Override
    public void start() {
        if (running) {
            return;
        }
        running = true;
        serverThread = new Thread(() -> {
            bossGroup = new NioEventLoopGroup(1);
            workerGroup = new NioEventLoopGroup();
            try {
                ServerBootstrap bootstrap = new ServerBootstrap();
                bootstrap.group(bossGroup, workerGroup)
                        .channel(NioServerSocketChannel.class)
                        .childHandler(initializer);
                serverChannel = bootstrap.bind(properties.getNettyPort()).sync().channel();
                log.info("Netty game server started on port {}", properties.getNettyPort());
                serverChannel.closeFuture().sync();
            } catch (InterruptedException interruptedException) {
                Thread.currentThread().interrupt();
            } catch (Exception exception) {
                log.error("Failed to start netty game server", exception);
            } finally {
                shutdownGroups();
                running = false;
            }
        }, "tankbattle-netty-server");
        serverThread.setDaemon(true);
        serverThread.start();
    }

    @Override
    public void stop() {
        running = false;
        if (serverChannel != null) {
            serverChannel.close();
        }
        shutdownGroups();
    }

    private void shutdownGroups() {
        if (bossGroup != null) {
            bossGroup.shutdownGracefully();
            bossGroup = null;
        }
        if (workerGroup != null) {
            workerGroup.shutdownGracefully();
            workerGroup = null;
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public int getPhase() {
        return Integer.MAX_VALUE;
    }

    @Override
    public boolean isAutoStartup() {
        return true;
    }

    @Override
    public void stop(Runnable callback) {
        stop();
        callback.run();
    }
}
