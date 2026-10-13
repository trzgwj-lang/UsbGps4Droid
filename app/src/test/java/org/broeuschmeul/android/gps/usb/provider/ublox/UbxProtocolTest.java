package org.broeuschmeul.android.gps.usb.provider.ublox;

import org.junit.Test;
import java.io.*;
import java.util.*;
import static org.junit.Assert.*;

public class UbxProtocolTest {
    private static final long NOW = 1791849600000L; // 2026-10-13 00:00:00 UTC

    static byte[] eph(long time, int sv) {
        long gps = time / 1000 - 315964800L + 18;
        byte[] payload = new byte[104];
        UbxProtocol.put32(payload, 0, sv);
        UbxProtocol.put32(payload, 4, 1); // non-empty HOW
        UbxProtocol.put32(payload, 8, ((gps / 604800) % 1024) << 14); // SF1 W3: week, healthy
        UbxProtocol.put32(payload, 28, 7 << 16); // SF1 W8: IODC low byte
        UbxProtocol.put32(payload, 40, 7 << 16); // SF2 W3: IODE
        UbxProtocol.put32(payload, 68, ((gps % 604800) / 16) << 8); // SF2 W10: TOE
        UbxProtocol.put32(payload, 100, 7 << 16); // SF3 W10: IODE
        return UbxProtocol.frame(11, 49, payload);
    }
    private static byte[] concat(byte[]... arrays) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] a : arrays) out.write(a);
        return out.toByteArray();
    }
    private static class Capture implements UbxProtocol.Listener {
        List<String> nmea = new ArrayList<>();
        List<byte[]> frames = new ArrayList<>();
        public void onNmea(String s) { nmea.add(s); }
        public void onUbx(byte[] f) { frames.add(f); }
    }

    @Test public void knownVersionPollChecksum() {
        assertArrayEquals(new byte[]{(byte)181,98,10,4,0,0,14,52}, UbxProtocol.poll(10,4));
    }
    @Test public void streamHandlesEveryChunkBoundaryAndBinaryNewlines() throws Exception {
        byte[] payload = new byte[]{0,10,13,36,33,(byte)181,98,0};
        byte[] binary = UbxProtocol.frame(10,4,payload);
        byte[] stream = concat("junk$GPRMC,one*00\r\n".getBytes("US-ASCII"), binary,
                "$GPGGA,two*00\r\n".getBytes("US-ASCII"));
        for (int split=0; split<=stream.length; split++) {
            Capture c = new Capture(); UbxProtocol.Decoder d = new UbxProtocol.Decoder(c);
            byte[] first=Arrays.copyOfRange(stream,0,split), second=Arrays.copyOfRange(stream,split,stream.length);
            d.accept(first,first.length); d.accept(second,second.length);
            assertEquals(2,c.nmea.size()); assertEquals(1,c.frames.size()); assertArrayEquals(binary,c.frames.get(0));
        }
    }
    @Test public void corruptChecksumAndOversizeLengthRecover() throws Exception {
        byte[] broken = UbxProtocol.poll(10,4); broken[7]++;
        byte[] stream=concat(broken,new byte[]{(byte)181,98,1,2,(byte)255,(byte)255},UbxProtocol.poll(10,4));
        Capture c=new Capture(); new UbxProtocol.Decoder(c).accept(stream,stream.length);
        assertEquals(1,c.frames.size());
    }
    @Test public void idleResetDropsTruncatedFrame() throws Exception {
        Capture c=new Capture(); UbxProtocol.Decoder d=new UbxProtocol.Decoder(c);
        d.accept(new byte[]{(byte)181,98,1,2,100,0},6); d.reset();
        byte[] poll=UbxProtocol.poll(10,4); d.accept(poll,poll.length); assertEquals(1,c.frames.size());
    }
    @Test public void overlongNmeaResynchronizes() throws Exception {
        byte[] longLine=new byte[3000]; Arrays.fill(longLine,(byte)'x'); longLine[0]='$';
        Capture c=new Capture(); UbxProtocol.Decoder d=new UbxProtocol.Decoder(c);
        d.accept(longLine,longLine.length); byte[] good="$GPRMC,ok*00\n".getBytes("US-ASCII");
        d.accept(good,good.length); assertEquals(1,c.nmea.size());
    }
    @Test public void acceptsLegacyAidBatch() throws Exception {
        List<byte[]> frames=UbxProtocol.assistance(concat(UbxProtocol.timeAid(NOW),eph(NOW,1)));
        assertEquals(2,frames.size());
    }
    @Test public void rejectsConfigPacketsEvenWithValidChecksum() throws Exception {
        assertRejected(concat(eph(NOW,1),UbxProtocol.frame(6,4,new byte[4])));
    }
    @Test public void rejectsMgaInsteadOfAid() throws Exception {
        assertRejected(UbxProtocol.frame(0x13,0,new byte[68]));
    }
    @Test public void rejectsTextTruncationAndBadChecksums() throws Exception {
        assertRejected("Your token has expired".getBytes("US-ASCII"));
        byte[] b=eph(NOW,1); assertRejected(Arrays.copyOf(b,b.length-1)); b[20]++; assertRejected(b);
        assertRejected(new byte[65537]);
    }
    @Test public void rejectsNoEphemerisAndInvalidSatellite() throws Exception {
        assertRejected(UbxProtocol.timeAid(NOW)); assertRejected(eph(NOW,33));
    }
    private void assertRejected(byte[] b) throws Exception {
        try { UbxProtocol.assistance(b); fail("Should reject invalid/unsafe data"); }
        catch (IOException expected) { }
    }
    @Test public void utcInitializationUsesUtcRegardlessOfLocalTimezone() {
        TimeZone old=TimeZone.getDefault();
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"));
            byte[] b=UbxProtocol.timeAid(NOW+123);
            assertTrue(UbxProtocol.valid(b)); assertEquals(48,UbxProtocol.length(b));
            assertEquals(10,b[24]); assertEquals(26,b[25]); // month / years since 2000
            assertEquals(0,b[26]); assertEquals(0,b[27]); assertEquals(0,b[28]); assertEquals(13,b[29]);
            assertEquals(123000000,UbxProtocol.u32(b,30));
            assertEquals(10000,UbxProtocol.u32(b,34)); assertEquals(0x402,UbxProtocol.u32(b,50));
        } finally { TimeZone.setDefault(old); }
    }
    @Test(expected=IllegalArgumentException.class) public void rejectsUnsetAndroidClock() { UbxProtocol.timeAid(0); }
    @Test public void slowNetworkWidensTimeUncertaintyWithoutChangingEpoch() {
        byte[] original=UbxProtocol.timeAid(NOW);
        byte[] delayed=UbxProtocol.addTimeUncertainty(original,15000);
        assertTrue(UbxProtocol.valid(delayed));
        assertEquals(26000,UbxProtocol.u32(delayed,34));
        assertArrayEquals(Arrays.copyOfRange(original,6,34),Arrays.copyOfRange(delayed,6,34));
        assertEquals(10000,UbxProtocol.u32(original,34));
    }
    @Test public void ephemerisValidityUsesOrbitEpochNotDownloadTime() {
        assertTrue(UbxProtocol.usableEphemeris(eph(NOW,1),NOW));
        assertFalse(UbxProtocol.usableEphemeris(eph(NOW-4*3600000L,1),NOW));
        assertFalse(UbxProtocol.usableEphemeris(eph(NOW+4*3600000L,1),NOW));
    }
    @Test public void rejectsUnhealthyOrInconsistentOrbit() {
        byte[] p=Arrays.copyOfRange(eph(NOW,1),6,110);
        p[8]|=4; assertFalse(UbxProtocol.usableEphemeris(UbxProtocol.frame(11,49,p),NOW));
        p=Arrays.copyOfRange(eph(NOW,1),6,110); p[42]=8;
        assertFalse(UbxProtocol.usableEphemeris(UbxProtocol.frame(11,49,p),NOW));
    }
    @Test public void orbitWeekRolloverResolvesAgainstCurrentWeek() {
        long rollover=315964800000L+2048L*604800000-18000;
        byte[] b=eph(rollover-16000,1);
        assertTrue(UbxProtocol.usableEphemeris(b,rollover+16000));
        assertFalse(UbxProtocol.usableEphemeris(b,rollover+1024L*604800000-36000000));
    }
    @Test public void requestIsHttpsAndEncodesCredential() throws Exception {
        String url=AssistNowClient.requestUrl("abc;&token=bad").toString();
        assertTrue(url.startsWith("https://online-live1.services.u-blox.com/"));
        assertTrue(url.contains("format=aid")); assertTrue(url.contains("gnss=gps"));
        assertFalse(url.contains("&token=bad"));
    }
    @Test(expected=IOException.class) public void missingTokenDoesNotMakeRequest() throws Exception { AssistNowClient.requestUrl(" "); }
}
