package moe.feng.wheelplay.carlife;

import org.junit.Test;
import static org.junit.Assert.*;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static moe.feng.wheelplay.carlife.XiaomiHandshakeTest.*;

/** Real seven-channel loopback: exercises framing, control owner, media readers and input writer. */
public class CarLifeProtocolIntegrationTest {
    private static final class Peer implements AutoCloseable {
        final Map<Integer,ServerSocket> servers=new LinkedHashMap<>();
        final Map<Integer,Socket> sockets=new LinkedHashMap<>();
        Peer() throws Exception {
            try {for(int port:Wire.PORTS){ServerSocket s=new ServerSocket();s.setReuseAddress(true);s.bind(new InetSocketAddress("127.0.0.1",port));s.setSoTimeout(6000);servers.put(port,s);}}
            catch(Exception e){close();throw e;}
        }
        void accept() throws Exception {for(int p:Wire.PORTS){Socket s=servers.get(p).accept();s.setSoTimeout(6000);sockets.put(p,s);}}
        Wire.Packet command(int expected) throws Exception {
            for(int i=0;i<20;i++) {Wire.Packet p=Wire.read(sockets.get(7240).getInputStream(),true,65535);if(p.type==131074)continue;assertEquals(expected,p.type);return p;}
            throw new AssertionError("heartbeat flood");
        }
        void send(int type,byte[] data) throws Exception {sockets.get(7240).getOutputStream().write(Wire.command(type,data));}
        void media(int port,int type,byte[] data) throws Exception {
            DataOutputStream out=new DataOutputStream(sockets.get(port).getOutputStream());out.writeInt(data.length);out.writeInt(0);out.writeInt(type);out.write(data);out.flush();
        }
        public void close(){for(Socket s:sockets.values())try{s.close();}catch(Exception ignored){}for(ServerSocket s:servers.values())try{s.close();}catch(Exception ignored){}}
    }
    @Test public void xiaomiEncryptedVideoInitAudioAndTouchRoundTrip() throws Exception {roundTrip(true);}
    @Test public void vivoDefaultStillUsesOriginalPlaintextWireAndTouch() throws Exception {roundTrip(false);}
    @Test public void vivoMicrophoneUplinkFollowsSourceStartAndStop() throws Exception { roundTrip(false,true); }
    @Test public void xiaomiMicrophoneUplinkEncryptsPcmAndFollowsStop() throws Exception { roundTrip(true,true); }
    private void roundTrip(boolean xiaomi) throws Exception { roundTrip(xiaomi,false); }
    @Test public void vivoTwoPointerWireAndStopRelease() throws Exception { roundTrip(false,false,true); }
    private void roundTrip(boolean xiaomi, boolean withMicrophone) throws Exception { roundTrip(xiaomi,withMicrophone,false); }
    @Test public void explicitSourceAddressCarriesVideoAudioAndTwoPointers() throws Exception { roundTrip(false,false,true,"localhost"); }
    private void roundTrip(boolean xiaomi, boolean withMicrophone, boolean multi) throws Exception { roundTrip(xiaomi,withMicrophone,multi,null); }
    private void roundTrip(boolean xiaomi, boolean withMicrophone, boolean multi, String host) throws Exception {
        BlockingQueue<CarLifeMicrophone.Capture> captures=new LinkedBlockingQueue<>();
        CountDownLatch microphoneStopped=new CountDownLatch(1);
        CarLifeMicrophone.Listener mic=new CarLifeMicrophone.Listener() {
            public void started(CarLifeMicrophone.Capture capture) {captures.add(capture);}
            public void stopped(CarLifeMicrophone.Capture capture) {microphoneStopped.countDown();}
        };
        CountDownLatch video=new CountDownLatch(1),pcm=new CountDownLatch(2);
        AtomicReference<String> failure=new AtomicReference<>();AtomicReference<byte[]> receivedVideo=new AtomicReference<>();
        Map<Integer,byte[]> receivedAudio=new ConcurrentHashMap<>();AtomicReference<VideoSize> size=new AtomicReference<>();
        CarLifeSession.Listener listener=new CarLifeSession.Listener(){
            public void configured(int w,int h){size.set(new VideoSize(w,h));}
            public void log(String s){}public void failed(String s){failure.compareAndSet(null,s);}
            public void video(byte[] bytes,long t){receivedVideo.set(bytes);video.countDown();}public void ended(){}
        };
        CarLifeSession.AudioListener audio=new CarLifeSession.AudioListener(){
            public void started(int p,int r,int c){assertEquals(48000,r);assertEquals(2,c);}
            public void pcm(int p,byte[] b){receivedAudio.put(p,b);pcm.countDown();}public void stopped(int p){}
        };
        // Use the old constructor for vivo: regressions in its default selection must fail this test.
        CarLifeSession session=host!=null?new CarLifeSession(listener,null,audio,SIZE,CarLifeProtocolMode.LEGACY_3_0,null,host):withMicrophone?new CarLifeSession(listener,null,audio,SIZE,xiaomi?CarLifeProtocolMode.XIAOMI_4_1:CarLifeProtocolMode.LEGACY_3_0,mic):xiaomi?new CarLifeSession(listener,null,audio,SIZE,CarLifeProtocolMode.XIAOMI_4_1):new CarLifeSession(listener,null,audio,SIZE);
        try(Peer peer=new Peer()) {
            session.start();peer.accept();
            assertArrayEquals(xiaomi?XiaomiHandshake.version():Wire.version(),peer.command(98305).body);
            peer.send(65538,Wire.number(1,1));peer.command(98343);
            if(xiaomi && withMicrophone) {
                peer.send(65572,Wire.EMPTY);
                assertNull("capture before authentication",captures.poll(150,TimeUnit.MILLISECONDS));
            }
            peer.send(65540,xiaomi?info():Wire.EMPTY);peer.command(98307);
            if(xiaomi) {
                String challenge=string(peer.command(0x18048).body,1);
                peer.send(0x1004B,Wire.number(1,1));peer.command(0x1804C);
                peer.send(0x10051,Wire.EMPTY);peer.send(0x10049,proof(challenge));peer.command(0x1804A);
                byte[] features=peer.command(0x18052).body;
                assertEquals(withMicrophone?3:2,Wire.integer(features,1,-1));
                Map<String,Long> options=new HashMap<>();
                for(Wire.Field entry:Wire.fields(features)) if(entry.id==2)
                    options.put(string(entry.data,1),Wire.integer(entry.data,2,-1));
                assertEquals(withMicrophone?3:2,options.size());
                if(withMicrophone) assertEquals(Long.valueOf(0),options.get("VOICE_MIC"));
                assertEquals(Long.valueOf(1),options.get("CONTENT_ENCRYPTION"));
                assertEquals(Long.valueOf(1),options.get("DISABLE_RESOLUTION_OPTION"));
                peer.send(0x1006A,Wire.EMPTY);
                peer.send(0x1006C,seed(string(peer.command(0x1806B).body,1),KEY));peer.command(0x1806D);
                peer.send(0x1006E,Wire.EMPTY);
                byte[] request=crypt(peer.command(98311).body,false);
                assertEquals(1280,Wire.integer(request,1,0));peer.send(65544,crypt(request,true));
            } else {
                byte[] features=peer.command(98386).body;
                assertArrayEquals(Wire.features(withMicrophone),features);
                if(withMicrophone) {
                    assertEquals(5,Wire.integer(features,1,-1));
                    boolean found=false;
                    for(Wire.Field entry:Wire.fields(features)) if(entry.id==2 && "VOICE_MIC".equals(string(entry.data,1))) {
                        assertEquals(0,Wire.integer(entry.data,2,-1));found=true;
                    }
                    assertTrue(found);
                }
                peer.send(65551,Wire.EMPTY);
                byte[] request=peer.command(98311).body;assertArrayEquals(Wire.video(SIZE),request);peer.send(65544,request);
            }
            peer.command(98313);
            byte[] frame={0,0,0,1,0x65,1,2,3};peer.media(8240,0x20001,xiaomi?crypt(frame,true):frame);
            byte[] format=Wire.join(Wire.number(1,48000),Wire.number(2,2),Wire.number(3,16)),samples={1,0,2,0};
            peer.media(9240,0x30001,xiaomi?crypt(format,true):format);peer.media(9240,0x30006,samples);
            peer.media(9241,0x40001,xiaomi?crypt(format,true):format);peer.media(9241,0x40003,xiaomi?crypt(samples,true):samples);
            assertTrue(video.await(3,TimeUnit.SECONDS));assertTrue(pcm.await(3,TimeUnit.SECONDS));assertNull(failure.get());
            assertEquals(SIZE,size.get());assertArrayEquals(frame,receivedVideo.get());
            assertArrayEquals(samples,receivedAudio.get(9240));assertArrayEquals(samples,receivedAudio.get(9241));
            if(withMicrophone) {
                peer.send(65572,Wire.EMPTY);
                CarLifeMicrophone.Capture capture=captures.poll(3,TimeUnit.SECONDS);assertNotNull(capture);
                byte[] speech=new byte[640];speech[0]=23;capture.pcm(speech);
                Wire.Packet packet=Wire.read(peer.sockets.get(9242).getInputStream(),false,5000);
                assertEquals(0x58001,packet.type);assertArrayEquals(speech,xiaomi?crypt(packet.body,false):packet.body);
                assertEquals(xiaomi?656:640,packet.body.length);assertEquals(0,packet.sequence);
                capture.pcm(speech);packet=Wire.read(peer.sockets.get(9242).getInputStream(),false,5000);
                assertEquals(20,packet.sequence);
                assertArrayEquals(speech,xiaomi?crypt(packet.body,false):packet.body);
                peer.send(65571,Wire.EMPTY);assertTrue(microphoneStopped.await(3,TimeUnit.SECONDS));
                capture.pcm(speech);
                peer.sockets.get(9242).setSoTimeout(200);
                try {Wire.read(peer.sockets.get(9242).getInputStream(),false,5000);fail("stopped capture wrote PCM");}
                catch(SocketTimeoutException expected) {}
            }
            if(multi) {
                session.touch(0,50,60,0,0); session.touch(261,50,60,150,160); session.touch(2,40,60,170,160);
                for(int action:new int[]{0,261,2}) {
                    Wire.Packet touch=Wire.read(peer.sockets.get(9340).getInputStream(),true,65535);
                    if(action==0) { assertEquals(425986,touch.type);assertEquals(50,Wire.integer(touch.body,1,-1));continue; }
                    assertEquals(425985,touch.type);assertEquals(action,Wire.integer(touch.body,1,-1));
                    assertEquals(action==2?40:50,Wire.integer(touch.body,2,-1));
                    assertEquals(action==0?0:action==2?170:150,Wire.integer(touch.body,4,-1));
                    assertEquals(action==0?0:160,Wire.integer(touch.body,5,-1));
                }
                session.stop().get(5,TimeUnit.SECONDS);
                for(int action:new int[]{262,1}) {
                    Wire.Packet touch=Wire.read(peer.sockets.get(9340).getInputStream(),true,65535);
                    assertEquals(action==1?425987:425985,touch.type);
                    assertEquals(action==1?40:action,Wire.integer(touch.body,1,-1));
                }
                assertNull(failure.get());return;
            }
            session.touch(0,50,60);
            Wire.Packet down=Wire.read(peer.sockets.get(9340).getInputStream(),true,65535);
            assertEquals(425986,down.type);byte[] touch=xiaomi?crypt(down.body,false):down.body;
            assertEquals(50,Wire.integer(touch,1,-1));assertEquals(60,Wire.integer(touch,2,-1));
            session.stop().get(5,TimeUnit.SECONDS);
            Wire.Packet up=Wire.read(peer.sockets.get(9340).getInputStream(),true,65535);assertEquals(425987,up.type);
            assertNull(failure.get());
        } finally {session.stop().get(5,TimeUnit.SECONDS);}
    }

