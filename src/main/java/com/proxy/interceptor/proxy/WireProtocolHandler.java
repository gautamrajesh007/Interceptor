package com.proxy.interceptor.proxy;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

@Component
@Slf4j
public class WireProtocolHandler {

    /*
    * Parse a Simple Query(Q) message and extract the SQL.
    * Format: 'Q' (1 byte) + Length (4 bytes) + Query String + null-terminator (1 byte)
     */
    public Optional<String> parseSimpleQuery(ByteBuf buf) {
        if (buf.readableBytes() < 5) {
            return Optional.empty();
        }

        buf.markReaderIndex();
        byte messageType = buf.readByte();

        if (messageType != 'Q') {
            buf.resetReaderIndex();
            return Optional.empty();
        }

        int length = buf.readInt(); // Includes itself but not the type byte
        int queryLength = length - 4 - 1; // Subtract length field and null terminator

        if (buf.readableBytes() < queryLength + 1) {
            buf.resetReaderIndex();
            return Optional.empty();
        }

        byte[] queryBytes = new byte[queryLength];
        buf.readBytes(queryBytes);
        buf.readByte(); // Skip null terminator

        String sql = new String(queryBytes, StandardCharsets.UTF_8);
        log.debug("Parsed Simple Query: {}", sql.substring(0, Math.min(100, sql.length())));

        return Optional.of(sql);
    }

    /*
    * Parse a Parse (P) message to extract query preview for Extended Protocol.
    * Format: 'P' (1 byte) + Length (4 bytes) + Statement Name (C-string) + Query (C-string) + ...
     */

    public Optional<String> parseExtendedQuery(ByteBuf buf) {
        if (buf.readableBytes() < 5) {
            return Optional.empty();
        }

        buf.markReaderIndex();
        try {
            byte messageType = buf.readByte();

            if (messageType != 'P') {
                buf.resetReaderIndex();
                return Optional.empty();
            }

            int length = buf.readInt();
            if (length < 4) {
                buf.resetReaderIndex();
                return Optional.empty();
            }

            // Remaining bytes after reading 4 byte length
            int remainingBytes = length - 4;
            if (buf.readableBytes() < remainingBytes) {
                buf.resetReaderIndex();
                return Optional.empty();
            }

            // Prevent reading into next message
            int messageEndIndex = buf.readerIndex() + remainingBytes;

            // Skip statement name (C-string)
            if (!skipCString(buf, messageEndIndex)) {
                buf.resetReaderIndex();
                return Optional.empty();
            }

            // Read query (C-string) as UTF-8 bytes
            Optional<String> sqlOpt = readCStringUtf8(buf, messageEndIndex);
            if (sqlOpt.isEmpty()) {
                buf.resetReaderIndex();
                return Optional.empty();
            }

            String sql = sqlOpt.get();

            buf.resetReaderIndex();

            log.debug("Parsed Extended Query: {}", sql.substring(0, Math.min(100, sql.length())));

            return Optional.of(sql);
        } catch (IndexOutOfBoundsException e) {
            buf.resetReaderIndex();
            return Optional.empty();
        }
    }

    /*
    * Create a PostgreSQL ErrorResponse message
     */
    public ByteBuf createErrorResponse(String message) {
        // Error fields: S (Severity), V (Severity non-localized), C (Code), M (Message)
        byte[] severity = "ERROR".getBytes(StandardCharsets.UTF_8);
        byte[] code = "20000".getBytes(StandardCharsets.UTF_8);
        byte[] msg = message.getBytes(StandardCharsets.UTF_8);

        int length = 4 + // Length field
                    1 + severity.length + 1 + // 'S' + severity + null
                    1 + severity.length + 1 + // 'V' + severity + null
                    1 + code.length + 1 + // 'C' + code + null
                    1 + msg.length + 1 + // 'M' + message + null
                    1;                      // Null terminator

        ByteBuf buf = Unpooled.buffer(1 + length);
        buf.writeByte('E'); // ErrorResponse
        buf.writeInt(length);

        buf.writeByte('S');
        buf.writeBytes(severity);
        buf.writeByte(0);

        buf.writeByte('V');
        buf.writeBytes(severity);
        buf.writeByte(0);

        buf.writeByte('C');
        buf.writeBytes(code);
        buf.writeByte(0);

        buf.writeByte('M');
        buf.writeBytes(msg);
        buf.writeByte(0);

        buf.writeByte(0); // End of error fields

        return buf;
    }

    /*
    * Create a ReadyForQuery message to unblock the client.
     */
    public ByteBuf createReadyForQuery() {
        ByteBuf buf = Unpooled.buffer(6);
        buf.writeByte('Z'); // ReadyForQuery
        buf.writeInt(5); // Length
        buf.writeByte('I'); // Transaction status: Idle
        return buf;
    }

    /*
    * Check if this is a Sync message (end of extended protocol batch).
     */
    public boolean isSyncMessage(ByteBuf buf) {
        if (buf.readableBytes() < 5) return false;
        buf.markReaderIndex();
        byte type = buf.readByte();
        buf.resetReaderIndex();
        return type == 'S';
    }

    /*
    * Get message type from buffer without consuming
     */
    public char peekMessageType(ByteBuf buf) {
        if (buf.readableBytes() < 1) return '\0';
        return (char) buf.getByte(buf.readerIndex());
    }

    /*
    * Skips a null-terminated. C-string within (currentReaderIndex, messageEndIndex).
    * Returns true if we found a null terminator, false otherwise.
     */
    private boolean skipCString(ByteBuf buf, int messageEndIndex) {
        while (buf.readerIndex() < messageEndIndex) {
            if (buf.readByte() == 0) {
                return true; // null-terminator
            }
        }
        return false; // null-terminator not found
    }

    /*
    * Reads a null-terminated C-string as UTF-8 within (currentReaderIndex, messageEndIndex).
    * Returns Optional.empty() if terminator not found.
     */
    private Optional<String> readCStringUtf8(ByteBuf buf, int messageEndIndex) {
        int start = buf.readerIndex();

        int end = start;
        while (end < messageEndIndex) {
            if (buf.getByte(end) == 0) {
                break;
            }
            end++;
        }

        if (end >= messageEndIndex) {
            return Optional.empty();
        }

        int len = end - start;

        byte[] bytes = new byte[len];
        buf.readBytes(bytes);

        buf.readByte();

        return Optional.of(new String(bytes, StandardCharsets.UTF_8));
    }
}
