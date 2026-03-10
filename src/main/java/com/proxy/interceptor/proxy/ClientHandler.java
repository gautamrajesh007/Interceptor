package com.proxy.interceptor.proxy;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.channel.*;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.ssl.SslHandler;
import lombok.extern.slf4j.Slf4j;

import java.net.InetSocketAddress;
import java.util.concurrent.TimeUnit;

@Slf4j
public class ClientHandler extends ChannelInboundHandlerAdapter {

    private final String connId;
    private final ConnectionState state;
    private final ProxyContext ctx;
    private volatile boolean backendReady;
    private Channel clientChannel;

    public ClientHandler(String connId,
                         ConnectionState state,
                         ProxyContext ctx,
                         Channel clientChannel) {
        this.connId = connId;
        this.state = state;
        this.ctx = ctx;
        this.clientChannel = clientChannel;
        this.backendReady = (ctx.sslContextFactory() == null);
    }

    /** Connection lifecycle */
    @Override
    public void channelActive(ChannelHandlerContext nettyCtx) {
        clientChannel = nettyCtx.channel();

        // Capture and store the PostgreSQL client's IP address
        if (nettyCtx.channel().remoteAddress() instanceof InetSocketAddress socketAddress) {
            state.setClientIp(socketAddress.getAddress().getHostAddress());
            log.debug("{}: Client connected from IP: {}", connId, state.getClientIp());
        }

        // Connect to the PostgreSQL db engine
        Bootstrap b = new Bootstrap();
        b.group(nettyCtx.channel().eventLoop())
                .channel(ctx.eventLoopGroupFactory().getSocketChannelClass())
                .option(ChannelOption.SO_KEEPALIVE, true)
                .option(ChannelOption.TCP_NODELAY, true)
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        if (ctx.sslContextFactory() != null) {
                            ch.pipeline().addLast(new BackendSslNegotiationHandler(
                                    connId,
                                    ctx.sslContextFactory(),
                                    ctx.targetHost(),
                                    ctx.targetPort(),
                                    () -> backendReady = true
                            ));
                        }

                        // Add the standard server handler
                        ch.pipeline().addLast(new ServerHandler(connId, clientChannel, ctx.metricsService()));
                    }
                });

        b.connect(ctx.targetHost(), ctx.targetPort()).addListener((ChannelFutureListener) future -> {
            if (future.isSuccess()) {
                state.setServerChannel(future.channel());
                log.debug("{}: Connected to PostgreSQL db engine", connId);
            } else {
                log.error("{}: Failed to connect to PostgreSQL", connId);
                ctx.metricsService().trackError();
                sendErrorToClient(nettyCtx, "Failed to connect to db engine");
                nettyCtx.close();
            }
        });
    }

    @Override
    public void channelRead(ChannelHandlerContext nettyCtx, Object msg) {
        ByteBuf buf = (ByteBuf) msg;

        try {
            // -------------------- Frontend TLS Negotiation (TLS A) --------------------
            // Handle PostgreSQL SSLRequest from the client (psql, DataGrip, etc.)
            if (!state.isSslNegotiated() && buf.readableBytes() == 8) {
                int readerIndex = buf.readerIndex();
                int length = buf.getInt(readerIndex);
                int code = buf.getInt(readerIndex + 4);

                // SSLRequest: length=8, code=80877103
                if (length == 8 && code == 80877103) {
                    if (ctx.sslContextFactory() != null) {
                        log.debug("{}: Received SSLRequest from client, responding 'S' (frontend TLS enabled)", connId);

                        // 1. Respond with 'S' — we accept SSL
                        ByteBuf response = nettyCtx.alloc().buffer(1);
                        response.writeByte('S');
                        nettyCtx.writeAndFlush(response).addListener((ChannelFutureListener) writeFuture -> {
                            if (writeFuture.isSuccess()) {
                                // 2. Install SslHandler at the front of the pipeline (TLS A)
                                SslHandler sslHandler = ctx.sslContextFactory().newFrontendHandler(nettyCtx.alloc());
                                nettyCtx.pipeline().addFirst("frontendSsl", sslHandler);

                                // 3. Wait for TLS handshake to complete before processing more data
                                sslHandler.handshakeFuture().addListener(handshakeFuture -> {
                                    if (handshakeFuture.isSuccess()) {
                                        log.info("{}: Frontend TLS handshake completed (TLS A)", connId);
                                        state.setFrontendSslDone(true);
                                    } else {
                                        log.error("{}: Frontend TLS handshake failed: {}", connId,
                                                handshakeFuture.cause().getMessage());
                                        nettyCtx.close();
                                    }
                                });
                            } else {
                                log.error("{}: Failed to send 'S' response to client", connId);
                                nettyCtx.close();
                            }
                        });
                    } else {
                        // SSL not configured — reject SSL negotiation
                        log.debug("{}: Received SSLRequest from client, responding 'N' (SSL not configured)", connId);
                        ByteBuf response = nettyCtx.alloc().buffer(1);
                        response.writeByte('N');
                        nettyCtx.writeAndFlush(response);
                    }

                    state.setSslNegotiated(true);
                    return;
                }
            }

            // Wait for server connection to be established
            if (state.getServerChannel() == null || !state.getServerChannel().isActive() || !backendReady) {
                log.debug("{}: Server not connected yet, buffering message", connId);
                scheduleForward(nettyCtx, buf.retain());
                return;
            }
            processClientMessage(nettyCtx, buf);
        } finally {
            buf.release();
        }
    }

    /** Message Processing */
    private void processClientMessage(ChannelHandlerContext nettyCtx, ByteBuf buf) {
        if (buf.readableBytes() < 1) {
            forwardToServer(buf.retain());
            return;
        }

        byte messageType = buf.getByte(buf.readerIndex());

        switch (messageType) {
            case 'Q' -> handleSimpleQuery(nettyCtx, buf);
            case 'P' -> handleParseMessage(buf);
            case 'S' -> handleSyncMessage(nettyCtx, buf);
            case 'B', 'D', 'E' -> handleExtendedProtocolMessage(buf);
            default -> forwardToServer(buf.retain());
        }
    }

    /** Simple Query */
    private void handleSimpleQuery(ChannelHandlerContext nettyCtx, ByteBuf buf) {
        var simpleQuery = ctx.protocolHandler().parseSimpleQuery(buf.duplicate());
        if (simpleQuery.isPresent()) {
            String sql = simpleQuery.get();
            ctx.metricsService().trackQuery("SIMPLE");

            if (ctx.sqlClassifier().shouldBlock(sql)) {
                log.info("{}: 🚫BLOCKED Simple Query: {}", connId, truncate(sql));
                ctx.metricsService().trackBlocked();

                ctx.blockedQueryService().addBlockedQuery(
                        connId,
                        "SIMPLE",
                        sql,
                        buf.retainedDuplicate(),
                        this::forwardToServer,
                        error -> sendErrorToClient(nettyCtx, error)
                );
                return;
            }
        }
        forwardToServer(buf.retain());
    }

    /** Extended Query */
    private void handleParseMessage(ByteBuf buf) {
        var extendedQuery = ctx.protocolHandler().parseExtendedQuery(buf.duplicate());
        if (extendedQuery.isPresent()) {
            String sql = extendedQuery.get();

            if (ctx.sqlClassifier().shouldBlock(sql)) {
                log.debug("{}: Starting blocked extended batch", connId);
                state.setInExtendedBatch(true);
                state.setBatchQuery(new StringBuilder(sql));
                state.getBatchBuffers().add(buf.retainedDuplicate());
                return;
            }
        }
        forwardToServer(buf.retain());
    }

    private void handleExtendedProtocolMessage(ByteBuf buf) {
        if (state.isInExtendedBatch()) {
            state.getBatchBuffers().add(buf.retainedDuplicate());
        } else {
            forwardToServer(buf.retain());
        }
    }

    private void handleSyncMessage(ChannelHandlerContext nettyCtx, ByteBuf buf) {
        if (!state.isInExtendedBatch()) {
            forwardToServer(buf.retain());
            return;
        }

        state.getBatchBuffers().add(buf.retainedDuplicate());
        String sql = state.getBatchQuery().toString();

        log.info("{}: 🚫BLOCKED Extended Query: {}", connId, truncate(sql));
        ctx.metricsService().trackQuery("EXTENDED");
        ctx.metricsService().trackBlocked();

        ByteBuf combinedBuf = nettyCtx.alloc().compositeBuffer()
                .addComponents(true, state.getBatchBuffers().toArray(new ByteBuf[0]));

        state.setInExtendedBatch(false);
        state.setBatchQuery(new StringBuilder());
        state.getBatchBuffers().clear();

        ctx.blockedQueryService().addBlockedQuery(
                connId,
                "EXTENDED",
                sql,
                combinedBuf,
                this::forwardToServer,
                error -> sendErrorToClient(nettyCtx, error)
        );
    }

    /** Forwarding helpers */
    private void forwardToServer(ByteBuf buf) {
        if (state.getServerChannel() != null && state.getServerChannel().isActive()) {
            state.getServerChannel().writeAndFlush(buf);
        } else {
            buf.release(); // prevent leak if server not available
            log.warn("{}: Cannot forward - server channel inactive", connId);
        }
    }

    private void scheduleForward(ChannelHandlerContext nettyCtx, ByteBuf buf) {
        nettyCtx.channel().eventLoop().schedule(() -> {
            if (state.getServerChannel() != null && state.getServerChannel().isActive() && backendReady) {
                try {
                    processClientMessage(nettyCtx, buf);
                } finally {
                    buf.release();
                }
            } else if (nettyCtx.channel().isActive()) {
                scheduleForward(nettyCtx, buf);
            } else {
                buf.release();
            }
        }, 10, TimeUnit.MILLISECONDS);
    }

    private void sendErrorToClient(ChannelHandlerContext nettyCtx, String message) {
        if (!nettyCtx.channel().isActive()) return;

        ByteBuf error = ctx.protocolHandler().createErrorResponse(message);
        ByteBuf ready = ctx.protocolHandler().createReadyForQuery();
        nettyCtx.write(error);
        nettyCtx.writeAndFlush(ready);
    }

    /** Cleanup */
    @Override
    public void channelInactive(ChannelHandlerContext nettyCtx) {
        log.info("{}: Client disconnected", connId);
        ctx.connections().remove(connId);
        ctx.metricsService().trackDisconnection();
        ctx.blockedQueryService().cleanupConnection(connId);
        state.resetBatch();

        if (state.getServerChannel() != null) {
            state.getServerChannel().close();
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext nettyCtx, Throwable cause) {
        log.error("{}: Client error: {}", connId, cause.getMessage());
        ctx.metricsService().trackError();
        state.resetBatch();
        nettyCtx.close();
    }

    /** Utilities */
    private String truncate(String s) {
        return s.length() > 100 ? s.substring(0, 100) + "..." : s;
    }
}
