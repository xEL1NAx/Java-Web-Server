package server;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;

final class Http2Frame {
    static final int TYPE_DATA = 0x0;
    static final int TYPE_HEADERS = 0x1;
    static final int TYPE_PRIORITY = 0x2;
    static final int TYPE_RST_STREAM = 0x3;
    static final int TYPE_SETTINGS = 0x4;
    static final int TYPE_PUSH_PROMISE = 0x5;
    static final int TYPE_PING = 0x6;
    static final int TYPE_GOAWAY = 0x7;
    static final int TYPE_WINDOW_UPDATE = 0x8;
    static final int TYPE_CONTINUATION = 0x9;

    static final int FLAG_END_STREAM = 0x1;
    static final int FLAG_ACK = 0x1;
    static final int FLAG_END_HEADERS = 0x4;
    static final int FLAG_PADDED = 0x8;
    static final int FLAG_PRIORITY = 0x20;

    static final int SETTING_HEADER_TABLE_SIZE = 0x1;
    static final int SETTING_ENABLE_PUSH = 0x2;
    static final int SETTING_MAX_CONCURRENT_STREAMS = 0x3;
    static final int SETTING_INITIAL_WINDOW_SIZE = 0x4;
    static final int SETTING_MAX_FRAME_SIZE = 0x5;
    static final int SETTING_MAX_HEADER_LIST_SIZE = 0x6;
    static final int SETTING_ENABLE_CONNECT_PROTOCOL = 0x8;

    static final int ERROR_NO_ERROR = 0x0;
    static final int ERROR_PROTOCOL_ERROR = 0x1;
    static final int ERROR_INTERNAL_ERROR = 0x2;
    static final int ERROR_FLOW_CONTROL_ERROR = 0x3;
    static final int ERROR_SETTINGS_TIMEOUT = 0x4;
    static final int ERROR_STREAM_CLOSED = 0x5;
    static final int ERROR_FRAME_SIZE_ERROR = 0x6;
    static final int ERROR_REFUSED_STREAM = 0x7;
    static final int ERROR_CANCEL = 0x8;
    static final int ERROR_COMPRESSION_ERROR = 0x9;
    static final int ERROR_CONNECT_ERROR = 0xa;
    static final int ERROR_ENHANCE_YOUR_CALM = 0xb;
    static final int ERROR_INADEQUATE_SECURITY = 0xc;
    static final int ERROR_HTTP_1_1_REQUIRED = 0xd;

    static final byte[] CLIENT_PREFACE = "PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n".getBytes(java.nio.charset.StandardCharsets.US_ASCII);

    final int length;
    final int type;
    final int flags;
    final int streamId;
    final byte[] payload;

    Http2Frame(int length, int type, int flags, int streamId, byte[] payload) {
        this.length = length;
        this.type = type;
        this.flags = flags;
        this.streamId = streamId;
        this.payload = payload == null ? new byte[0] : payload;
    }

    static Http2Frame read(InputStream in, int maxFrameSize) throws IOException {
        byte[] header = in.readNBytes(9);
        if (header.length == 0) {
            return null;
        }
        if (header.length < 9) {
            throw new EOFException("Unexpected EOF while reading HTTP/2 frame header");
        }
        int length = ((header[0] & 0xFF) << 16) | ((header[1] & 0xFF) << 8) | (header[2] & 0xFF);
        if (length > maxFrameSize) {
            throw new IOException("HTTP/2 frame exceeds peer max frame size: " + length);
        }
        int type = header[3] & 0xFF;
        int flags = header[4] & 0xFF;
        int streamId = ((header[5] & 0x7F) << 24) | ((header[6] & 0xFF) << 16) | ((header[7] & 0xFF) << 8) | (header[8] & 0xFF);
        byte[] payload = in.readNBytes(length);
        if (payload.length < length) {
            throw new EOFException("Unexpected EOF while reading HTTP/2 frame payload");
        }
        return new Http2Frame(length, type, flags, streamId, payload);
    }