    @Test public void autoReopensSevenChannelsAsPlaintextOnlyAfterWireVersionRejection() throws Exception {
        CountDownLatch fallback = new CountDownLatch(1), video = new CountDownLatch(1);
        AtomicReference<String> failure = new AtomicReference<>();
        CarLifeRecovery recovery = new CarLifeRecovery(new CarLifeRecovery.Listener() {
            public void log(String s) { if (s.startsWith("AUTO_PROTOCOL_FALLBACK")) fallback.countDown(); }
            public void waiting(String s) {}
            public void failed(String s) { failure.compareAndSet(null,s); }
            public void video(byte[] b,long pts) { video.countDown(); }
            public void ended() {}
        }, null, SIZE, CarLifeProtocolMode.AUTO);
        try (Peer peer = new Peer()) {
            recovery.start(); peer.accept();
            assertArrayEquals(XiaomiHandshake.version(), peer.command(98305).body);
            peer.send(65538,Wire.number(1,0));
            assertTrue(fallback.await(3,TimeUnit.SECONDS));
            for (Socket old : peer.sockets.values()) old.close();
            peer.sockets.clear(); peer.accept();
            assertArrayEquals(Wire.version(),peer.command(98305).body);
            peer.send(65538,Wire.number(1,1)); peer.command(98343);
            peer.send(65540,Wire.EMPTY); peer.command(98307);
            assertArrayEquals(Wire.features(),peer.command(98386).body);
            peer.send(65551,Wire.EMPTY);
            byte[] request=peer.command(98311).body;
            peer.send(65544,request); peer.command(98313);
            peer.media(8240,0x20001,new byte[]{0,0,0,1,0x65,1,2,3});
            assertTrue(video.await(3,TimeUnit.SECONDS)); assertNull(failure.get());
        } finally { recovery.stop().get(5,TimeUnit.SECONDS); }
    }
}
