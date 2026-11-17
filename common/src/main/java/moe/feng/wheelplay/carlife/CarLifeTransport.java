package moe.feng.wheelplay.carlife;

import java.io.*;

/** A session owns one transport; writes always contain one complete inner CarLife message. */
public interface CarLifeTransport extends AutoCloseable {
    interface Factory { CarLifeTransport create(); }
    void connect() throws Exception;
    InputStream input(int port) throws IOException;
    void write(int port, byte[] message) throws IOException;
    default int connectionType() { return 5; }
    default boolean usb() { return false; }
    @Override void close() throws IOException;
}
