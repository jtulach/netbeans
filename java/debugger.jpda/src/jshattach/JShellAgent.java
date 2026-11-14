package jshattach;


import jdk.jshell.execution.RemoteExecutionControl;
import jdk.jshell.execution.Util;
import java.io.*;
import java.net.*;

/** Injected into the debuggee via JDI. Must stay self-contained: one class file, no nested classes. */
public final class JShellAgent {
    private JShellAgent() {}

    /** Binds synchronously (so the port is ready when this returns), serves on a daemon thread. */
    public static int start() throws IOException {
        ServerSocket ss = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
        int port = ss.getLocalPort();
        Thread t = new Thread(() -> serve(ss), "jshell-agent-" + port);
        t.setDaemon(true);
        t.start();
        return port;
    }

    private static void serve(ServerSocket ss) {
        while (!ss.isClosed()) {
            try (Socket s = ss.accept()) {
                ObjectOutputStream out = new ObjectOutputStream(s.getOutputStream());
                out.flush();
                ObjectInputStream in = new ObjectInputStream(s.getInputStream());
                Util.forwardExecutionControl(new RemoteExecutionControl(), in, out);
            } catch (Throwable e) {
                e.printStackTrace();
            }
        }
    }
}