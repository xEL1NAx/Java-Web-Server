package server;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;

public final class CidrMatcher {
    private final List<Entry> entries = new ArrayList<>();

    public CidrMatcher(List<String> cidrs) {
        if (cidrs != null) {
            for (String cidr : cidrs) {
                if (cidr != null && !cidr.isBlank()) {
                    entries.add(parse(cidr.trim()));
                }
            }
        }
    }


    public boolean isEmpty() {
        return entries.isEmpty();
    }

    public boolean matches(String ip) {
        if (entries.isEmpty()) return false;
        try {
            byte[] address = InetAddress.getByName(ip).getAddress();
            for (Entry e : entries) {
                if (e.matches(address)) return true;
            }
            return false;
        } catch (UnknownHostException e) {
            return false;
        }
    }

    private Entry parse(String cidr) {
        try {
            String[] parts = cidr.split("/", 2);
            byte[] base = InetAddress.getByName(parts[0]).getAddress();
            int prefix = parts.length == 2 ? Integer.parseInt(parts[1]) : base.length * 8;
            return new Entry(base, prefix);
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid CIDR: " + cidr, e);
        }
    }

    private static final class Entry {
        private final byte[] base;
        private final int prefix;

        private Entry(byte[] base, int prefix) {
            this.base = base;
            this.prefix = prefix;
        }

        boolean matches(byte[] address) {
            if (address.length != base.length) return false;
            int bits = prefix;
            for (int i = 0; i < address.length; i++) {
                int maskBits = Math.min(8, Math.max(bits, 0));
                if (maskBits == 0) return true;
                int mask = 0xFF << (8 - maskBits);
                if ((address[i] & mask) != (base[i] & mask)) return false;
                bits -= maskBits;
            }
            return true;
        }
    }
}
