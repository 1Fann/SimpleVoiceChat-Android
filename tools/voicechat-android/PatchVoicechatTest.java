import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.jar.*;
import java.util.stream.Stream;
import org.objectweb.asm.*;

/** Run with one argument: an existing or new directory for disposable test fixtures. */
public final class PatchVoicechatTest {
    private static final String HELPER = "cn/fan/voicechat/android/NativePlatform";
    private static final String RNNOISE = "de/maxhenkel/rnnoise4j/LibraryLoader.class";
    private static final String SPEEX = "de/maxhenkel/speex4j/LibraryLoader.class";
    private static final String OPUS = "de/maxhenkel/opus4j/LibraryLoader.class";
    private static final String CLIENT = "de/maxhenkel/voicechat/natives/ClientNativeManager.class";
    private static Path root, classes, natives, sources;
    private static int passed;

    public static void main(String[] args) throws Exception {
        if (args.length < 1) throw new IllegalArgumentException("Usage: PatchVoicechatTest test-directory [original.jar ...]");
        Path parent = Paths.get(args[0]).toAbsolutePath().normalize();
        Files.createDirectories(parent);
        root = Files.createTempDirectory(parent, "patch-voicechat-test-");
        classes = root.resolve("helper");
        natives = root.resolve("natives");
        sources = root.resolve("sources");
        write(classes.resolve(HELPER + ".class"), helper());
        write(natives.resolve("librnnoise4j.so"), bytes("rnnoise native fixture"));
        write(natives.resolve("libspeex4j.so"), bytes("speex native fixture"));
        write(natives.resolve("libopus4j.so"), bytes("opus native fixture"));
        write(sources.resolve("rnnoise/COPYING"), bytes("rnnoise license fixture"));
        write(sources.resolve("speexdsp/COPYING"), bytes("speex license fixture"));
        write(sources.resolve("opus/COPYING"), bytes("opus license fixture"));
        write(sources.resolve("sources.json"), bytes("{}"));

        expectFailure(() -> PatchVoicechat.main(new String[0]), "Usage:");
        expectFailure(() -> PatchVoicechat.main(new String[6]), "Usage:");
        passed++;
        testSuccess();
        testFailedPatchCanRetry();
        testMissingInputs();
        testUnsupportedLoaders();
        testUnsupportedClientManager();
        testRejectedArchives();
        testExistingTargets();
        testConcurrentPublication();
        for (int i = 1; i < args.length; i++) testConnectionWarnings(Paths.get(args[i]));
        System.out.println("PASS " + passed + " patch-tool test groups; fixtures: " + root);
    }

