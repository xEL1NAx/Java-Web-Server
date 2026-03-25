package server;

import java.nio.file.Path;

public final class Main {
    public static void main(String[] args) throws Exception {
        Path configPath = args.length > 0 ? Path.of(args[0]) : Path.of("src/main/resources/server-config.json");
        Config config = Config.load(configPath);
        HttpServer server = new HttpServer(config);
        server.start();
        Thread.currentThread().join();
    }
}
