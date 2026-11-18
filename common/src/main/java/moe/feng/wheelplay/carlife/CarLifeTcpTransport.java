package moe.feng.wheelplay.carlife;

import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** The existing seven TCP channels, also used for same-device loopback. */
public final class CarLifeTcpTransport implements CarLifeTransport {
    private final String host;
    private final Map<Integer, Socket> sockets = new ConcurrentHashMap<>();
    private final AtomicBoolean closed = new AtomicBoolean();
    public CarLifeTcpTransport(String host) {
        this.host = Objects.requireNonNull(host);
        if (host.trim().isEmpty()) throw new IllegalArgumentException("source host is empty");
    }
    @Override public void connect() throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
        for (int port : Wire.PORTS) {
            while (true) {
                Socket socket = new Socket();
                sockets.put(port, socket);
                if (closed.get()) { socket.close(); throw new IOException("transport closed"); }
                try { socket.connect(new InetSocketAddress(host, port), 1000); socket.setTcpNoDelay(true); break; }
                catch (ConnectException error) {
                    socket.close();
                    if (closed.get() || System.nanoTime() >= deadline) throw error;
                    Thread.sleep(100);
                }
            }
        }
    }
    private Socket socket(int port) throws IOException {
        Socket socket = sockets.get(port);
        if (closed.get() || socket == null) throw new IOException("channel absent or closed: " + port);
        return socket;
    }
    @Override public InputStream input(int port) throws IOException { return socket(port).getInputStream(); }
    @Override public void write(int port, byte[] message) throws IOException { socket(port).getOutputStream().write(message); }
    @Override public void close() throws IOException {
        closed.set(true);
        IOException failure = null;
        for (Socket socket : sockets.values()) try { socket.close(); } catch (IOException error) { failure = error; }
        if (failure != null) throw failure;
    }
}
