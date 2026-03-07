package com.proxy.interceptor.proxy;

import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

@Getter
@Setter
public class ConnectionState {

    private final String connId;
    private volatile Channel serverChannel;
    private volatile boolean inExtendedBatch = false;
    private volatile boolean sslNegotiated = false;
    private volatile boolean frontendSslDone = false;
    private StringBuilder batchQuery = new StringBuilder();
    private final List<ByteBuf> batchBuffers = new ArrayList<>();

    public ConnectionState(String connId) {
        this.connId = connId;
    }

    public void resetBatch() {
        inExtendedBatch = false;
        batchQuery.setLength(0);
        for (ByteBuf buf : batchBuffers) {
            if (buf.refCnt() > 0) {
                buf.release();
            }
        }
        batchBuffers.clear();
    }
}