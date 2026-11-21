import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.jar.*;
import org.objectweb.asm.*;

public final class PatchVoicechat {
    private static final String HELPER = "cn/fan/voicechat/android/NativePlatform";
    private static final String CLIENT = "de/maxhenkel/voicechat/natives/ClientNativeManager";
    private static final String LAME = "de/maxhenkel/voicechat/natives/LameManager";
    private static final String LAME_CHECK = "voicechatAndroidShouldReportLame";
    private static final Set<String> LOADERS = new HashSet<>(Arrays.asList(
            "de/maxhenkel/rnnoise4j/LibraryLoader.class",
            "de/maxhenkel/speex4j/LibraryLoader.class",
            "de/maxhenkel/opus4j/LibraryLoader.class"));

    public static void main(String[] args) throws Exception {
        if (args.length != 5) {
            throw new IllegalArgumentException("Usage: PatchVoicechat input.jar output.jar helper stripped sources");
        }
        Path input = Paths.get(args[0]).toAbsolutePath().normalize();
        Path output = Paths.get(args[1]).toAbsolutePath().normalize();
        Path classes = Paths.get(args[2]);
        Path natives = Paths.get(args[3]);
        Path sources = Paths.get(args[4]);
        requireFile(input);
        if (input.equals(output) || (Files.exists(output) && Files.isSameFile(input, output))) {
            throw new IOException("Input and output must be different files");
        }
        if (Files.exists(output, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Output already exists: " + output);
        }

        Map<String, Path> additions = new LinkedHashMap<>();
        additions.put(HELPER + ".class", classes.resolve(HELPER + ".class"));
        for (String name : Arrays.asList("librnnoise4j.so", "libspeex4j.so", "libopus4j.so")) {
            additions.put("natives/android-aarch64/" + name, natives.resolve(name));
        }
        additions.put("META-INF/voicechat-android/RNNoise-COPYING.txt", sources.resolve("rnnoise/COPYING"));
        additions.put("META-INF/voicechat-android/SpeexDSP-COPYING.txt", sources.resolve("speexdsp/COPYING"));
        additions.put("META-INF/voicechat-android/Opus-COPYING.txt", sources.resolve("opus/COPYING"));
        additions.put("META-INF/voicechat-android/sources.json", sources.resolve("sources.json"));
        for (Path path : additions.values()) requireFile(path);

        Path temporary = null;
        try {
            try (JarFile original = new JarFile(input.toFile())) {
                validateOriginal(original, additions.keySet());
                Files.createDirectories(output.getParent());
                temporary = Files.createTempFile(output.getParent(), output.getFileName() + ".", ".part");
                try (JarOutputStream patched = new JarOutputStream(Files.newOutputStream(temporary))) {
                    Enumeration<JarEntry> entries = original.entries();
                    while (entries.hasMoreElements()) {
                        JarEntry entry = entries.nextElement();
                        String name = entry.getName();
                        byte[] bytes;
                        try (InputStream in = original.getInputStream(entry)) { bytes = in.readAllBytes(); }
                        if (LOADERS.contains(name)) bytes = patchLoader(bytes);
                        if (name.equals(CLIENT + ".class")) bytes = patchClientManager(bytes);
                        add(patched, name, bytes, entry.getTime());
                    }
                    for (Map.Entry<String, Path> addition : additions.entrySet()) {
                        add(patched, addition.getKey(), Files.readAllBytes(addition.getValue()), 0);
                    }
                }
            }
            // No REPLACE_EXISTING: a competing process may have created the target.
            // ATOMIC_MOVE is deliberately omitted because it may replace existing targets.
            Files.move(temporary, output);
            temporary = null;
        } finally {
            if (temporary != null) Files.deleteIfExists(temporary);
        }
        System.out.println("Patched " + output + " (3 loaders + Android connection warning, Android arm64 RNNoise + SpeexDSP + Opus)");
    }

    private static void requireFile(Path path) throws IOException {
        if (!Files.isRegularFile(path)) throw new IOException("Required file is missing or not a regular file: " + path);
    }

    private static void validateOriginal(JarFile original, Set<String> additions) throws IOException {
        Set<String> seen = new HashSet<>();
        Set<String> missing = new HashSet<>(LOADERS);
        missing.add(CLIENT + ".class");
        Enumeration<JarEntry> entries = original.entries();
        while (entries.hasMoreElements()) {
            String name = entries.nextElement().getName();
            if (!seen.add(name)) throw new IOException("Duplicate entry: " + name);
            if (name.matches("(?i)META-INF/.*\\.(SF|RSA|DSA|EC)") || name.matches("(?i)META-INF/SIG-[^/]+")) {
                throw new IOException("Signed JAR cannot be patched: " + name);
            }
            if (additions.contains(name)) {
                throw new IOException("Already patched JAR or conflicting Android resource: " + name);
            }
            missing.remove(name);
        }
        if (!missing.isEmpty()) throw new IOException("Missing required native loaders or client manager: " + missing);
    }

    private static byte[] patchLoader(byte[] bytes) throws IOException {
        ClassReader reader = new ClassReader(bytes);
        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
        int[] count = {0, 0};
        ClassVisitor visitor = new ClassVisitor(Opcodes.ASM9, writer) {
            @Override public MethodVisitor visitMethod(int access, String name, String descriptor,
                                                      String signature, String[] exceptions) {
                MethodVisitor target = super.visitMethod(access, name, descriptor, signature, exceptions);
                if (!name.equals("getNativeFolderName") || !descriptor.equals("()Ljava/lang/String;")) return target;
                if ((access & Opcodes.ACC_STATIC) == 0 || (access & (Opcodes.ACC_NATIVE | Opcodes.ACC_ABSTRACT)) != 0) {
                    throw new IllegalStateException("Expected a static loader method with bytecode");
                }
                count[0]++;
                return new MethodVisitor(Opcodes.ASM9, target) {
                    @Override public void visitCode() {
                        count[1]++;
                        super.visitCode();
                        Label original = new Label();
                        super.visitMethodInsn(Opcodes.INVOKESTATIC, HELPER, "isAndroidArm64", "()Z", false);
                        super.visitJumpInsn(Opcodes.IFEQ, original);
                        super.visitLdcInsn("android-aarch64");
                        super.visitInsn(Opcodes.ARETURN);
                        super.visitLabel(original);
                        super.visitFrame(Opcodes.F_SAME, 0, null, 0, null);
                    }
                };
            }
        };
        reader.accept(visitor, 0);
        if (count[0] != 1 || count[1] != 1) throw new IOException("Unsupported native loader structure");
        return writer.toByteArray();
    }


    // Only connection-time notices skip optional MP3 support on Android. The
    // LAME manager and every recording/capability check retain their original behavior.
    private static byte[] patchClientManager(byte[] bytes) throws IOException {
        ClassReader reader = new ClassReader(bytes);
        if (!reader.getClassName().equals(CLIENT)) throw new IOException("Unexpected client manager class");
        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
        int[] count = {0, 0};
        reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
            @Override public MethodVisitor visitMethod(int access, String name, String descriptor,
                                                      String signature, String[] exceptions) {
                if (name.equals(LAME_CHECK)) throw new IllegalStateException("Client manager is already patched");
                MethodVisitor target = super.visitMethod(access, name, descriptor, signature, exceptions);
                if (!name.equals("onConnecting") || !descriptor.equals("()V")) return target;
                if ((access & Opcodes.ACC_STATIC) == 0 || (access & (Opcodes.ACC_NATIVE | Opcodes.ACC_ABSTRACT)) != 0) {
                    throw new IllegalStateException("Expected a static connection callback with bytecode");
                }
                count[0]++;
                return new MethodVisitor(Opcodes.ASM9, target) {
                    @Override public void visitMethodInsn(int opcode, String owner, String name, String descriptor, boolean isInterface) {
                        if (opcode == Opcodes.INVOKESTATIC && owner.equals(LAME) && name.equals("isFailed")
                                && descriptor.equals("()Z") && !isInterface) {
                            count[1]++;
                            super.visitMethodInsn(opcode, CLIENT, LAME_CHECK, descriptor, false);
                        } else {
                            super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
                        }
                    }
                };
            }

            @Override public void visitEnd() {
                MethodVisitor method = super.visitMethod(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC,
                        LAME_CHECK, "()Z", null, null);
                method.visitCode();
                Label desktop = new Label();
                method.visitMethodInsn(Opcodes.INVOKESTATIC, HELPER, "isAndroidArm64", "()Z", false);
                method.visitJumpInsn(Opcodes.IFEQ, desktop);
                method.visitInsn(Opcodes.ICONST_0);
                method.visitInsn(Opcodes.IRETURN);
                method.visitLabel(desktop);
                method.visitFrame(Opcodes.F_SAME, 0, null, 0, null);
                method.visitMethodInsn(Opcodes.INVOKESTATIC, LAME, "isFailed", "()Z", false);
                method.visitInsn(Opcodes.IRETURN);
                method.visitMaxs(1, 0);
                method.visitEnd();
                super.visitEnd();
            }
        }, 0);
        if (count[0] != 1 || count[1] != 2) throw new IOException("Unsupported client connection warning structure");
        return writer.toByteArray();
    }

    private static void add(JarOutputStream out, String name, byte[] bytes, long time) throws IOException {
        JarEntry entry = new JarEntry(name);
        entry.setTime(Math.max(0, time));
        out.putNextEntry(entry);
        out.write(bytes);
        out.closeEntry();
    }
}
