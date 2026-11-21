import java.lang.reflect.*;
import java.net.*;
import java.nio.file.*;

public final class LoaderTest {
    public static void main(String[] args) throws Exception {
        try (URLClassLoader loader = new URLClassLoader(new URL[]{Paths.get(args[0]).toUri().toURL()}, null)) {
            for (String name : new String[]{"rnnoise4j", "speex4j", "opus4j"}) {
                Class<?> type = Class.forName("de.maxhenkel." + name + ".LibraryLoader", true, loader);
                Method folder = type.getDeclaredMethod("getNativeFolderName");
                folder.setAccessible(true);
                Object actual = folder.invoke(null);
                if (!args[1].equals(actual)) throw new AssertionError(name + ": " + actual);
                Method resource = type.getDeclaredMethod("getResourcePath", String.class);
                resource.setAccessible(true);
                String path = (String) resource.invoke(null, "lib" + name);
                if (loader.getResource(path) == null) throw new AssertionError("Missing resource: " + path);
                System.out.println("PASS " + name + " -> " + path);
            }
            if (args.length > 2 && args[2].equals("smoke")) {
                Class<?> denoiser = Class.forName("de.maxhenkel.rnnoise4j.Denoiser", true, loader);
                Object dn = denoiser.getConstructor().newInstance();
                int frame = (Integer) denoiser.getMethod("getFrameSize").invoke(dn);
                if (frame != 480) throw new AssertionError("RNNoise frame size " + frame);
                float probability = (Float) denoiser.getMethod("denoiseInPlace", short[].class)
                        .invoke(dn, (Object) new short[960]);
                if (Float.isNaN(probability) || probability < 0 || probability > 1) throw new AssertionError("Invalid probability");
                denoiser.getMethod("close").invoke(dn);

                Class<?> agc = Class.forName("de.maxhenkel.speex4j.AutomaticGainControl", true, loader);
                Object gain = agc.getConstructor(int.class, int.class).newInstance(960, 48000);
                agc.getMethod("setTarget", int.class).invoke(gain, 10000);
                if (!Integer.valueOf(10000).equals(agc.getMethod("getTarget").invoke(gain))) throw new AssertionError("AGC target mismatch");
                agc.getMethod("agc", short[].class).invoke(gain, (Object) new short[960]);
                agc.getMethod("close").invoke(gain);
                testOpus(loader);
                System.out.println("PASS original Windows RNNoise + AGC + Opus native smoke test");
            }
        }
    }

    private static void testOpus(ClassLoader loader) throws Exception {
        Class<?> encoderType = Class.forName("de.maxhenkel.opus4j.OpusEncoder", true, loader);
        Class<?> decoderType = Class.forName("de.maxhenkel.opus4j.OpusDecoder", true, loader);
        Class<?> application = Class.forName("de.maxhenkel.opus4j.OpusEncoder$Application", true, loader);
        try (AutoCloseable encoder = (AutoCloseable) encoderType.getConstructor(int.class, int.class, application)
                     .newInstance(48000, 1, application.getField("VOIP").get(null));
             AutoCloseable decoder = (AutoCloseable) decoderType.getConstructor(int.class, int.class).newInstance(48000, 1)) {
            decoderType.getMethod("setFrameSize", int.class).invoke(decoder, 960);
            String version = (String) encoderType.getMethod("getOpusVersion").invoke(encoder);
            if (version == null || version.isEmpty() || !version.equals(decoderType.getMethod("getOpusVersion").invoke(decoder))) {
                throw new AssertionError("Invalid Opus version");
            }
            boolean audible = false;
            for (int frame = 0; frame < 5; frame++) {
                short[] signal = new short[960];
                for (int i = 0; i < signal.length; i++) {
                    signal[i] = (short) (10000 * Math.sin(2 * Math.PI * 440 * (frame * 960 + i) / 48000));
                }
                byte[] packet = (byte[]) encoderType.getMethod("encode", short[].class).invoke(encoder, (Object) signal);
                if (packet == null || packet.length == 0) throw new AssertionError("Empty Opus packet");
                short[] decoded = (short[]) decoderType.getMethod("decode", byte[].class).invoke(decoder, (Object) packet);
                if (decoded == null || decoded.length != 960) throw new AssertionError("Wrong decoded Opus frame length");
                for (short sample : decoded) if (Math.abs((int) sample) > 100) audible = true;
            }
            if (!audible) throw new AssertionError("Opus decoded only silence");
            System.out.println("PASS " + version + " mono 48 kHz encode/decode, 5 x 20 ms frames");
        }
    }
}
