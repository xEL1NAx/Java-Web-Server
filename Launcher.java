import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public final class Launcher {
    private static final String EXPORTS = "java.net.http/jdk.internal.net.http.hpack=ALL-UNNAMED";

    private Launcher() {
    }

    public static void main(String[] args) throws Exception {
        Path projectRoot = Path.of("").toAbsolutePath().normalize();
        Path sourceRoot = projectRoot.resolve("src/main/java");
        Path outDir = projectRoot.resolve("out");

        compileSources(sourceRoot, outDir);

        String[] forwarded = args.length == 0
                ? new String[]{"src/main/resources/server-config.json"}
                : args;

        try (URLClassLoader classLoader = new URLClassLoader(new URL[]{outDir.toUri().toURL()}, Launcher.class.getClassLoader())) {
            Thread.currentThread().setContextClassLoader(classLoader);
            Class<?> mainClass = Class.forName("server.Main", true, classLoader);
            Method main = mainClass.getMethod("main", String[].class);
            main.invoke(null, (Object) forwarded);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Exception ex) {
                throw ex;
            }
            if (cause instanceof Error err) {
                throw err;
            }
            throw e;
        }
    }

    private static void compileSources(Path sourceRoot, Path outDir) throws Exception {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new IllegalStateException("No Java compiler found. Install a full JDK (not JRE).\n"
                    + "Then run: java --add-exports " + EXPORTS + " Launcher.java");
        }

        if (!Files.isDirectory(sourceRoot)) {
            throw new IllegalStateException("Missing source directory: " + sourceRoot);
        }

        Files.createDirectories(outDir);

        List<Path> sources;
        try (Stream<Path> stream = Files.walk(sourceRoot)) {
            sources = stream
                    .filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".java"))
                    .sorted(Comparator.comparing(Path::toString))
                    .collect(Collectors.toList());
        }

        if (sources.isEmpty()) {
            throw new IllegalStateException("No .java sources found under: " + sourceRoot);
        }

        List<String> options = new ArrayList<>();
        options.add("--add-exports");
        options.add(EXPORTS);
        options.add("-encoding");
        options.add("UTF-8");
        options.add("-d");
        options.add(outDir.toString());

        try (StandardJavaFileManager fileManager = compiler.getStandardFileManager(null, null, null)) {
            Iterable<? extends JavaFileObject> compilationUnits = fileManager.getJavaFileObjectsFromPaths(sources);
            Boolean ok = compiler.getTask(null, fileManager, null, options, null, compilationUnits).call();
            if (!Boolean.TRUE.equals(ok)) {
                throw new IllegalStateException("Compilation failed. See javac errors above.");
            }
        }
    }
}