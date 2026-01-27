package com.proxy.interceptor.proxy;

import io.netty.channel.IoHandlerFactory;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.ServerChannel;
import io.netty.channel.epoll.Epoll;
import io.netty.channel.epoll.EpollIoHandler;
import io.netty.channel.epoll.EpollServerSocketChannel;
import io.netty.channel.epoll.EpollSocketChannel;
import io.netty.channel.kqueue.KQueue;
import io.netty.channel.kqueue.KQueueIoHandler;
import io.netty.channel.kqueue.KQueueServerSocketChannel;
import io.netty.channel.kqueue.KQueueSocketChannel;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class EventLoopGroupFactory {

    private final IoHandlerFactory ioHandlerFactory;
    @Getter
    private final Class<? extends ServerChannel> serverChannelClass;
    @Getter
    private final Class<? extends SocketChannel> socketChannelClass;

    public EventLoopGroupFactory() {
        // Try to use the best available transport
        IoHandlerFactory factory = null;
        Class<? extends ServerChannel> serverClass = null;
        Class<? extends SocketChannel> socketClass = null;

        // Epoll (Linux)
        if (isEpollAvailable()) {
            try {
                factory = EpollIoHandler.newFactory();
                serverClass = EpollServerSocketChannel.class;
                socketClass = EpollSocketChannel.class;
                log.info("Using Epoll transport (Linux optimized)");
            } catch (Exception e) {
                log.debug("Epoll not available: {}", e.getMessage());
            }
        }

        // KQueue (macOS/BSD)
        if (factory == null && isKQueueAvailable()) {
            try {
                factory = KQueueIoHandler.newFactory();
                serverClass = KQueueServerSocketChannel.class;
                socketClass = KQueueSocketChannel.class;
                log.info("Using KQueue transport (macOS optimized)");
            } catch (Exception e) {
                log.debug("KQueue not available: {}", e.getMessage());
            }
        }

        // Fallback to NIO
        if (factory == null) {
            factory = NioIoHandler.newFactory();
            serverClass = NioServerSocketChannel.class;
            socketClass = NioSocketChannel.class;
            log.info("Using NIO transport (cross-platform)");
        }

        this.ioHandlerFactory = factory;
        this.serverChannelClass = serverClass;
        this.socketChannelClass = socketClass;
    }

    public MultiThreadIoEventLoopGroup createBossGroup() {
        return new MultiThreadIoEventLoopGroup(1, ioHandlerFactory);
    }

    public MultiThreadIoEventLoopGroup createWorkerGroup() {
        return new MultiThreadIoEventLoopGroup(ioHandlerFactory);
    }

    public MultiThreadIoEventLoopGroup createWorkerGroup(int threads) {
        return new MultiThreadIoEventLoopGroup(threads, ioHandlerFactory);
    }

    private boolean isEpollAvailable() {
        try {
            return Epoll.isAvailable();
        } catch (NoClassDefFoundError e) {
            return false;
        }
    }

    private boolean isKQueueAvailable() {
        try {
            return KQueue.isAvailable();
        } catch (NoClassDefFoundError e) {
            return false;
        }
    }
}
