package moe.feng.wheelplay.carlife;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.security.*;
import java.util.*;
import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;

/**
 * CarWith 4.1 handshake adapted from wangxso/mcarplay (MIT), commit 8e25e0f.
 * See docs/CARLIFE_XIAOMI.md. Control-thread owned; only the published AES key is shared with readers.
 * No plaintext fallback, fabricated authentication success, or legacy-mode retry.
 */
final class XiaomiHandshake implements AutoCloseable {
    static final class VersionRejectedException extends IOException {
        VersionRejectedException() { super("4.1 version explicitly rejected"); }
    }
    static final class Action {
        final int type;
        final byte[] payload;
        Action(int type, byte[] payload) { this.type=type; this.payload=payload; }
    }
    private boolean matched, infoSeen, proofAccepted, admitted, featureRequested, featuresSent;
    private boolean rsaPublished, seedAccepted, ready, videoRequested, running;
    private String nonce, brand, model, sdk;
    private KeyPair rsa;
    private volatile byte[] aes;
    private volatile boolean closed;
    private VideoSize confirmed;
    private final boolean microphone, usb;

    XiaomiHandshake() { this(false); }
    XiaomiHandshake(boolean microphone) { this(microphone, false); }
    XiaomiHandshake(boolean microphone, boolean usb) { this.microphone = microphone; this.usb = usb; }

    static byte[] version() { return Wire.join(Wire.number(1,4), Wire.number(2,1)); }
    boolean matched() { return matched; }
    boolean running() { return running; }
    VideoSize confirmed() { return confirmed; }

