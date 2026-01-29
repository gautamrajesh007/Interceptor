package com.proxy.interceptor.proxy;

import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;

import java.util.ArrayList;
import java.util.List;

public class ConnectionState {

    public final String connId;

    public volatile Channel serverChannel;
    public volatile boolean inExtendedBatch = false;
    public volatile boolean sslNegotiated = false;

    public StringBuilder batchQuery = new StringBuilder();
    public List<ByteBuf> batchBuffers = new ArrayList<>();

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
