package com.proxy.interceptor.proxy;

import com.proxy.interceptor.service.MetricsService;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;

@Slf4j
public class ServerHandler extends ChannelInboundHandlerAdapter {

    private final String connId;
    private final Channel clientChannel;
    private final MetricsService metricsService;

    public ServerHandler(String connId,
                         Channel clientChannel,
                         MetricsService metricsService) {
        this.connId = connId;
        this.clientChannel = clientChannel;
        this.metricsService = metricsService;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        if (msg instanceof ByteBuf) {
            ByteBuf buf = (ByteBuf) msg;

            // Prevent SCRAM-SHA-256-PLUS channel binding errors for non-SSL frontend clients.
            // Authentication packet format: 'R' (1 byte), Length (4 bytes), AuthType (4 bytes = 10 for SASL)
            if (buf.readableBytes() >= 9) {
                int readerIndex = buf.readerIndex();
                if (buf.getByte(readerIndex) == 'R') {
                    int authType = buf.getInt(readerIndex + 5);
                    if (authType == 10) {
                        int msgLength = buf.getInt(readerIndex + 1);
                        int searchEnd = Math.min(buf.writerIndex(), readerIndex + 1 + msgLength);
                        byte[] plusBytes = "-PLUS".getBytes(StandardCharsets.UTF_8);

                        for (int i = readerIndex + 9; i <= searchEnd - plusBytes.length; i++) {
                            boolean match = true;
                            for (int j = 0; j < plusBytes.length; j++) {
                                if (buf.getByte(i + j) != plusBytes[j]) {
                                    match = false;
                                    break;
                                }
                            }
                            if (match) {
                                // We found "-PLUS". Instead of overwriting with nulls,
                                // we cleanly remove it by shifting the rest of the packet left.
                                int shiftStart = i + plusBytes.length;
                                int shiftLength = buf.writerIndex() - shiftStart;

                                // 1. Shift remaining bytes to the left
                                for (int k = 0; k < shiftLength; k++) {
                                    buf.setByte(i + k, buf.getByte(shiftStart + k));
                                }

                                // 2. Update the packet length field to reflect the removed bytes
                                buf.setInt(readerIndex + 1, msgLength - plusBytes.length);

                                // 3. Update the buffer's writer index
                                buf.writerIndex(buf.writerIndex() - plusBytes.length);

                                log.debug("{}: Stripped SCRAM channel binding (-PLUS) from AuthenticationSASL", connId);
                                break;
                            }
                        }
                    }
                }
            }
        }

        // Forward server response to client
        if (clientChannel.isActive()) {
            clientChannel.writeAndFlush(msg);
        } else {
            // If client is dead, release message to avoid leaks
            if (msg instanceof ByteBuf) {
                ((ByteBuf) msg).release();
            }
        }
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        log.debug("{}: Server connection closed", connId);
        if (clientChannel.isActive()) {
            clientChannel.close();
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.error("{}: Server error: {}", connId, cause.getMessage());
        metricsService.trackError();
        ctx.close();
    }
}