package moe.feng.wheelplay.carlife;

import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class CarLifeMicrophoneTest {
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
