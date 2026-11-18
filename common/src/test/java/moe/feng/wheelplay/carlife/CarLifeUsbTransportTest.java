package moe.feng.wheelplay.carlife;

import org.junit.Test;
import static org.junit.Assert.*;
import java.io.*;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.*;

public class CarLifeUsbTransportTest {
    private static final class Link implements CarLifeUsbTransport.Link {
        final PipedInputStream input = new PipedInputStream(65536);
        final PipedOutputStream source;
        final List<byte[]> writes = Collections.synchronizedList(new ArrayList<>());
        final CountDownLatch connecting = new CountDownLatch(1), release = new CountDownLatch(1);
        boolean waitForConnect;
        int failTransfer, transfers;
        volatile boolean closed;
        Link() throws IOException { source = new PipedOutputStream(input); }
        public void connect() throws Exception { connecting.countDown(); if (waitForConnect) release.await(); if (closed) throw new IOException("closed"); }
        public InputStream input() { return input; }
        public OutputStream output() { return new OutputStream() {
            public void write(int b) { throw new AssertionError(); }
            public void write(byte[] data, int offset, int length) throws IOException {
                if (++transfers == failTransfer) throw new IOException("injected USB write failure");
                writes.add(Arrays.copyOfRange(data,offset,offset+length)); Thread.yield();
            }
        }; }
        public void close() throws IOException { closed = true; release.countDown(); source.close(); input.close(); }
        void frame(int channel, byte[] inner) throws IOException {
            DataOutputStream out = new DataOutputStream(source); out.writeInt(channel); out.writeInt(inner.length); out.write(inner); out.flush();
        }
    }
    @Test public void multiplexesFragmentedVideoAndCommandAndClosesBlockedChannels() throws Exception {
        Link link = new Link(); CarLifeUsbTransport transport = new CarLifeUsbTransport(link);
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            transport.connect();
            byte[] video = ByteBuffer.allocate(16).putInt(4).putInt(9).putInt(0x20001).putInt(0x01020304).array();
            link.frame(2,video); link.frame(1,Wire.command(0x10002,Wire.number(1,1)));
            assertEquals(0x10002,Wire.read(transport.input(7240),true,65535).type);
            Wire.Packet packet = Wire.read(transport.input(8240),false,65535);
            assertEquals(9,packet.sequence); assertArrayEquals(new byte[]{1,2,3,4},packet.body);
            Future<Integer> blocked = worker.submit(() -> transport.input(9241).read());
            transport.close();
            assertTrue(assertThrows(ExecutionException.class,() -> blocked.get(2,TimeUnit.SECONDS)).getCause() instanceof IOException);
        } finally { transport.close(); worker.shutdownNow(); }
    }
    @Test public void rejectsUnknownChannelsOversizeAndMismatchedInnerLengths() throws Exception {
        for (byte[] invalid : new byte[][] {
            ByteBuffer.allocate(8).putInt(8).putInt(8).array(),
            ByteBuffer.allocate(8).putInt(2).putInt(-1).array(),
            ByteBuffer.allocate(16).putInt(1).putInt(8).putShort((short)4).putShort((short)0).putInt(0x10002).array()
        }) {
            Link link = new Link(); CarLifeUsbTransport transport = new CarLifeUsbTransport(link);
            ExecutorService worker = Executors.newSingleThreadExecutor();
            try {
                transport.connect(); link.source.write(invalid); link.source.flush();
                Future<Integer> read = worker.submit(() -> transport.input(7240).read());
                assertTrue(assertThrows(ExecutionException.class,() -> read.get(2,TimeUnit.SECONDS)).getCause() instanceof IOException);
            } finally { transport.close(); worker.shutdownNow(); }
        }
    }
    @Test public void concurrentCommandAndMicrophoneWritesKeepHeaderAndBodyTogether() throws Exception {
        Link link = new Link(); CarLifeUsbTransport transport = new CarLifeUsbTransport(link);
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            transport.connect();
            byte[] command = Wire.command(0x18001,Wire.version());
            byte[] voice = ByteBuffer.allocate(652).putInt(640).putInt(0).putInt(0x58001).array();
            Future<?> a = workers.submit(() -> { for (int i=0;i<100;i++) try { transport.write(7240,command); } catch(IOException e) { throw new UncheckedIOException(e); } });
            Future<?> b = workers.submit(() -> { for (int i=0;i<100;i++) try { transport.write(9242,voice); } catch(IOException e) { throw new UncheckedIOException(e); } });
            a.get(3,TimeUnit.SECONDS); b.get(3,TimeUnit.SECONDS);
            assertEquals(400,link.writes.size());
            for (int i=0;i<link.writes.size();i+=2) {
                assertEquals(8,link.writes.get(i).length);
                ByteBuffer head=ByteBuffer.wrap(link.writes.get(i)); int channel=head.getInt(); int size=head.getInt();
                byte[] body=link.writes.get(i+1); assertEquals(size,body.length);
                assertArrayEquals(channel==1?command:voice,body); assertTrue(channel==1 || channel==5);
            }
        } finally { transport.close(); workers.shutdownNow(); }
    }
    @Test public void closeDuringConnectDoesNotStartAReader() throws Exception {
        Link link=new Link(); link.waitForConnect=true; CarLifeUsbTransport transport=new CarLifeUsbTransport(link);
        ExecutorService worker=Executors.newSingleThreadExecutor();
        try {
            Future<?> connecting=worker.submit(() -> { try { transport.connect(); } catch(Exception e) { throw new RuntimeException(e); } });
            assertTrue(link.connecting.await(2,TimeUnit.SECONDS)); transport.close();
            assertThrows(ExecutionException.class,() -> connecting.get(2,TimeUnit.SECONDS));
            assertTrue(link.closed);
        } finally { transport.close(); worker.shutdownNow(); }
    }
    @Test public void slowConsumerFailsBoundedlyInsteadOfBlockingOtherChannelsForever() throws Exception {
        Link link=new Link(); CarLifeUsbTransport transport=new CarLifeUsbTransport(link);
        ExecutorService worker=Executors.newSingleThreadExecutor();
        try {
            transport.connect();
            for(int i=0;i<257;i++) link.frame(3,ByteBuffer.allocate(12).putInt(0).putInt(0).putInt(0x30006).array());
            Future<Integer> read=worker.submit(() -> transport.input(7240).read());
            Throwable error=assertThrows(ExecutionException.class,() -> read.get(2,TimeUnit.SECONDS)).getCause();
            assertTrue(error.getMessage().contains("queue overflow"));
        } finally { transport.close(); worker.shutdownNow(); }
    }
    @Test public void finalCommandSurvivesEofEvenWhenAnotherChannelObservesItFirst() throws Exception {
        Link link = new Link(); CarLifeUsbTransport transport = new CarLifeUsbTransport(link);
        try {
            transport.connect();
            byte[] command = Wire.command(0x10002, Wire.number(1,0));
            link.frame(1,command); link.source.close();
            // A quiet stream observes the shared termination before the command consumer runs.
            assertThrows(CarLifeUsbTransport.ReceiveEndedException.class, () -> transport.input(8240).read());
            byte[] received = new byte[command.length]; new DataInputStream(transport.input(7240)).readFully(received);
            assertArrayEquals(command,received);
            assertThrows(CarLifeUsbTransport.ReceiveEndedException.class, () -> transport.input(7240).read());
            assertThrows(CarLifeUsbTransport.ReceiveEndedException.class, () -> transport.write(7240,command));
            assertTrue(link.writes.isEmpty());
        } finally { transport.close(); }
    }
    @Test public void eofRetainsRemainderOfPartiallyConsumedFrame() throws Exception {
        Link link = new Link(); CarLifeUsbTransport transport = new CarLifeUsbTransport(link);
        try {
            transport.connect();
            byte[] command = Wire.command(0x10002, Wire.number(1,0));
            link.frame(1,command);
            byte[] received = new byte[command.length]; DataInputStream in = new DataInputStream(transport.input(7240));
            in.readFully(received,0,3); link.source.close();
            assertThrows(CarLifeUsbTransport.ReceiveEndedException.class, () -> transport.input(8240).read());
            in.readFully(received,3,received.length-3); assertArrayEquals(command,received);
            assertThrows(CarLifeUsbTransport.ReceiveEndedException.class,in::read);
        } finally { transport.close(); }
    }
    @Test public void explicitCloseDiscardsQueuedFramesImmediately() throws Exception {
        Link link = new Link(); CarLifeUsbTransport transport = new CarLifeUsbTransport(link);
        try {
            transport.connect(); link.frame(1,Wire.command(0x10002,Wire.number(1,0))); link.source.close();
            assertThrows(CarLifeUsbTransport.ReceiveEndedException.class, () -> transport.input(8240).read());
            transport.close();
            IOException error = assertThrows(IOException.class, () -> transport.input(7240).read());
            assertEquals("USB transport closed",error.getMessage());
        } finally { transport.close(); }
    }
    @Test public void headerOrBodyWriteFailurePoisonsAllStreamsAndRejectsQueuedWriters() throws Exception {
        for (int failingTransfer : new int[]{1,2}) {
            Link link = new Link(); link.failTransfer = failingTransfer;
            CarLifeUsbTransport transport = new CarLifeUsbTransport(link);
            ExecutorService worker = Executors.newSingleThreadExecutor();
            try {
                transport.connect();
                Future<Integer> blocked = worker.submit(() -> transport.input(9241).read());
                byte[] command = Wire.command(0x18001,Wire.version());
                IOException error = assertThrows(IOException.class, () -> transport.write(7240,command));
                assertEquals("injected USB write failure",error.getMessage());
                assertTrue(link.closed);
                assertSame(error,assertThrows(IOException.class, () -> transport.write(9340,command)));
                assertEquals(failingTransfer,link.transfers);
                assertSame(error,assertThrows(ExecutionException.class, () -> blocked.get(2,TimeUnit.SECONDS)).getCause());
            } finally { transport.close(); worker.shutdownNow(); }
        }
    }

}
