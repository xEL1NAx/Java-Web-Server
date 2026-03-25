package server;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

public final class WebSocketFrame {
    public final int opcode;
    public final boolean fin;
    public final byte[] payload;

    public WebSocketFrame(int opcode, boolean fin, byte[] payload) {
        this.opcode = opcode;
        this.fin = fin;
        this.payload = payload == null ? new byte[0] : payload;
    }

    public static WebSocketFrame text(String message) {
        return new WebSocketFrame(0x1, true, message.getBytes(StandardCharsets.UTF_8));
    }

    public static WebSocketFrame binary(byte[] data) {
        return new WebSocketFrame(0x2, true, data);
    }

    public static WebSocketFrame pong(byte[] data) {
        return new WebSocketFrame(0xA, true, data);
    }

    public static WebSocketFrame close(byte[] data) {
        return new WebSocketFrame(0x8, true, data);
    }

    public ByteBuffer toBuffer() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int b0 = (fin ? 0x80 : 0) | (opcode & 0x0F);
        out.write(b0);
        int len = payload.length;
        if (len <= 125) {
            out.write(len);
        } else if (len <= 0xFFFF) {
            out.write(126);
            out.write((len >> 8) & 0xFF);
            out.write(len & 0xFF);
        } else {
            out.write(127);
            for (int i = 7; i >= 0; i--) {
                out.write((len >> (8 * i)) & 0xFF);
            }
        }
        out.writeBytes(payload);
        return ByteBuffer.wrap(out.toByteArray());
    }

    public static ParseResult tryParse(byte[] input, int length) throws IOException {
        if (length < 2) return ParseResult.needMore();
        int b0 = input[0] & 0xFF;
        int b1 = input[1] & 0xFF;
        boolean fin = (b0 & 0x80) != 0;
        int opcode = b0 & 0x0F;
        boolean masked = (b1 & 0x80) != 0;
        long payloadLen = b1 & 0x7F;
        int pos = 2;

        if (payloadLen == 126) {
            if (length < pos + 2) return ParseResult.needMore();
            payloadLen = ((input[pos] & 0xFF) << 8) | (input[pos + 1] & 0xFF);
            pos += 2;
        } else if (payloadLen == 127) {
            if (length < pos + 8) return ParseResult.needMore();
            payloadLen = 0;
            for (int i = 0; i < 8; i++) {
                payloadLen = (payloadLen << 8) | (input[pos + i] & 0xFF);
            }
            pos += 8;
        }
        byte[] mask = null;
        if (masked) {
            if (length < pos + 4) return ParseResult.needMore();
            mask = new byte[] { input[pos], input[pos + 1], input[pos + 2], input[pos + 3] };
            pos += 4;
        }
        if (payloadLen > Integer.MAX_VALUE) throw new IOException("Frame too large");
        if (length < pos + payloadLen) return ParseResult.needMore();
        byte[] payload = new byte[(int) payloadLen];
        System.arraycopy(input, pos, payload, 0, (int) payloadLen);
        if (masked) {
            for (int i = 0; i < payload.length; i++) {
                payload[i] = (byte) (payload[i] ^ mask[i % 4]);
            }
        }
        return ParseResult.frame(new WebSocketFrame(opcode, fin, payload), pos + payload.length);
    }

    public static final class ParseResult {
        public final WebSocketFrame frame;
        public final int consumed;
        public final boolean needMore;

        private ParseResult(WebSocketFrame frame, int consumed, boolean needMore) {
            this.frame = frame;
            this.consumed = consumed;
            this.needMore = needMore;
        }

        public static ParseResult frame(WebSocketFrame frame, int consumed) {
            return new ParseResult(frame, consumed, false);
        }

        public static ParseResult needMore() {
            return new ParseResult(null, 0, true);
        }
    }
}
