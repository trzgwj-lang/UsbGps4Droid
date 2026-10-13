package org.broeuschmeul.android.gps.usb.provider.ublox;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.List;
import java.util.TimeZone;

/** u-blox 7 protocol v14. All binary data stays separate from the NMEA text parser. */
public final class UbxProtocol {
    private UbxProtocol() { }
    public static final int MAX_PAYLOAD = 4096;

    public interface Listener {
        void onNmea(String sentence);
        void onUbx(byte[] frame);
    }

    public static byte[] frame(int cls, int id, byte[] payload) {
        byte[] b = new byte[payload.length + 8];
        b[0] = (byte) 0xb5; b[1] = 0x62; b[2] = (byte) cls; b[3] = (byte) id;
        b[4] = (byte) payload.length; b[5] = (byte) (payload.length >> 8);
        System.arraycopy(payload, 0, b, 6, payload.length);
        int a = 0, c = 0;
        for (int i = 2; i < b.length - 2; i++) { a = (a + (b[i] & 255)) & 255; c = (c + a) & 255; }
        b[b.length - 2] = (byte) a; b[b.length - 1] = (byte) c;
        return b;
    }

    public static byte[] poll(int cls, int id) { return frame(cls, id, new byte[0]); }
    public static int length(byte[] b) { return (b[4] & 255) | ((b[5] & 255) << 8); }
    public static long u32(byte[] b, int off) {
        return ((long) b[off] & 255) | (((long) b[off + 1] & 255) << 8)
                | (((long) b[off + 2] & 255) << 16) | (((long) b[off + 3] & 255) << 24);
    }
    static void put32(byte[] b, int off, long value) {
        for (int i = 0; i < 4; i++) b[off + i] = (byte) (value >> (8 * i));
    }
    public static boolean valid(byte[] b) {
        if (b.length < 8 || (b[0] & 255) != 181 || b[1] != 98 || length(b) + 8 != b.length) return false;
        int a = 0, c = 0;
        for (int i = 2; i < b.length - 2; i++) { a = (a + (b[i] & 255)) & 255; c = (c + a) & 255; }
        return a == (b[b.length - 2] & 255) && c == (b[b.length - 1] & 255);
    }

    /** Opt-in host UTC time only; never replay a saved AID-INI message. */
    public static byte[] timeAid(long utcMillis) {
        Calendar c = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        c.setTimeInMillis(utcMillis);
        int year = c.get(Calendar.YEAR);
        if (year < 2020 || year > 2099) throw new IllegalArgumentException("Check Android date/time");
        byte[] p = new byte[48];
        p[18] = (byte) (c.get(Calendar.MONTH) + 1);
        p[19] = (byte) (year - 2000);
        p[20] = (byte) c.get(Calendar.SECOND); p[21] = (byte) c.get(Calendar.MINUTE);
        p[22] = (byte) c.get(Calendar.HOUR_OF_DAY); p[23] = (byte) c.get(Calendar.DAY_OF_MONTH);
        put32(p, 24, c.get(Calendar.MILLISECOND) * 1000000L);
        put32(p, 28, 10000); // Conservative 10-second uncertainty. User must enable automatic system time.
        put32(p, 44, 0x402); // time valid + UTC; no position or clock-drift assertion
        return frame(0x0b, 0x01, p);
    }

    /** Server time refers to response creation, not the moment USB transmission starts. */
    public static byte[] addTimeUncertainty(byte[] frame, long elapsedMillis) {
        if (!valid(frame) || frame[2] != 11 || frame[3] != 1 || length(frame) != 48
                || elapsedMillis < 0) throw new IllegalArgumentException("Invalid time assistance");
        byte[] p = Arrays.copyOfRange(frame, 6, frame.length - 2);
        long uncertainty = Math.min(0xffffffffL, u32(p, 28) + elapsedMillis + 1000);
        put32(p, 28, uncertainty);
        return frame(11, 1, p);
    }

