package moe.feng.wheelplay.carlife;

import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class CarLifeMicrophoneTest {
    @Test public void stopAndCloseAllowInFlightCaptureToExit() throws Exception {
        for (boolean close : new boolean[] { false, true }) {
            CountDownLatch stopping = new CountDownLatch(1);
            BlockingQueue<byte[]> packets = new LinkedBlockingQueue<>();
            Thread[] captureThread = new Thread[1];
            CarLifeMicrophone mic = new CarLifeMicrophone(new CarLifeMicrophone.Listener() {
                public void started(CarLifeMicrophone.Capture capture) {
                    captureThread[0] = new Thread(() -> {
                        try {
                            stopping.await();
                            // Simulate PCM already past the audio source's active check.
                            capture.pcm(new byte[] { 1, 0 });
                        } catch (InterruptedException error) { Thread.currentThread().interrupt(); }
                    });
                    captureThread[0].start();
                }
                public void stopped(CarLifeMicrophone.Capture capture) {
                    stopping.countDown();
                    try { captureThread[0].join(1000); }
                    catch (InterruptedException error) { throw new AssertionError(error); }
                    assertFalse("Capture must exit while stopped() waits", captureThread[0].isAlive());
                }
            }, packets::add, s -> {}, s -> { throw new AssertionError(s); });
            try {
                mic.command(65570, Wire.EMPTY);
                if (close) mic.close(); else mic.command(65571, Wire.EMPTY);
                assertTrue("Revoked capture must not send PCM", packets.isEmpty());
            } finally {
                stopping.countDown(); mic.close(); mic.awaitStopped();
                captureThread[0].join(1000);
            }
        }
    }
    @Test public void duplicateStartAndStopCloseCaptureExactlyOnce() throws Exception {
        List<CarLifeMicrophone.Capture> starts=new ArrayList<>(),stops=new ArrayList<>();
        BlockingQueue<byte[]> packets=new LinkedBlockingQueue<>();
        CarLifeMicrophone mic=new CarLifeMicrophone(new CarLifeMicrophone.Listener() {
            public void started(CarLifeMicrophone.Capture c){starts.add(c);}
            public void stopped(CarLifeMicrophone.Capture c){stops.add(c);}
        },packets::add,s->{},s->{throw new AssertionError(s);});
        try {
            mic.command(65570,Wire.EMPTY);mic.command(65572,Wire.EMPTY);assertEquals(1,starts.size());
            mic.command(65571,Wire.EMPTY);mic.command(65571,Wire.EMPTY);assertEquals(1,stops.size());
            mic.command(65572,Wire.EMPTY);assertEquals(2,starts.size());
            starts.get(0).pcm(new byte[]{1,0});assertNull(packets.poll(100,TimeUnit.MILLISECONDS));
            assertThrows(IllegalArgumentException.class,()->starts.get(1).pcm(new byte[]{1}));
            assertThrows(IOException.class,()->mic.command(65571,new byte[]{1}));
            mic.close();mic.close();assertEquals(2,stops.size());
            mic.command(65572,Wire.EMPTY);starts.get(1).pcm(new byte[]{1,0});
            assertEquals(2,starts.size());assertNull(packets.poll(100,TimeUnit.MILLISECONDS));
        } finally {mic.close();mic.awaitStopped();}
    }
    @Test public void slowTransportHasBoundedBacklogAndCloseInterruptsWriter() throws Exception {
        BlockingQueue<CarLifeMicrophone.Capture> starts=new LinkedBlockingQueue<>();
        CountDownLatch writing=new CountDownLatch(1),failed=new CountDownLatch(1);
        CarLifeMicrophone mic=new CarLifeMicrophone(new CarLifeMicrophone.Listener() {
            public void started(CarLifeMicrophone.Capture c){starts.add(c);}
            public void stopped(CarLifeMicrophone.Capture c){}
        },p->{writing.countDown();try {new CountDownLatch(1).await();}catch(InterruptedException e){throw new IOException(e);}},s->{},s->failed.countDown());
        try {
            mic.command(65572,Wire.EMPTY);CarLifeMicrophone.Capture capture=starts.take();
            capture.pcm(new byte[640]);assertTrue(writing.await(1,TimeUnit.SECONDS));
            for(int i=0;i<9;i++)capture.pcm(new byte[640]);
            assertTrue(failed.await(1,TimeUnit.SECONDS));
        } finally {mic.close();mic.awaitStopped();}
    }
}
