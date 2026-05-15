package com.sentient;

/**
 * Sentient Assistant — Web-based UI served by embedded Javalin server.
 */
public class App {

    private static WebServer server;

    public static void main(String[] args) {
        server = new WebServer();

        // Graceful shutdown on JVM exit
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("[App] Shutting down...");
            if (server != null)
                server.stop();
        }));

        server.start();
    }
}
