package io.github.jessielee3260.upjasperreport6to7;

import net.sf.jasperreports.engine.JRPropertiesUtil;
import net.sf.jasperreports.engine.JasperReportsContext;
import net.sf.jasperreports.engine.design.JRCompiler;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Resolves the runtime classpath that JasperReports' expression compilers need.
 *
 * <p>When this application is started from a Spring Boot fat jar, {@code java.class.path}
 * contains only the outer application jar. JasperReports' Java expression compiler uses
 * {@link JRCompiler#COMPILER_CLASSPATH} (defaulting to {@code java.class.path}), so nested
 * libraries under {@code BOOT-INF/lib} are invisible and generated evaluator sources fail
 * with unresolved {@code net.sf.jasperreports.engine.*} symbols.</p>
 *
 * <p>JasperReports 7.0.8's Groovy compiler also ignores the classpath parameter internally,
 * so Groovy expressions need a thread context classloader that can see the effective runtime
 * classpath. This helper provides both the property value and a classloader wrapper.</p>
 */
public final class CompilerClasspath {

    private static volatile List<Path> extractedSpringBootFatJarEntries;

    private CompilerClasspath() {
    }

    /**
     * Applies the effective classpath to the JasperReports compiler property and returns it
     * for logging/diagnostics.
     */
    public static String apply(JasperReportsContext ctx) {
        String classpath = resolve();
        if (classpath != null && !classpath.isBlank()) {
            JRPropertiesUtil.getInstance(ctx).setProperty(JRCompiler.COMPILER_CLASSPATH, classpath);
        }
        return classpath;
    }

    /**
     * Builds a classloader that exposes the effective classpath for Groovy expression
     * compilation while keeping the original context classloader as parent.
     */
    public static ClassLoader newClassLoader() {
        ClassLoader current = Thread.currentThread().getContextClassLoader();
        if (current instanceof URLClassLoader) {
            return current;
        }
        List<URL> urls = toUrls(resolveEntries());
        if (urls.isEmpty()) {
            return current;
        }
        return new URLClassLoader(urls.toArray(URL[]::new), current);
    }

    /** Returns the effective compiler classpath as a platform path-separator string. */
    public static String resolve() {
        List<Path> entries = resolveEntries();
        if (entries.isEmpty()) {
            return null;
        }
        return entries.stream()
                .map(Path::toAbsolutePath)
                .map(Path::normalize)
                .map(Path::toString)
                .collect(Collectors.joining(File.pathSeparator));
    }

    static List<Path> resolveEntries() {
        List<Path> bootEntries = springBootLoaderPathEntries();
        if (!bootEntries.isEmpty()) {
            return bootEntries;
        }

        List<Path> fatJarEntries = springBootFatJarEntries();
        if (!fatJarEntries.isEmpty()) {
            return fatJarEntries;
        }

        List<Path> loaderEntries = contextUrlClassLoaderEntries();
        if (!loaderEntries.isEmpty()) {
            return loaderEntries;
        }

        return javaClassPathEntries();
    }

    private static List<Path> springBootLoaderPathEntries() {
        String loaderPath = System.getProperty("loader.path");
        if (loaderPath == null || loaderPath.isBlank()) {
            return List.of();
        }

        List<Path> entries = new ArrayList<>();
        Path bootInf = Path.of(loaderPath, "BOOT-INF");
        Path classes = bootInf.resolve("classes");
        if (Files.isDirectory(classes)) {
            entries.add(classes);
        }

        Path lib = bootInf.resolve("lib");
        if (Files.isDirectory(lib)) {
            try (Stream<Path> stream = Files.list(lib)) {
                stream.sorted().forEach(entries::add);
            } catch (IOException ignored) {
                return List.of();
            }
        }
        return entries;
    }

    private static List<Path> springBootFatJarEntries() {
        List<Path> cached = extractedSpringBootFatJarEntries;
        if (cached != null) {
            return cached;
        }

        for (Path entry : javaClassPathEntries()) {
            if (Files.isRegularFile(entry) && entry.toString().endsWith(".jar")) {
                try {
                    List<Path> extracted = extractSpringBootFatJar(entry);
                    if (!extracted.isEmpty()) {
                        extractedSpringBootFatJarEntries = extracted;
                        return extracted;
                    }
                } catch (IOException ignored) {
                    // Not a Spring Boot fat jar or cannot be extracted; try the next entry.
                }
            }
        }
        extractedSpringBootFatJarEntries = List.of();
        return List.of();
    }

    private static List<Path> extractSpringBootFatJar(Path jar) throws IOException {
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            boolean hasNestedLibraries = zip.stream()
                    .map(ZipEntry::getName)
                    .anyMatch(name -> name.startsWith("BOOT-INF/lib/") && name.endsWith(".jar"));
            if (!hasNestedLibraries) {
                return List.of();
            }

            Path root = Files.createTempDirectory("upj-jr-compiler-cp-");
            Path classesDir = root.resolve("classes");
            Path libDir = root.resolve("lib");

            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry zipEntry = entries.nextElement();
                if (zipEntry.isDirectory()) {
                    continue;
                }
                String name = zipEntry.getName();
                Path target = null;
                if (name.startsWith("BOOT-INF/classes/")) {
                    target = classesDir.resolve(name.substring("BOOT-INF/classes/".length()));
                } else if (name.startsWith("BOOT-INF/lib/") && name.endsWith(".jar")) {
                    target = libDir.resolve(Path.of(name).getFileName().toString());
                }
                if (target != null) {
                    Files.createDirectories(target.getParent());
                    try (var in = zip.getInputStream(zipEntry)) {
                        Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
                    }
                }
            }

            List<Path> extracted = new ArrayList<>();
            if (Files.isDirectory(classesDir)) {
                extracted.add(classesDir);
            }
            if (Files.isDirectory(libDir)) {
                try (Stream<Path> stream = Files.list(libDir)) {
                    stream.sorted().forEach(extracted::add);
                }
            }
            return extracted;
        }
    }

    private static List<Path> contextUrlClassLoaderEntries() {
        ClassLoader current = Thread.currentThread().getContextClassLoader();
        if (!(current instanceof URLClassLoader urlClassLoader)) {
            return List.of();
        }

        List<Path> entries = new ArrayList<>();
        for (URL url : urlClassLoader.getURLs()) {
            try {
                entries.add(Paths.get(url.toURI()));
            } catch (Exception ignored) {
                // Non-file URLs are not suitable for javac/groovy classpath entries.
            }
        }
        return entries;
    }

    private static List<Path> javaClassPathEntries() {
        String javaClassPath = System.getProperty("java.class.path", "");
        if (javaClassPath.isBlank()) {
            return List.of();
        }

        List<Path> entries = new ArrayList<>();
        for (String entry : javaClassPath.split(File.pathSeparator)) {
            if (!entry.isBlank()) {
                entries.add(Path.of(entry));
            }
        }
        return entries;
    }

    private static List<URL> toUrls(List<Path> entries) {
        List<URL> urls = new ArrayList<>();
        for (Path entry : entries) {
            try {
                urls.add(entry.toAbsolutePath().normalize().toUri().toURL());
            } catch (Exception ignored) {
                // Skip unusable classpath entries.
            }
        }
        return urls;
    }
}
