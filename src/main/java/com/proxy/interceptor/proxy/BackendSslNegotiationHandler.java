package com.proxy.interceptor.proxy;

import com.proxy.interceptor.config.SslContextFactory;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import lombok.extern.slf4j.Slf4j;

/**
 * Handles the PostgreSQL SSL negotiation with the backend database.
 * Sends SSLRequest, waits for 'S' response, then installs SslHandler.
 */
@Slf4j
public class BackendSslNegotiationHandler extends ChannelInboundHandlerAdapter {

    private final String connId;
    private final SslContextFactory sslContextFactory;
    private final String targetHost;
    private final int targetPort;
    private final Runnable onSslReady;

    public BackendSslNegotiationHandler(String connId,
                                        SslContextFactory sslContextFactory,
                                        String targetHost,
                                        int targetPort,
                                        Runnable onSslReady
    ) {
        this.connId = connId;
        this.sslContextFactory = sslContextFactory;
        this.targetHost = targetHost;
        this.targetPort = targetPort;
        this.onSslReady = onSslReady;
    }

    /** Send PostgreSQL SSLRequest (8 bytes: length 8, magic code 80877103) */
    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        ByteBuf sslRequest = ctx.alloc().buffer(8);
        sslRequest.writeInt(8);
        sslRequest.writeInt(80877103);
        ctx.writeAndFlush(sslRequest);
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        ByteBuf buf = (ByteBuf) msg;
        if (buf.readableBytes() > 0) {
            byte response = buf.readByte();
            if (response == 'S') {
                // DB agreed to SSL — install backend SslHandler (TLS B)
                ctx.pipeline().addFirst(
                        sslContextFactory.newBackendHandler(ctx.alloc(), targetHost, targetPort)
                );
                log.debug("{}: Upgraded backend connection to TLS (TLS B)", connId);
            } else {
                log.error("{}: PostgreSQL server rejected SSL request (responded with '{}')", connId, (char) response);
            }

            // Remove this temporary startup handler as negotiation is done
            ctx.pipeline().remove(this);

            // Signal the proxy that it is now safe to forward client data
            onSslReady.run();

            // Forward any remaining bytes in this buffer to the next handler
            if (buf.isReadable()) {
                ctx.fireChannelRead(buf);
            } else {
                buf.release();
            }
        } else {
            buf.release();
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.error("{}: BackendSslNegotiationHandler error: {}", connId, cause.getMessage());
        ctx.close();
    }
}