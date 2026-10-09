package org.veltismc.net;

import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.network.CompressionEncoder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.util.Random;

/**
 * Event-loop blocking measurement for {@link OffThreadCompression}.
 *
 * <p>Off by default; run with {@code -PveltisBench
 * --tests org.veltismc.net.CompressionBench}, which is also the one-core,
 * two-gigabyte shape the feature exists for. The number that matters is the
 * first: how long the calling (event-loop) thread is inside compression. Inline
 * is the vanilla encoder; off-thread pays only for the copy and the enqueue.
 */
class CompressionBench {

    private static final int PACKETS = Integer.getInteger("veltis.bench.packets", 4000);
    private static final int SIZE = 64 * 1024;
    private static final int THRESHOLD = 256;

    @Test
    @EnabledIfSystemProperty(named = "veltis.bench", matches = "true")
    void eventLoopTime() throws Exception {
        byte[] payload = new byte[SIZE];
        new Random(7).nextBytes(payload);

        long inline = measureInline(payload);
        long offThread = measureOffThread(payload);

        System.out.println("== VeltisMC off-thread compression benchmark ==");
        System.out.println("packets=" + PACKETS + " payloadBytes=" + SIZE
            + " cores=" + Runtime.getRuntime().availableProcessors());
        System.out.printf("inline      caller-thread: %8.1f ms%n", inline / 1e6);
        System.out.printf("off-thread  caller-thread: %8.1f ms%n", offThread / 1e6);
        System.out.printf("caller-thread speedup: %.1fx%n", (double) inline / Math.max(offThread, 1));
    }

    private static long measureInline(byte[] payload) {
        EmbeddedChannel channel = new EmbeddedChannel(new CompressionEncoder(THRESHOLD));
        try {
            long start = System.nanoTime();
            for (int i = 0; i < PACKETS; i++) {
                channel.writeOutbound(Unpooled.wrappedBuffer(payload));
            }
            return System.nanoTime() - start;
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    private static long measureOffThread(byte[] payload) throws Exception {
        EmbeddedChannel channel = new EmbeddedChannel(
            new OffThreadCompression.Encoder(THRESHOLD, OffThreadCompression.executor()));
        try {
            long start = System.nanoTime();
            for (int i = 0; i < PACKETS; i++) {
                channel.writeOutbound(Unpooled.wrappedBuffer(payload));
            }
            return System.nanoTime() - start;
        } finally {
            long deadline = System.nanoTime() + 30_000_000_000L;
            while (channel.outboundMessages().size() < PACKETS && System.nanoTime() < deadline) {
                channel.runPendingTasks();
                Thread.sleep(1);
            }
            channel.finishAndReleaseAll();
        }
    }
}