    List<Action> accept(int type, byte[] raw, VideoSize size) throws Exception {
        require(!closed, "closed");
        // The peer only enables encryption after receiving our seed ACK. Plain control
        // messages already in flight may follow the seed; READY marks the command boundary.
        // Never retry malformed ciphertext as plaintext after that boundary.
        byte[] body=ready ? decrypt(raw) : raw;
        List<Action> actions=new ArrayList<>();
        switch(type) {
            case 0x10002:
                require(!matched,"version repeated");
                long versionResult = Wire.integer(body,1,-1);
                if (versionResult == 0) throw new VersionRejectedException();
                require(versionResult == 1,"invalid version result");
                matched=true;
                actions.add(new Action(0x18027,Wire.join(Wire.string(1,"wheelplay-local"),Wire.string(2,"0.1.0"),
                    Wire.number(3,1),Wire.string(4,"29999999"),Wire.number(5,1),Wire.number(6,0),Wire.number(7,0))));
                break;
            case 0x10004:
                require(matched && !infoSeen,"unexpected device info");
                brand=field(body,4,128);model=field(body,14,128);sdk=field(body,20,128);
                byte[] random=new byte[16];new SecureRandom().nextBytes(random);nonce=hex(random);
                infoSeen=true;
                actions.add(new Action(0x18003,Wire.join(Wire.string(1,"Android"),Wire.string(4,"WheelPlay"),
                    Wire.string(14,"Xiaomi"),Wire.string(20,"CarWith"))));
                actions.add(new Action(0x18048,Wire.string(1,"2.0;"+nonce)));
                break;
            case 0x10049:
                require(infoSeen && !proofAccepted,"unexpected authentication proof");
                String proof=field(body,1,32);
                byte[] expected=hex(MessageDigest.getInstance("MD5").digest(
                    ("CARLIFE"+brand+model+sdk+nonce).getBytes(StandardCharsets.UTF_8))).getBytes(StandardCharsets.US_ASCII);
                require(proof.matches("[0-9a-f]{32}") && MessageDigest.isEqual(expected,proof.getBytes(StandardCharsets.US_ASCII)),"authentication rejected");
                proofAccepted=true;nonce=null;brand=null;model=null;sdk=null;
                actions.add(new Action(0x1804A,Wire.number(1,1)));
                break;
            case 0x1004B:
                require(matched && !admitted && !videoRequested && Wire.integer(body,1,-1)==1,"phone admission rejected or repeated");
                admitted=true;actions.add(new Action(0x1804C,Wire.EMPTY));break;
            case 0x10051:
                require(matched && !featureRequested && !featuresSent,"unexpected feature request");
                featureRequested=true;break;
            case 0x1006A:
                require(featuresSent && !rsaPublished,"unexpected RSA request");
                KeyPairGenerator generator=KeyPairGenerator.getInstance("RSA");generator.initialize(2048);
                rsa=generator.generateKeyPair();rsaPublished=true;
                actions.add(new Action(0x1806B,Wire.string(1,Base64.getEncoder().encodeToString(rsa.getPublic().getEncoded()))));
                break;
            case 0x1006C:
                require(rsaPublished && !seedAccepted,"unexpected AES seed");
                Cipher cipher=Cipher.getInstance("RSA/ECB/PKCS1Padding");cipher.init(Cipher.DECRYPT_MODE,rsa.getPrivate());
                byte[] key=cipher.doFinal(Base64.getDecoder().decode(field(body,1,1024)));
                if(key.length!=16 && key.length!=24 && key.length!=32) {Arrays.fill(key,(byte)0);throw new IOException("XIAOMI invalid AES seed length");}
                aes=key;seedAccepted=true;rsa=null;
                actions.add(new Action(0x1806D,Wire.EMPTY));break;
            case 0x1006E:
                require(seedAccepted && !ready && body.length==0,"unexpected encryption ready");
                ready=true;break;
            case 0x10008:
                require(videoRequested && !running,"encoder reply before encrypted video request");
                long width=Wire.integer(body,1,0),height=Wire.integer(body,2,0),fps=Wire.integer(body,3,0);
                require(width>0 && width<=Integer.MAX_VALUE && height>0 && height<=Integer.MAX_VALUE && fps>0 && fps<=120,"invalid encoder format");
                confirmed=new VideoSize((int)width,(int)height);running=true;
                actions.add(new Action(0x18009,Wire.EMPTY));break;
            case 0x1001B:
                require(matched,"foreground before version");actions.add(new Action(0x18025,Wire.EMPTY));break;
            default: break;
        }
        if(proofAccepted && featureRequested && !featuresSent) {
            featuresSent=true;
            byte[] features = Wire.join(Wire.number(1,2 + (microphone ? 1 : 0) + (usb ? 1 : 0)),Wire.bytes(2,
                Wire.join(Wire.string(1,"CONTENT_ENCRYPTION"),Wire.number(2,1))),Wire.bytes(2,
                Wire.join(Wire.string(1,"DISABLE_RESOLUTION_OPTION"),Wire.number(2,1))));
            if (microphone) features = Wire.join(features, Wire.bytes(2,
                Wire.join(Wire.string(1,"VOICE_MIC"),Wire.number(2,0))));
            if (usb) features = Wire.join(features, Wire.bytes(2,
                Wire.join(Wire.string(1,"CONNECT_TYPE"),Wire.number(2,2))));
            actions.add(new Action(0x18052,features));
        }
        if(ready && admitted && !videoRequested) {
            videoRequested=true;
            actions.add(new Action(0x18007,Wire.join(Wire.number(1,size.width),Wire.number(2,size.height),Wire.number(3,30))));
        }
        return actions;
    }
    /** The VR header stays plaintext; its length counts the padded AES payload. */
    byte[] voice(byte[] packet) throws IOException {
        require(aes != null && !closed, "voice before encryption or after close");
        try {
            byte[] payload = encrypt(Arrays.copyOfRange(packet,12,packet.length));
            return ByteBuffer.allocate(12 + payload.length).putInt(payload.length)
                .put(packet,4,8).put(payload).array();
        } catch (Exception error) { throw new IOException("XIAOMI voice encryption failed",error); }
    }
    byte[] encrypt(byte[] data) throws Exception { return crypt(data,Cipher.ENCRYPT_MODE); }
    byte[] decrypt(byte[] data) throws Exception { return crypt(data,Cipher.DECRYPT_MODE); }
    byte[] media(int port,int type,byte[] data) throws Exception {
        require(aes!=null,"media before encryption");
        // Xiaomi PcmHoldHelper protects INIT but writes mixed MEDIA_DATA PCM directly.
        if(port==9240 && type==0x30006) return data;
        return decrypt(data);
    }
    private byte[] crypt(byte[] data,int mode) throws Exception {
        byte[] key=aes;
        if(key==null || data.length==0)return data;
        // Wire requirement from CarLife, not a cipher chosen for new protocols.
        Cipher cipher=Cipher.getInstance("AES/ECB/PKCS5Padding");
        cipher.init(mode,new SecretKeySpec(key,"AES"));return cipher.doFinal(data);
    }
    private static String field(byte[] data,int id,int max) throws IOException {
        for(Wire.Field f:Wire.fields(data)) if(f.id==id && f.data!=null) {
            require(f.data.length>0 && f.data.length<=max,"invalid string field "+id);
            String value=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(f.data)).toString().trim();
            require(!value.isEmpty(),"blank string field "+id);return value;
        }
        throw new IOException("XIAOMI missing string field "+id);
    }
    private static String hex(byte[] data) {
        StringBuilder out=new StringBuilder();for(byte b:data)out.append(String.format(Locale.ROOT,"%02x",b&255));return out.toString();
    }
    private static void require(boolean condition,String reason) throws IOException {
        if(!condition)throw new IOException("XIAOMI "+reason);
    }
    /** Call after readers and control have joined. */
    @Override public void close() {closed=true;rsa=null;nonce=null;brand=null;model=null;sdk=null;byte[] key=aes;aes=null;if(key!=null)Arrays.fill(key,(byte)0);}
}
