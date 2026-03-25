package server;

import jdk.internal.net.http.hpack.Decoder;
import jdk.internal.net.http.hpack.DecodingCallback;
import jdk.internal.net.http.hpack.Encoder;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

final class Hpack {
    private Hpack() {
    }

    public static Map<String, List<String>> decodeHeaderBlock(byte[] headerBlock, int maxHeaderTableSize) throws IOException {
        Decoder decoder = new Decoder(maxHeaderTableSize);
        Map<String, List<String>> headers = new LinkedHashMap<>();
        decoder.decode(ByteBuffer.wrap(headerBlock), true, new DecodingCallback() {
            @Override
            public void onDecoded(CharSequence name, CharSequence value) {
                String key = name.toString().toLowerCase(Locale.ROOT);
                headers.computeIfAbsent(key, k -> new ArrayList<>()).add(value.toString());
            }
        });
        return headers;
    }

    public static byte[] encodeHeaderList(List<Map.Entry<String, String>> headers, int maxHeaderTableSize) throws IOException {
        Encoder encoder = new Encoder(maxHeaderTableSize);
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        ByteBuffer buffer = ByteBuffer.allocate(4096);

        for (Map.Entry<String, String> header : headers) {
            String name = header.getKey().toLowerCase(Locale.ROOT);
            String value = header.getValue();
            if (value == null) {
                continue;
            }
            encoder.header(name, value);
            while (true) {
                boolean done = encoder.encode(buffer);
                if (done) {
                    break;
                }
                if (!buffer.hasRemaining()) {
                    flush(buffer, bos);
                }
            }
            flush(buffer, bos);
        }
        return bos.toByteArray();
    }

    public static byte[] encodeResponseHeaders(int status, Map<String, String> headers, int maxHeaderTableSize) throws IOException {
        List<Map.Entry<String, String>> headerList = new ArrayList<>();
        headerList.add(new AbstractMap.SimpleImmutableEntry<>(":status", String.valueOf(status)));
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            headerList.add(new AbstractMap.SimpleImmutableEntry<>(entry.getKey(), entry.getValue()));
        }
        return encodeHeaderList(headerList, maxHeaderTableSize);
    }

    private static void flush(ByteBuffer buffer, ByteArrayOutputStream bos) {
        buffer.flip();
        if (buffer.hasRemaining()) {
            byte[] arr = new byte[buffer.remaining()];
            buffer.get(arr);
            bos.write(arr, 0, arr.length);
        }
        buffer.clear();
    }
}
