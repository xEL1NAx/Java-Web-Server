package server;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

public final class Logger {
    private static final DateTimeFormatter TS = DateTimeFormatter.ISO_OFFSET_DATE_TIME;
    private static final Object LOCK = new Object();
    private static Path accessLog = Path.of("logs", "access.log");
    private static Path errorLog = Path.of("logs", "error.log");

    private Logger() {}

    public static void configure(Path accessPath, Path errorPath) {
        accessLog = accessPath;
        errorLog = errorPath;
        try {
            Files.createDirectories(accessLog.getParent());
            Files.createDirectories(errorLog.getParent());
            if (!Files.exists(accessLog)) Files.createFile(accessLog);
            if (!Files.exists(errorLog)) Files.createFile(errorLog);
        } catch (IOException e) {
            System.err.println("Failed to initialize log files: " + e.getMessage());
        }
    }

    public static void info(String msg) {
        write("INFO", msg, false);
    }

    public static void warn(String msg) {
        write("WARN", msg, false);
    }

    public static void error(String msg, Throwable t) {
        StringWriter sw = new StringWriter();
        if (t != null) {
            t.printStackTrace(new PrintWriter(sw));
        }
        write("ERROR", msg + (t == null ? "" : System.lineSeparator() + sw), true);
    }

    public static void access(String remoteIp, String method, String host, String path, int status, long bytes, long durationMicros) {
        String line = String.format("%s %s \"%s %s\" host=%s status=%d bytes=%d duration_us=%d%n",
                TS.format(ZonedDateTime.now()),
                remoteIp,
                method,
                path,
                host == null ? "-" : host,
                status,
                bytes,
                durationMicros);
        synchronized (LOCK) {
            try {
                Files.writeString(accessLog, line, java.nio.file.StandardOpenOption.APPEND);
            } catch (IOException e) {
                System.err.println("Access log write failed: " + e.getMessage());
            }
        }
    }

    private static void write(String level, String msg, boolean error) {
        String line = String.format("%s [%s] %s%n", TS.format(ZonedDateTime.now()), level, msg);
        Path target = error ? errorLog : accessLog;
        synchronized (LOCK) {
            try {
                Files.writeString(target, line, java.nio.file.StandardOpenOption.APPEND);
            } catch (IOException e) {
                System.err.println("Log write failed: " + e.getMessage());
            }
        }
        System.out.print(line);
    }
}