    static synchronized void write(OutputStream out, int type, int flags, int streamId, byte[] payload) throws IOException {
        byte[] body = payload == null ? new byte[0] : payload;
        int len = body.length;
        byte[] header = new byte[9];
        header[0] = (byte) ((len >>> 16) & 0xFF);
        header[1] = (byte) ((len >>> 8) & 0xFF);
        header[2] = (byte) (len & 0xFF);
        header[3] = (byte) type;
        header[4] = (byte) flags;
        header[5] = (byte) ((streamId >>> 24) & 0x7F);
        header[6] = (byte) ((streamId >>> 16) & 0xFF);
        header[7] = (byte) ((streamId >>> 8) & 0xFF);
        header[8] = (byte) (streamId & 0xFF);
        out.write(header);
        if (len > 0) {
            out.write(body);
        }
        out.flush();
    }

    static void writeSettings(OutputStream out, int[][] settings) throws IOException {
        byte[] payload = new byte[settings.length * 6];
        int pos = 0;
        for (int[] setting : settings) {
            int id = setting[0];
            int value = setting[1];
            payload[pos++] = (byte) ((id >>> 8) & 0xFF);
            payload[pos++] = (byte) (id & 0xFF);
            payload[pos++] = (byte) ((value >>> 24) & 0xFF);
            payload[pos++] = (byte) ((value >>> 16) & 0xFF);
            payload[pos++] = (byte) ((value >>> 8) & 0xFF);
            payload[pos++] = (byte) (value & 0xFF);
        }
        write(out, TYPE_SETTINGS, 0, 0, payload);
    }

    static void writeSettingsAck(OutputStream out) throws IOException {
        write(out, TYPE_SETTINGS, FLAG_ACK, 0, new byte[0]);
    }

    static void writePingAck(OutputStream out, byte[] opaque) throws IOException {
        write(out, TYPE_PING, FLAG_ACK, 0, opaque);
    }

    static void writeWindowUpdate(OutputStream out, int streamId, int increment) throws IOException {
        byte[] payload = new byte[4];
        payload[0] = (byte) ((increment >>> 24) & 0x7F);
        payload[1] = (byte) ((increment >>> 16) & 0xFF);
        payload[2] = (byte) ((increment >>> 8) & 0xFF);
        payload[3] = (byte) (increment & 0xFF);
        write(out, TYPE_WINDOW_UPDATE, 0, streamId, payload);
    }

    static void writeRstStream(OutputStream out, int streamId, int errorCode) throws IOException {
        byte[] payload = int32(errorCode);
        write(out, TYPE_RST_STREAM, 0, streamId, payload);
    }

    static void writePushPromise(OutputStream out, int streamId, int promisedStreamId, byte[] headerBlock) throws IOException {
        byte[] hb = headerBlock == null ? new byte[0] : headerBlock;
        byte[] payload = new byte[4 + hb.length];
        payload[0] = (byte) ((promisedStreamId >>> 24) & 0x7F);
        payload[1] = (byte) ((promisedStreamId >>> 16) & 0xFF);
        payload[2] = (byte) ((promisedStreamId >>> 8) & 0xFF);
        payload[3] = (byte) (promisedStreamId & 0xFF);
        if (hb.length > 0) {
            System.arraycopy(hb, 0, payload, 4, hb.length);
        }
        write(out, TYPE_PUSH_PROMISE, FLAG_END_HEADERS, streamId, payload);
    }

    static void writeGoAway(OutputStream out, int lastStreamId, int errorCode, byte[] debugData) throws IOException {
        byte[] debug = debugData == null ? new byte[0] : debugData;
        byte[] payload = Arrays.copyOf(int32(lastStreamId & 0x7FFFFFFF), 8 + debug.length);
        byte[] error = int32(errorCode);
        System.arraycopy(error, 0, payload, 4, 4);
        if (debug.length > 0) {
            System.arraycopy(debug, 0, payload, 8, debug.length);
        }
        write(out, TYPE_GOAWAY, 0, 0, payload);
    }

    static byte[] int32(int value) {
        return new byte[] {
                (byte) ((value >>> 24) & 0xFF),
                (byte) ((value >>> 16) & 0xFF),
                (byte) ((value >>> 8) & 0xFF),
                (byte) (value & 0xFF)
        };
    }
}

