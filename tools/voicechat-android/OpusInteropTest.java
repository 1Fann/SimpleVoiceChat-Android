import java.io.*;
import java.nio.file.*;
import java.util.Arrays;
import de.maxhenkel.opus4j.OpusEncoder;
import de.maxhenkel.opus4j.OpusDecoder;
import de.maxhenkel.voicechat.concentus.OpusApplication;

/** Exercises codecs in the supplied mod JAR without starting Minecraft. */
public final class OpusInteropTest {
    private static short[] samples(int frame) {
        short[] pcm = new short[960];
        for (int i = 0; i < pcm.length; i++) {
            double t = (frame * 960 + i) / 48000.0;
            pcm[i] = (short) (9000 * Math.sin(2 * Math.PI * 440 * t) + 3000 * Math.sin(2 * Math.PI * 173 * t));
        }
        return pcm;
    }

    public static void main(String[] args) throws Exception {
        Path directory = Paths.get(args[1]);
        if ("generate".equals(args[0])) {
            de.maxhenkel.voicechat.concentus.OpusEncoder javaEncoder = new de.maxhenkel.voicechat.concentus.OpusEncoder(48000, 1, OpusApplication.OPUS_APPLICATION_VOIP);
            javaEncoder.setUseInbandFEC(true);
            javaEncoder.setPacketLossPercent(5);
            try (OpusEncoder nativeEncoder = new OpusEncoder(48000, 1, OpusEncoder.Application.VOIP);
                 DataOutputStream javaOut = new DataOutputStream(Files.newOutputStream(directory.resolve("java-packets.bin")));
                 DataOutputStream nativeOut = new DataOutputStream(Files.newOutputStream(directory.resolve("native-packets.bin")))) {
                nativeEncoder.setMaxPayloadSize(1275);
                for (int frame = 0; frame < 100; frame++) {
                    short[] pcm = samples(frame);
                    byte[] buffer = new byte[1275];
                    int count = javaEncoder.encode(pcm, 0, 960, buffer, 0, buffer.length);
                    if (count <= 0) throw new AssertionError("Java encoding failed");
                    javaOut.writeInt(count);
                    javaOut.write(buffer, 0, count);
                    byte[] packet = nativeEncoder.encode(pcm);
                    if (packet.length == 0) throw new AssertionError("Native encoding failed");
                    nativeOut.writeInt(packet.length);
                    nativeOut.write(packet);
                }
                System.out.println("Generated Java and native Opus fixtures (" + nativeEncoder.getOpusVersion() + ")");
            }
        } else if ("check".equals(args[0])) {
            de.maxhenkel.voicechat.concentus.OpusDecoder javaDecoder = new de.maxhenkel.voicechat.concentus.OpusDecoder(48000, 1);
            try (OpusDecoder nativeDecoder = new OpusDecoder(48000, 1);
                 DataInputStream input = new DataInputStream(Files.newInputStream(directory.resolve("wasm-packets.bin")))) {
                nativeDecoder.setFrameSize(960);
                long nativeEnergy = 0, javaEnergy = 0;
                for (int frame = 0; frame < 100; frame++) {
                    int size = input.readInt();
                    if (size <= 0 || size > 1275) throw new AssertionError("Invalid packet size");
                    byte[] packet = new byte[size];
                    input.readFully(packet);
                    short[] decoded = nativeDecoder.decode(packet);
                    if (decoded.length != 960) throw new AssertionError("Wrong native frame size");
                    for (short s : decoded) nativeEnergy += (long) s * s;
                    short[] javaPcm = new short[960];
                    if (javaDecoder.decode(packet, 0, packet.length, javaPcm, 0, 960, false) != 960) throw new AssertionError("Wrong Java frame size");
                    for (short s : javaPcm) javaEnergy += (long) s * s;
                }
                if (input.read() != -1 || javaEnergy < 100000000 || nativeEnergy < 100000000) throw new AssertionError("Incomplete or silent result");
                if (nativeDecoder.decode((byte[]) null).length != 960) throw new AssertionError("Native PLC failed");
                if (javaDecoder.decode(null, 0, 0, new short[960], 0, 960, true) != 960) throw new AssertionError("Java PLC failed");
                javaDecoder.resetState();
                nativeDecoder.resetState();
                System.out.println("PASS source-built Opus -> Java/native JAR decoders: 100 frames, audio energy, PLC, reset");
            }
        } else {
            throw new IllegalArgumentException("Expected generate or check");
        }
    }
}