    /** Strict allowlist: a downloaded file must never reconfigure or reset the receiver. */
    public static List<byte[]> assistance(byte[] data) throws IOException {
        if (data.length == 0 || data.length > 65536) throw new IOException("Invalid assistance size");
        List<byte[]> result = new ArrayList<>();
        boolean eph = false;
        for (int off = 0; off < data.length;) {
            if (data.length - off < 8) throw new IOException("Truncated assistance");
            int size = (data[off + 4] & 255) | ((data[off + 5] & 255) << 8);
            if (size > MAX_PAYLOAD || size + 8 > data.length - off) throw new IOException("Invalid UBX length");
            byte[] b = Arrays.copyOfRange(data, off, off + size + 8);
            if (!valid(b) || b[2] != 0x0b) throw new IOException("Expected legacy UBX-AID data");
            int id = b[3] & 255;
            boolean allowed = (id == 1 && size == 48) || (id == 2 && size == 72)
                    || (id == 0x30 && size == 40) || (id == 0x31 && size == 104);
            if (!allowed) throw new IOException("Unsupported assistance message");
            if ((id == 0x30 || id == 0x31) && (u32(b, 6) < 1 || u32(b, 6) > 32))
                throw new IOException("Invalid satellite ID");
            if (id == 0x31) {
                if (u32(b, 10) == 0) throw new IOException("Empty ephemeris");
                eph = true;
            }
            result.add(b); off += size + 8;
        }
        if (!eph) throw new IOException("No GPS ephemeris in response");
        return result;
    }

    /** Conservative orbit-age check, independent of when a frame was downloaded or polled. */
    public static boolean usableEphemeris(byte[] b, long utcMillis) {
        if (!valid(b) || b[2] != 11 || b[3] != 49 || length(b) != 104
                || u32(b, 6) < 1 || u32(b, 6) > 32 || u32(b, 10) == 0) return false;
        long word3 = u32(b, 14);
        if (((word3 >> 2) & 63) != 0) return false; // unhealthy SV
        long iode = (u32(b, 46) >> 16) & 255;
        if (((u32(b, 34) >> 16) & 255) != iode || ((u32(b, 106) >> 16) & 255) != iode) return false;
        long gpsSeconds = utcMillis / 1000 - 315964800L + 18;
        long currentWeek = gpsSeconds / 604800;
        long week10 = (word3 >> 14) & 1023;
        long week = week10 + Math.round((currentWeek - week10) / 1024.0) * 1024L;
        long toe = ((u32(b, 74) >> 8) & 65535) * 16;
        // GPS-UTC is 18s for current receivers; a conservative 90-minute window leaves ample margin.
        return toe < 604800 && Math.abs(gpsSeconds - (week * 604800 + toe)) <= 5400;
    }

    public static final class Decoder {
        private final Listener listener;
        private final ByteArrayOutputStream pending = new ByteArrayOutputStream();
        public Decoder(Listener listener) { this.listener = listener; }
        public void accept(byte[] bytes, int count) {
            for (int i = 0; i < count; i++) accept(bytes[i]);
        }
        private void accept(byte value) {
            pending.write(value);
            drain();
        }
        private void drain() {
            byte[] b = pending.toByteArray();
            int off = 0;
            while (off < b.length) {
                int remaining = b.length - off;
                if ((b[off] & 255) == 181) {
                    if (remaining < 2) break;
                    if (b[off + 1] != 98) { off++; continue; }
                    if (remaining < 6) break;
                    int n = (b[off + 4] & 255) | ((b[off + 5] & 255) << 8);
                    if (n > MAX_PAYLOAD) { off++; continue; }
                    if (remaining < n + 8) break;
                    byte[] f = Arrays.copyOfRange(b, off, off + n + 8);
                    if (valid(f)) { listener.onUbx(f); off += n + 8; } else off++;
                } else if (b[off] == '$' || b[off] == '!') {
                    int end = off + 1;
                    while (end < b.length && b[end] != '\n' && b[end] != '$' && (b[end] & 255) != 181) end++;
                    if (end == b.length) { if (remaining > 1024) off++; else break; }
                    else if (b[end] == '\n') {
                        try { listener.onNmea(new String(b, off, end - off + 1, "US-ASCII").trim()); }
                        catch (java.io.UnsupportedEncodingException impossible) { throw new AssertionError(impossible); }
                        off = end + 1;
                    } else off = end;
                } else off++;
            }
            if (off > 0) { pending.reset(); pending.write(b, off, b.length - off); }
        }
        public void reset() { pending.reset(); }
    }
}
