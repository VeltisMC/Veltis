package org.veltismc.net;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.network.CompressionDecoder;
import net.minecraft.network.CompressionEncoder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.veltismc.net.OffThreadCompression.Encoder;

import java.util.Random;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.zip.Inflater;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link OffThreadCompression}: exact vanilla framing, interop with
 * the vanilla decoder, ordering, and cleanup on removal.
 */
class OffThreadCompressionTest {

    private static final int THRESHOLD = 256;
    private static final Executor DIRECT = Runnable::run;

    private static EmbeddedChannel channel() {
        return new EmbeddedChannel(new Encoder(THRESHOLD, DIRECT));
    }

    private static void drain(EmbeddedChannel channel, int expected) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (channel.outboundMessages().size() < expected && System.nanoTime() < deadline) {
            channel.runPendingTasks();
            Thread.sleep(1);
        }
        channel.runPendingTasks();
        assertEquals(expected, channel.outboundMessages().size(), "outbound frames");
    }

    @Test
    void smallPacketIsVanillaFramed() throws Exception {
        byte[] payload = new byte[100];
        new Random(1).nextBytes(payload);

        EmbeddedChannel ours = channel();
        EmbeddedChannel vanilla = new EmbeddedChannel(new CompressionEncoder(THRESHOLD));
        try {
            assertTrue(ours.writeOutbound(Unpooled.wrappedBuffer(payload.clone())));
            assertTrue(vanilla.writeOutbound(Unpooled.wrappedBuffer(payload.clone())));
            drain(ours, 1);

            ByteBuf oursOut = ours.readOutbound();
            ByteBuf vanillaOut = vanilla.readOutbound();
            assertNotNull(oursOut);
            assertNotNull(vanillaOut);
            try {
                assertEquals(ByteBufUtil.hexDump(vanillaOut), ByteBufUtil.hexDump(oursOut));
            } finally {
                oursOut.release();
                vanillaOut.release();
            }
        } finally {
            ours.finishAndReleaseAll();
            vanilla.finishAndReleaseAll();
        }
    }

    @Test
    void largePacketMatchesVanillaBytes() throws Exception {
        byte[] payload = new byte[8192];
        new Random(2).nextBytes(payload);

        EmbeddedChannel ours = channel();
        EmbeddedChannel vanilla = new EmbeddedChannel(new CompressionEncoder(THRESHOLD));
        try {
            ours.writeOutbound(Unpooled.wrappedBuffer(payload.clone()));
            vanilla.writeOutbound(Unpooled.wrappedBuffer(payload.clone()));
            drain(ours, 1);

            ByteBuf oursOut = ours.readOutbound();
            ByteBuf vanillaOut = vanilla.readOutbound();
            try {
                assertEquals(ByteBufUtil.hexDump(vanillaOut), ByteBufUtil.hexDump(oursOut));
            } finally {
                oursOut.release();
                vanillaOut.release();
            }
        } finally {
            ours.finishAndReleaseAll();
            vanilla.finishAndReleaseAll();
        }
    }

    @Test
    void decodesWithVanillaDecoder() throws Exception {
        byte[] payload = new byte[4096];
        new Random(3).nextBytes(payload);

        EmbeddedChannel encoder = channel();
        try {
            encoder.writeOutbound(Unpooled.wrappedBuffer(payload.clone()));
            drain(encoder, 1);
            ByteBuf frame = encoder.readOutbound();
            assertNotNull(frame);

            EmbeddedChannel decoder = new EmbeddedChannel(new CompressionDecoder(THRESHOLD, false));
            try {
                assertTrue(decoder.writeInbound(frame));
                ByteBuf decoded = decoder.readInbound();
                assertNotNull(decoded);
                try {
                    assertEquals(ByteBufUtil.hexDump(Unpooled.wrappedBuffer(payload)), ByteBufUtil.hexDump(decoded));
                } finally {
                    decoded.release();
                }
            } finally {
                decoder.finishAndReleaseAll();
            }
        } finally {
            encoder.finishAndReleaseAll();
        }
    }

    @Test
    @Timeout(30)
    void orderingIsPreservedAcrossManyPackets() throws Exception {
        int frames = 64;
        byte[][] payloads = new byte[frames][];
        Random random = new Random(4);
        EmbeddedChannel channel = channel();
        try {
            for (int i = 0; i < frames; i++) {
                byte[] payload = new byte[THRESHOLD + 1024];
                random.nextBytes(payload);
                payload[0] = (byte) i;
                payloads[i] = payload;
                channel.writeOutbound(Unpooled.wrappedBuffer(payload.clone()));
            }
            drain(channel, frames);

            for (int i = 0; i < frames; i++) {
                ByteBuf frame = channel.readOutbound();
                assertNotNull(frame, "frame " + i);
                try {
                    assertEquals(payloads[i].length, readVarInt(frame), "declared length of frame " + i);
                    byte[] expected = payloads[i];
                    byte[] actual = new byte[expected.length];
                    inflate(frame, actual);
                    assertTrue(expected.length > 0);
                    assertEquals(expected[0], actual[0], "payload order at frame " + i);
                } finally {
                    frame.release();
                }
            }
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    @Timeout(30)
    void closeFailsQueuedPromisesAndReleasesBuffers() throws Exception {
        // An executor that never runs keeps the queue observable: the first
        // write is in flight, the rest sit in the FIFO.
        Executor blocked = command -> {
        };
        EmbeddedChannel channel = new EmbeddedChannel(new Encoder(THRESHOLD, blocked));
        try {
            ChannelPromise first = channel.newPromise();
            channel.pipeline().write(Unpooled.wrappedBuffer(new byte[4096]), first);
            ChannelPromise second = channel.newPromise();
            channel.pipeline().write(Unpooled.wrappedBuffer(new byte[4096]), second);

            Encoder encoder = channel.pipeline().get(Encoder.class);
            assertNotNull(encoder);
            assertEquals(2, encoder.pendingFrames(), "one in flight, one queued");

            channel.pipeline().remove(encoder);

            assertTrue(first.isDone());
            assertTrue(second.isDone());
            assertTrue(!first.isSuccess());
            assertTrue(!second.isSuccess());
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void installIfEnabledRespectsTheFlag() {
        String previous = System.getProperty(OffThreadCompression.ENABLED_PROPERTY);
        EmbeddedChannel channel = new EmbeddedChannel();
        channel.pipeline().addLast("prepender", new ChannelOutboundHandlerAdapter());
        try {
            System.setProperty(OffThreadCompression.ENABLED_PROPERTY, "false");
            assertTrue(!OffThreadCompression.installIfEnabled(channel.pipeline(), THRESHOLD));
            assertNull(channel.pipeline().get(OffThreadCompression.HANDLER_NAME));

            System.setProperty(OffThreadCompression.ENABLED_PROPERTY, "true");
            assertTrue(OffThreadCompression.installIfEnabled(channel.pipeline(), THRESHOLD));
            assertInstanceOf(Encoder.class, channel.pipeline().get(OffThreadCompression.HANDLER_NAME));

            // A second call must reuse the handler, not add another.
            assertTrue(OffThreadCompression.installIfEnabled(channel.pipeline(), THRESHOLD + 1));
            assertNotNull(channel.pipeline().get(OffThreadCompression.HANDLER_NAME));
        } finally {
            if (previous == null) {
                System.clearProperty(OffThreadCompression.ENABLED_PROPERTY);
            } else {
                System.setProperty(OffThreadCompression.ENABLED_PROPERTY, previous);
            }
            channel.finishAndReleaseAll();
        }
    }

    private static int readVarInt(ByteBuf buf) {
        int value = 0;
        int position = 0;
        byte in;
        do {
            in = buf.readByte();
            value |= (in & 127) << position++ * 7;
        } while ((in & 128) != 0);
        return value;
    }

    private static void inflate(ByteBuf compressed, byte[] target) throws Exception {
        byte[] input = new byte[compressed.readableBytes()];
        compressed.readBytes(input);
        Inflater inflater = new Inflater();
        try {
            inflater.setInput(input);
            int offset = 0;
            while (!inflater.finished() && offset < target.length) {
                int written = inflater.inflate(target, offset, target.length - offset);
                if (written == 0) {
                    if (inflater.needsInput() || inflater.needsDictionary()) {
                        break;
                    }
                }
                offset += written;
            }
            assertEquals(target.length, offset, "inflated length");
        } finally {
            inflater.end();
        }
    }
}
