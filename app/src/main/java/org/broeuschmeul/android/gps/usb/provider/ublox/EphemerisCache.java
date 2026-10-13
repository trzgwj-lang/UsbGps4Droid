package org.broeuschmeul.android.gps.usb.provider.ublox;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Bounded GPS-only cache. A poll of identical data never extends its lifetime. */
public final class EphemerisCache {
    private static final long MAX_AGE_MS = 90 * 60 * 1000L;
    private static final class Entry {
        final long received;
        final byte[] frame;
        Entry(long received, byte[] frame) { this.received = received; this.frame = frame.clone(); }
    }
    private final Map<Integer, Entry> entries = new LinkedHashMap<>();

    public synchronized boolean put(byte[] frame, long now) {
        if (!UbxProtocol.usableEphemeris(frame, now)) return false;
        int sv = (int) UbxProtocol.u32(frame, 6);
        Entry old = entries.get(sv);
        if (old != null && Arrays.equals(old.frame, frame)) return false;
        entries.put(sv, new Entry(now, frame));
        return true;
    }

    public synchronized List<byte[]> usable(long now) {
        List<byte[]> result = new ArrayList<>();
        for (Entry e : entries.values()) {
            long age = now - e.received;
            if (age >= 0 && age <= MAX_AGE_MS && UbxProtocol.usableEphemeris(e.frame, now)) result.add(e.frame.clone());
        }
        return result;
    }

    public synchronized void read(InputStream input) throws IOException {
        DataInputStream in = new DataInputStream(input);
        if (in.readInt() != 0x55425831) throw new IOException("Unknown cache version");
        int count = in.readInt();
        if (count < 0 || count > 32) throw new IOException("Invalid cache size");
        Map<Integer, Entry> loaded = new LinkedHashMap<>();
        for (int i = 0; i < count; i++) {
            long received = in.readLong();
            byte[] frame = new byte[112]; in.readFully(frame);
            if (!UbxProtocol.usableEphemeris(frame, received)) throw new IOException("Invalid cached ephemeris");
            loaded.put((int) UbxProtocol.u32(frame, 6), new Entry(received, frame));
        }
        if (in.read() != -1) throw new IOException("Unexpected cache suffix");
        entries.clear(); entries.putAll(loaded);
    }

    public synchronized void write(OutputStream output) throws IOException {
        DataOutputStream out = new DataOutputStream(output);
        out.writeInt(0x55425831); out.writeInt(entries.size());
        for (Entry e : entries.values()) { out.writeLong(e.received); out.write(e.frame); }
        out.flush();
    }
}
