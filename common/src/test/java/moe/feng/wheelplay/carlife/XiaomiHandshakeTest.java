package moe.feng.wheelplay.carlife;

import org.junit.Test;
import static org.junit.Assert.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.spec.X509EncodedKeySpec;
import java.util.*;
import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;

public class XiaomiHandshakeTest {
    static final VideoSize SIZE=new VideoSize(1280,720);
    static final byte[] KEY=new byte[16];
    static String string(byte[] body,int field) throws Exception {
        for(Wire.Field f:Wire.fields(body))if(f.id==field && f.data!=null)return new String(f.data,StandardCharsets.UTF_8);
        throw new AssertionError("missing field "+field);
    }
    static byte[] proof(String challenge) throws Exception {
        byte[] digest=MessageDigest.getInstance("MD5").digest(("CARLIFEXiaomiTestCarWith"+challenge.substring(4)).getBytes(StandardCharsets.UTF_8));
        StringBuilder out=new StringBuilder();for(byte b:digest)out.append(String.format("%02x",b&255));
        return Wire.string(1,out.toString());
    }
    static byte[] crypt(byte[] data,boolean encrypt) throws Exception {
        if(data.length==0)return data;
        Cipher c=Cipher.getInstance("AES/ECB/PKCS5Padding");c.init(encrypt?Cipher.ENCRYPT_MODE:Cipher.DECRYPT_MODE,new SecretKeySpec(KEY,"AES"));return c.doFinal(data);
    }
    static byte[] seed(String publicKey,byte[] key) throws Exception {
        Cipher c=Cipher.getInstance("RSA/ECB/PKCS1Padding");
        c.init(Cipher.ENCRYPT_MODE,KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(publicKey))));
        return Wire.string(1,Base64.getEncoder().encodeToString(c.doFinal(key)));
    }
    static byte[] info(){return Wire.join(Wire.string(4,"Xiaomi"),Wire.string(14,"Test"),Wire.string(20,"CarWith"));}
    static XiaomiHandshake authenticated(boolean admit) throws Exception {
        XiaomiHandshake h=new XiaomiHandshake();h.accept(0x10002,Wire.number(1,1),SIZE);
        List<XiaomiHandshake.Action> actions=h.accept(0x10004,info(),SIZE);
        assertEquals(0x18048,actions.get(1).type);
        assertTrue(h.accept(0x10051,Wire.EMPTY,SIZE).isEmpty());
        actions=h.accept(0x10049,proof(string(actions.get(1).payload,1)),SIZE);
        assertEquals(0x1804A,actions.get(0).type);assertEquals(0x18052,actions.get(1).type);
        if(admit)h.accept(0x1004B,Wire.number(1,1),SIZE);
        return h;
    }
    static XiaomiHandshake encrypted(boolean admit) throws Exception {
        XiaomiHandshake h=authenticated(admit);
        String publicKey=string(h.accept(0x1006A,Wire.EMPTY,SIZE).get(0).payload,1);
        List<XiaomiHandshake.Action> ack=h.accept(0x1006C,seed(publicKey,KEY),SIZE);
        assertEquals(0x1806D,ack.get(0).type);assertEquals(0,ack.get(0).payload.length);
        return h;
    }
    @Test public void waitsForBothEncryptionReadyAndPhoneAdmission() throws Exception {
        try(XiaomiHandshake h=encrypted(false)) {
            assertTrue(h.accept(0x1006E,Wire.EMPTY,SIZE).isEmpty());
            List<XiaomiHandshake.Action> actions=h.accept(0x1004B,crypt(Wire.number(1,1),true),SIZE);
            assertEquals(2,actions.size());assertEquals(0x18007,actions.get(1).type);
            assertArrayEquals(Wire.join(Wire.number(1,1280),Wire.number(2,720),Wire.number(3,30)),actions.get(1).payload);
            assertEquals(0x18009,h.accept(0x10008,crypt(actions.get(1).payload,true),SIZE).get(0).type);
            assertTrue(h.running());assertEquals(SIZE,h.confirmed());
        }
    }
    @Test public void plaintextInFlightAfterSeedIsAcceptedOnlyUntilPeerReady() throws Exception {
        try (XiaomiHandshake h=encrypted(false)) {
            // The source has sent its key but has not received/processed our ACK yet.
            assertTrue(h.accept(0x10046,Wire.number(1,1),SIZE).isEmpty());
            assertEquals(0x1804C,h.accept(0x1004B,Wire.number(1,1),SIZE).get(0).type);
            assertEquals(0x18007,h.accept(0x1006E,Wire.EMPTY,SIZE).get(0).type);
            assertThrows(Exception.class,()->h.accept(0x10046,Wire.number(1,1),SIZE));
            assertTrue(h.accept(0x10046,crypt(Wire.number(1,1),true),SIZE).isEmpty());
        }
    }

    @Test public void rejectsWrongProofMissingFieldsAndPrematureRsa() throws Exception {
        try(XiaomiHandshake h=new XiaomiHandshake()) {
            assertThrows(IOException.class,()->h.accept(0x1006A,Wire.EMPTY,SIZE));
            h.accept(0x10002,Wire.number(1,1),SIZE);
            assertThrows(IOException.class,()->h.accept(0x10004,Wire.string(4,"Xiaomi"),SIZE));
            h.accept(0x10004,info(),SIZE);
            assertThrows(IOException.class,()->h.accept(0x10049,Wire.string(1,"00000000000000000000000000000000"),SIZE));
            assertThrows(IOException.class,()->h.accept(0x1006A,Wire.EMPTY,SIZE));
        }
    }
    @Test public void rejectsBadSeedLengthAndRepeatedReady() throws Exception {
        try(XiaomiHandshake h=authenticated(true)) {
            String pub=string(h.accept(0x1006A,Wire.EMPTY,SIZE).get(0).payload,1);
            assertThrows(IOException.class,()->h.accept(0x1006C,seed(pub,new byte[15]),SIZE));
        }
        try(XiaomiHandshake h=encrypted(true)) {
            assertEquals(0x18007,h.accept(0x1006E,Wire.EMPTY,SIZE).get(0).type);
            assertThrows(IOException.class,()->h.accept(0x1006E,Wire.EMPTY,SIZE));
        }
    }
    @Test public void onlyXiaomiMediaPcmIsPlainAndCorruptionNeverFallsBack() throws Exception {
        try(XiaomiHandshake h=encrypted(true)) {
            byte[] raw={1,2,3,4};
            assertArrayEquals(raw,h.media(9240,0x30006,raw));
            assertThrows(Exception.class,()->h.media(9240,0x30001,raw));
            assertThrows(Exception.class,()->h.media(9241,0x40003,raw));
            assertThrows(Exception.class,()->h.media(8240,0x20001,raw));
            assertArrayEquals(raw,h.media(9241,0x40003,crypt(raw,true)));
            assertArrayEquals(raw,crypt(h.encrypt(raw),false));
            assertEquals(0,h.encrypt(Wire.EMPTY).length);
        }
    }
    @Test public void defaultAndAllOtherBrandsStayLegacy() {
        assertEquals(CarLifeProtocolMode.XIAOMI_4_1,CarLifeProtocolMode.forBrand("xiaomi"));
        for(String id:Arrays.asList("vivo","generic","honor","oppo","samsung",null))
            assertEquals(CarLifeProtocolMode.LEGACY_3_0,CarLifeProtocolMode.forBrand(id));
        assertArrayEquals(new byte[]{8,3,16,0},Wire.version());
        assertArrayEquals(new byte[]{8,4,16,1},XiaomiHandshake.version());
    }

    @Test public void onlyExplicitInitialZeroIsVersionRejection() throws Exception {
        try (XiaomiHandshake h = new XiaomiHandshake()) {
            assertThrows(XiaomiHandshake.VersionRejectedException.class, () -> h.accept(0x10002, Wire.number(1,0), SIZE));
        }
        for (byte[] invalid : new byte[][]{Wire.EMPTY, Wire.number(1,2), new byte[]{8,(byte)128}}) {
            try (XiaomiHandshake h = new XiaomiHandshake()) {
                Exception error = assertThrows(Exception.class, () -> h.accept(0x10002, invalid, SIZE));
                assertFalse(error instanceof XiaomiHandshake.VersionRejectedException);
            }
        }
        try (XiaomiHandshake h = new XiaomiHandshake()) {
            h.accept(0x10002, Wire.number(1,1), SIZE);
            Exception error = assertThrows(Exception.class, () -> h.accept(0x10002, Wire.number(1,0), SIZE));
            assertFalse(error instanceof XiaomiHandshake.VersionRejectedException);
        }
    }
}