    private static void testSuccess() throws Exception {
        Path input = archive("success-input.jar", original());
        byte[] originalBytes = Files.readAllBytes(input);
        Path output = root.resolve("success.jar");
        patch(input, output);
        check(Arrays.equals(originalBytes, Files.readAllBytes(input)), "Input JAR changed");
        try (JarFile before = new JarFile(input.toFile()); JarFile after = new JarFile(output.toFile())) {
            check(after.size() == before.size() + 8, "Unexpected added entry count");
            Enumeration<JarEntry> entries = before.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                JarEntry actual = after.getJarEntry(entry.getName());
                check(actual != null, "Missing original entry " + entry.getName());
                check(entry.getTime() == actual.getTime(), "Changed original timestamp " + entry.getName());
                if (!Arrays.asList(RNNOISE, SPEEX, OPUS, CLIENT).contains(entry.getName())) {
                    check(Arrays.equals(read(before, entry), read(after, actual)), "Changed original content " + entry.getName());
                }
            }
            checkEntry(after, HELPER + ".class", classes.resolve(HELPER + ".class"));
            checkEntry(after, "natives/android-aarch64/librnnoise4j.so", natives.resolve("librnnoise4j.so"));
            checkEntry(after, "natives/android-aarch64/libspeex4j.so", natives.resolve("libspeex4j.so"));
            checkEntry(after, "META-INF/voicechat-android/RNNoise-COPYING.txt", sources.resolve("rnnoise/COPYING"));
            checkEntry(after, "META-INF/voicechat-android/SpeexDSP-COPYING.txt", sources.resolve("speexdsp/COPYING"));
            checkEntry(after, "natives/android-aarch64/libopus4j.so", natives.resolve("libopus4j.so"));
            checkEntry(after, "META-INF/voicechat-android/Opus-COPYING.txt", sources.resolve("opus/COPYING"));
            checkEntry(after, "META-INF/voicechat-android/sources.json", sources.resolve("sources.json"));
        }
        String previous = System.getProperty("patch.test.android");
        try (URLClassLoader loader = new URLClassLoader(new URL[]{output.toUri().toURL()}, null)) {
            for (String name : Arrays.asList(RNNOISE, SPEEX, OPUS)) {
                Class<?> type = loader.loadClass(name.substring(0, name.length() - 6).replace('/', '.'));
                System.setProperty("patch.test.android", "false");
                check("desktop-original".equals(type.getMethod("getNativeFolderName").invoke(null)), "Desktop loader changed");
                System.setProperty("patch.test.android", "true");
                check("android-aarch64".equals(type.getMethod("getNativeFolderName").invoke(null)), "Android loader not patched");
            }
        } finally {
            if (previous == null) System.clearProperty("patch.test.android");
            else System.setProperty("patch.test.android", previous);
        }
        noTemporaryOutput(output);
        passed++;
    }

    private static void testFailedPatchCanRetry() throws Exception {
        Map<String, byte[]> entries = original();
        entries.put(SPEEX, loader(SPEEX, "unexpectedMethod", Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC));
        Path input = archive("retry-input.jar", entries);
        Path output = root.resolve("retry-output.jar");
        reject(input, output, "Unsupported native loader structure");
        archive("retry-input.jar", original());
        patch(input, output);
        check(Files.isRegularFile(output), "Retry did not publish the output");
        noTemporaryOutput(output);
        passed++;
    }

    private static void testMissingInputs() throws Exception {
        Path input = archive("missing-inputs.jar", original());
        Path output = root.resolve("missing-inputs-output.jar");
        reject(root.resolve("does-not-exist.jar"), output, "Required file");
        for (Path source : Arrays.asList(sources.resolve("sources.json"), sources.resolve("opus/COPYING"), natives.resolve("libopus4j.so"))) {
            byte[] contents = Files.readAllBytes(source);
            Files.delete(source);
            try { reject(input, output, "Required file"); }
            finally { write(source, contents); }
        }
        for (String name : Arrays.asList(RNNOISE, SPEEX, OPUS, CLIENT)) {
            Map<String, byte[]> entries = original();
            entries.remove(name);
            reject(archive("missing-loader.jar", entries), output, "Missing required native loaders");
        }
        passed++;
    }

    private static void testUnsupportedLoaders() throws Exception {
        for (int access : new int[]{Opcodes.ACC_PUBLIC, Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_NATIVE}) {
            Map<String, byte[]> entries = original();
            entries.put(RNNOISE, loader(RNNOISE, "getNativeFolderName", access));
            reject(archive("unsupported-" + access + ".jar", entries), root.resolve("unsupported-" + access + "-output.jar"),
                    "Expected a static loader method with bytecode");
        }
        passed++;
    }

    private static void testUnsupportedClientManager() throws Exception {
        Map<String, byte[]> entries = original();
        entries.put(CLIENT, clientManagerFixture(1));
        reject(archive("unsupported-client.jar", entries), root.resolve("unsupported-client-output.jar"),
                "Unsupported client connection warning structure");
        passed++;
    }

    private static void testRejectedArchives() throws Exception {
        int index = 0;
        for (String name : Arrays.asList("META-INF/test.sf", "META-INF/TEST.RSA", "META-INF/SIG-test",
                HELPER + ".class", "natives/android-aarch64/librnnoise4j.so",
                "natives/android-aarch64/libopus4j.so", "META-INF/voicechat-android/Opus-COPYING.txt",
                "META-INF/voicechat-android/sources.json")) {
            Map<String, byte[]> entries = original();
            entries.put(name, bytes("existing resource"));
            reject(archive("rejected-" + index + ".jar", entries), root.resolve("rejected-" + index + "-output.jar"),
                    name.startsWith("META-INF/test") || name.startsWith("META-INF/TEST") || name.startsWith("META-INF/SIG-")
                            ? "Signed JAR" : "Already patched JAR");
            index++;
        }
        reject(root.resolve("success.jar"), root.resolve("repatched.jar"), "Already patched JAR");
        Map<String, byte[]> entries = original();
        entries.put("duplicate-a", bytes("one"));
        entries.put("duplicate-b", bytes("two"));
        Path duplicate = archive("duplicate.jar", entries);
        byte[] data = Files.readAllBytes(duplicate);
        byte[] from = bytes("duplicate-b"), to = bytes("duplicate-a");
        int replaced = 0;
        for (int offset = 0; offset <= data.length - from.length; offset++) {
            if (Arrays.equals(data, offset, offset + from.length, from, 0, from.length)) {
                System.arraycopy(to, 0, data, offset, to.length);
                replaced++;
            }
        }
        check(replaced == 2, "Duplicate ZIP fixture construction failed");
        Files.write(duplicate, data);
        reject(duplicate, root.resolve("duplicate-output.jar"), "Duplicate entry");
        passed++;
    }

    private static void testExistingTargets() throws Exception {
        Path input = archive("existing-input.jar", original());
        byte[] inputBytes = Files.readAllBytes(input);
        expectFailure(() -> patch(input, input), "Input and output must be different");
        check(Arrays.equals(inputBytes, Files.readAllBytes(input)), "In-place rejection changed original");
        Path output = root.resolve("already-exists.jar");
        byte[] marker = bytes("Do not overwrite this file");
        Files.write(output, marker);
        expectFailure(() -> patch(input, output), "Output already exists");
        check(Arrays.equals(marker, Files.readAllBytes(output)), "Existing output changed");
        noTemporaryOutput(output);
        passed++;
    }

    private static void testConcurrentPublication() throws Exception {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        byte[] payload = new byte[16 * 1024 * 1024];
        new Random(12345).nextBytes(payload);
        entries.put("large-payload.bin", payload);
        entries.putAll(original());
        Path input = archive("publication-race-input.jar", entries);
        Path output = root.resolve("publication-race.jar");
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread worker = new Thread(() -> {
            try { patch(input, output); }
            catch (Throwable error) { failure.set(error); }
        }, "patch-test-publication");
        worker.start();
        long deadline = System.nanoTime() + 10_000_000_000L;
        boolean observed = false;
        while (worker.isAlive() && System.nanoTime() < deadline) {
            if (hasTemporaryOutput(output)) { observed = true; break; }
            Thread.sleep(1);
        }
        byte[] marker = bytes("Competing publisher owns this output");
        try {
            check(observed, "Did not observe temporary output before publication");
            Files.write(output, marker, StandardOpenOption.CREATE_NEW);
        } finally {
            worker.join(30_000);
        }
        check(!worker.isAlive(), "Patcher failed to complete");
        check(failure.get() instanceof FileAlreadyExistsException, "Expected publication conflict, got " + failure.get());
        check(Arrays.equals(marker, Files.readAllBytes(output)), "Publication replaced a concurrent target");
        noTemporaryOutput(output);
        passed++;
    }

    private static Map<String, byte[]> original() {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("META-INF/MANIFEST.MF", bytes("Manifest-Version: 1.0\r\n\r\n"));
        entries.put("unchanged.txt", bytes("original payload"));
        entries.put(RNNOISE, loader(RNNOISE, "getNativeFolderName", Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC));
        entries.put(SPEEX, loader(SPEEX, "getNativeFolderName", Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC));
        entries.put(OPUS, loader(OPUS, "getNativeFolderName", Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC));
        entries.put(CLIENT, clientManagerFixture(2));
        return entries;
    }


    private static byte[] clientManagerFixture(int checks) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, CLIENT.substring(0, CLIENT.length() - 6), null, "java/lang/Object", null);
        MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "onConnecting", "()V", null, null);
        method.visitCode();
        for (int i = 0; i < checks; i++) {
            method.visitMethodInsn(Opcodes.INVOKESTATIC, "de/maxhenkel/voicechat/natives/LameManager", "isFailed", "()Z", false);
            method.visitInsn(Opcodes.POP);
        }
        method.visitInsn(Opcodes.RETURN);
        method.visitMaxs(1, 0);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    // Execute the real upstream onConnecting bytecode with isolated substitutes
    // for Minecraft/logging/native initialization. This tests the shipped branch
    // logic without launching a game or pretending Android JNI ran on Windows.
    private static void testConnectionWarnings(Path originalJar) throws Exception {
        byte[] original;
        try (JarFile jar = new JarFile(originalJar.toFile())) {
            JarEntry entry = jar.getJarEntry(CLIENT);
            check(entry != null, "Original JAR lacks ClientNativeManager");
            original = read(jar, entry);
        }
        java.lang.reflect.Method patch = PatchVoicechat.class.getDeclaredMethod("patchClientManager", byte[].class);
        patch.setAccessible(true);
        byte[] patched = (byte[]) patch.invoke(null, (Object) original);
        Path support = Files.createTempDirectory(root, "warning-fixtures-");
        Map<String, String> fixtures = new LinkedHashMap<>();
        fixtures.put("de/maxhenkel/voicechat/intercompatibility/CrossSideManager.java",
                "package de.maxhenkel.voicechat.intercompatibility; public class CrossSideManager { public static CrossSideManager get() { return new CrossSideManager(); } public boolean useNatives() { return true; } }");
        fixtures.put("de/maxhenkel/voicechat/logging/VoicechatLogger.java",
                "package de.maxhenkel.voicechat.logging; public interface VoicechatLogger { void info(String message, Object... args); }");
        fixtures.put("de/maxhenkel/voicechat/Voicechat.java",
                "package de.maxhenkel.voicechat; public class Voicechat { public static de.maxhenkel.voicechat.logging.VoicechatLogger LOGGER; }");
        fixtures.put("de/maxhenkel/voicechat/voice/client/ChatUtils.java",
                "package de.maxhenkel.voicechat.voice.client; public class ChatUtils { public static String message; public static int calls; public static void sendModErrorMessage(String key, String value) { message=value; calls++; } }");
        for (String name : Arrays.asList("OpusManager", "RNNoiseManager", "SpeexManager", "LameManager")) {
            fixtures.put("de/maxhenkel/voicechat/natives/" + name + ".java", "package de.maxhenkel.voicechat.natives; public class " + name
                    + " { public static boolean failed; public static int checks; public static boolean isFailed() { checks++; return failed; } public static String getFailedMessage() { return \"fixture failure\"; } }");
        }
        List<String> compilerArgs = new ArrayList<>(Arrays.asList("--release", "8", "-d", support.toString()));
        for (Map.Entry<String, String> entry : fixtures.entrySet()) {
            Path path = support.resolve(entry.getKey());
            write(path, bytes(entry.getValue()));
            compilerArgs.add(path.toString());
        }
        check(javax.tools.ToolProvider.getSystemJavaCompiler().run(null, null, null, compilerArgs.toArray(new String[0])) == 0,
                "Warning fixture compilation failed");
        write(support.resolve(CLIENT), patched);
        String previous = System.getProperty("patch.test.android");
        try (URLClassLoader loader = new URLClassLoader(new URL[]{support.toUri().toURL(), classes.toUri().toURL()}, null)) {
            Class<?> client = loader.loadClass("de.maxhenkel.voicechat.natives.ClientNativeManager");
            Class<?> chat = loader.loadClass("de.maxhenkel.voicechat.voice.client.ChatUtils");
            Class<?> lame = loader.loadClass("de.maxhenkel.voicechat.natives.LameManager");
            for (boolean android : new boolean[]{true, false}) {
                resetWarningFixtures(loader, chat);
                System.setProperty("patch.test.android", Boolean.toString(android));
                lame.getField("failed").setBoolean(null, true);
                client.getMethod("onConnecting").invoke(null);
                if (android) {
                    check(chat.getField("calls").getInt(null) == 0, "Android LAME-only failure still warns");
                    check(lame.getField("checks").getInt(null) == 0, "Android connection initialized optional LAME");
                } else {
                    check(chat.getField("calls").getInt(null) == 1 && ((String) chat.getField("message").get(null)).contains("LAME:"),
                            "Desktop LAME warning was suppressed");
                    check(lame.getField("checks").getInt(null) > 0, "Desktop LAME check was skipped");
                }
            }
            System.setProperty("patch.test.android", "true");
            for (String failed : Arrays.asList("Opus", "RNNoise", "Speex")) {
                resetWarningFixtures(loader, chat);
                lame.getField("failed").setBoolean(null, true);
                loader.loadClass("de.maxhenkel.voicechat.natives." + failed + "Manager").getField("failed").setBoolean(null, true);
                client.getMethod("onConnecting").invoke(null);
                String message = (String) chat.getField("message").get(null);
                check(chat.getField("calls").getInt(null) == 1 && message.contains(failed + ":") && !message.contains("LAME:"),
                        "Android voice failure warning was changed: " + failed);
                check(lame.getField("checks").getInt(null) == 0, "Voice failure caused an Android LAME check");
            }
        } finally {
            if (previous == null) System.clearProperty("patch.test.android"); else System.setProperty("patch.test.android", previous);
        }
        passed++;
        System.out.println("PASS real connection warnings: " + originalJar);
    }

    private static void resetWarningFixtures(ClassLoader loader, Class<?> chat) throws Exception {
        for (String name : Arrays.asList("OpusManager", "RNNoiseManager", "SpeexManager", "LameManager")) {
            Class<?> type = loader.loadClass("de.maxhenkel.voicechat.natives." + name);
            type.getField("failed").setBoolean(null, false);
            type.getField("checks").setInt(null, 0);
        }
        chat.getField("calls").setInt(null, 0);
        chat.getField("message").set(null, null);
    }

    private static byte[] loader(String path, String methodName, int access) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, path.substring(0, path.length() - 6), null, "java/lang/Object", null);
        MethodVisitor method = writer.visitMethod(access, methodName, "()Ljava/lang/String;", null, null);
        if ((access & (Opcodes.ACC_NATIVE | Opcodes.ACC_ABSTRACT)) == 0) {
            method.visitCode();
            method.visitLdcInsn("desktop-original");
            method.visitInsn(Opcodes.ARETURN);
            method.visitMaxs(1, (access & Opcodes.ACC_STATIC) != 0 ? 0 : 1);
        }
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] helper() {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, HELPER, null, "java/lang/Object", null);
        MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "isAndroidArm64", "()Z", null, null);
        method.visitCode();
        method.visitLdcInsn("patch.test.android");
        method.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Boolean", "getBoolean", "(Ljava/lang/String;)Z", false);
        method.visitInsn(Opcodes.IRETURN);
        method.visitMaxs(1, 0);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static Path archive(String name, Map<String, byte[]> entries) throws IOException {
        Path path = root.resolve(name);
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(path))) {
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                JarEntry item = new JarEntry(entry.getKey());
                item.setTime(1_700_000_000_000L);
                out.putNextEntry(item);
                out.write(entry.getValue());
                out.closeEntry();
            }
        }
        return path;
    }

    private static void patch(Path input, Path output) throws Exception {
        PatchVoicechat.main(new String[]{input.toString(), output.toString(), classes.toString(), natives.toString(), sources.toString()});
    }

    private static void reject(Path input, Path output, String message) throws Exception {
        expectFailure(() -> patch(input, output), message);
        check(!Files.exists(output), "Failed patch left a final output: " + output);
        noTemporaryOutput(output);
    }

    private static void expectFailure(CheckedRunnable action, String message) throws Exception {
        try { action.run(); }
        catch (Exception error) {
            check(error.getMessage() != null && error.getMessage().contains(message), "Unexpected failure: " + error);
            return;
        }
        throw new AssertionError("Expected failure containing: " + message);
    }

    private static boolean hasTemporaryOutput(Path output) throws IOException {
        try (Stream<Path> paths = Files.list(output.getParent())) {
            return paths.anyMatch(path -> path.getFileName().toString().startsWith(output.getFileName() + ".")
                    && path.getFileName().toString().endsWith(".part"));
        }
    }

    private static void noTemporaryOutput(Path output) throws IOException {
        check(!hasTemporaryOutput(output), "Temporary output was not cleaned up: " + output);
    }

    private static byte[] read(JarFile jar, JarEntry entry) throws IOException {
        try (InputStream in = jar.getInputStream(entry)) { return in.readAllBytes(); }
    }

    private static void checkEntry(JarFile jar, String name, Path source) throws IOException {
        JarEntry entry = jar.getJarEntry(name);
        check(entry != null && Arrays.equals(read(jar, entry), Files.readAllBytes(source)), "Incorrect added entry " + name);
    }

    private static byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }
    private static void write(Path path, byte[] data) throws IOException {
        Files.createDirectories(path.getParent());
        Files.write(path, data);
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    @FunctionalInterface private interface CheckedRunnable { void run() throws Exception; }
}
