package org.broeuschmeul.android.gps.usb.provider.ublox;
import org.junit.Test;
import java.io.*;
import static org.junit.Assert.*;

public class EphemerisCacheTest {
    private static final long NOW=1791849600000L;
    @Test public void roundTripAndDefensiveCopies() throws Exception {
        EphemerisCache c=new EphemerisCache(); byte[] b=UbxProtocolTest.eph(NOW,1);
        assertTrue(c.put(b,NOW)); b[20]++;
        ByteArrayOutputStream out=new ByteArrayOutputStream(); c.write(out);
        EphemerisCache restored=new EphemerisCache(); restored.read(new ByteArrayInputStream(out.toByteArray()));
        assertEquals(1,restored.usable(NOW+60000).size());
        restored.usable(NOW).get(0)[20]++;
        assertTrue(UbxProtocol.valid(restored.usable(NOW).get(0)));
    }
    @Test public void rejectsOvernightAndClockRollback() {
        EphemerisCache c=new EphemerisCache(); c.put(UbxProtocolTest.eph(NOW,1),NOW);
        assertTrue(c.usable(NOW+86400000).isEmpty()); assertTrue(c.usable(NOW-1).isEmpty());
    }
    @Test public void repeatPollDoesNotRefreshAge() {
        EphemerisCache c=new EphemerisCache(); byte[] b=UbxProtocolTest.eph(NOW+3600000,1);
        assertTrue(c.put(b,NOW)); assertFalse(c.put(b,NOW+3600000));
        assertTrue(c.usable(NOW+91*60000).isEmpty());
    }
    @Test public void staleOrbitIsNotAcceptedEvenIfJustReceived() {
        EphemerisCache c=new EphemerisCache(); assertFalse(c.put(UbxProtocolTest.eph(NOW-86400000,1),NOW));
    }
    @Test public void corruptFileDoesNotEraseExistingEntries() throws Exception {
        EphemerisCache c=new EphemerisCache(); c.put(UbxProtocolTest.eph(NOW,1),NOW);
        try { c.read(new ByteArrayInputStream(new byte[12])); fail(); } catch(IOException expected) { }
        assertEquals(1,c.usable(NOW).size());
    }
}
