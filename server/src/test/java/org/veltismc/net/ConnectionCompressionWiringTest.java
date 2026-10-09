package org.veltismc.net;

import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.network.CompressionDecoder;
import net.minecraft.network.CompressionEncoder;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import org.junit.jupiter.api.Test;
import org.veltismc.net.OffThreadCompression.Encoder;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * End-to-end check of the Shulker patch: the <em>patched</em>
 * {@code net.minecraft.network.Connection.setupCompression} must call VeltisMC's
 * installer reflectively and leave the connection with exactly one outbound
 * compressor — VeltisMC's when the feature is on, vanilla's when it is off.
 *
 * <p>This is the only test that exercises the reflection line in the patch
 * without a live client. It loads the patched {@code Connection} from the dev
 * classes, builds a real pipeline around it, and drives
 * {@code setupCompression} exactly as the login path does.
 */
class ConnectionCompressionWiringTest {

    private static EmbeddedChannel wiredPipeline() {
        Connection connection = new Connection(PacketFlow.SERVERBOUND);
        EmbeddedChannel channel = new EmbeddedChannel(connection);
        assertNotNull(connection.channel, "channelActive should have bound the channel");
        // setupCompression inserts the decoder after "splitter" and the encoder
        // after "prepender"; both names must exist exactly as they do live.
        channel.pipeline().addLast("splitter", new ChannelInboundHandlerAdapter());
        channel.pipeline().addLast("prepender", new ChannelOutboundHandlerAdapter());
        return channel;
    }

    @Test
    void featureOffInstallsVanillaEncoder() {
        withEnabledFlag("false", () -> {
            EmbeddedChannel channel = wiredPipeline();
            try {
                Connection connection = channel.pipeline().get(Connection.class);
                connection.setupCompression(256, false);

                assertInstanceOf(CompressionDecoder.class, channel.pipeline().get("decompress"));
                assertInstanceOf(CompressionEncoder.class, channel.pipeline().get("compress"));
            } finally {
                channel.finishAndReleaseAll();
            }
        });
    }

    @Test
    void featureOnReplacesVanillaEncoder() {
        withEnabledFlag("true", () -> {
            EmbeddedChannel channel = wiredPipeline();
            try {
                Connection connection = channel.pipeline().get(Connection.class);
                connection.setupCompression(256, false);

                assertInstanceOf(CompressionDecoder.class, channel.pipeline().get("decompress"));
                assertInstanceOf(Encoder.class, channel.pipeline().get("compress"));

                // Reconfiguring compression must update the existing handler, not
                // stack a second one or fall back to vanilla.
                connection.setupCompression(512, false);
                assertInstanceOf(Encoder.class, channel.pipeline().get("compress"));

                // Disabling compression must clear VeltisMC's handler too: the
                // vanilla "instanceof CompressionEncoder" check alone would leave
                // it installed.
                connection.setupCompression(-1, false);
                assertNull(channel.pipeline().get("compress"));
                assertNull(channel.pipeline().get("decompress"));
            } finally {
                channel.finishAndReleaseAll();
            }
        });
    }

    private static void withEnabledFlag(String value, ThrowingRunnable body) {
        String previous = System.getProperty(OffThreadCompression.ENABLED_PROPERTY);
        try {
            System.setProperty(OffThreadCompression.ENABLED_PROPERTY, value);
            body.run();
        } catch (Exception e) {
            throw new AssertionError(e);
        } finally {
            if (previous == null) {
                System.clearProperty(OffThreadCompression.ENABLED_PROPERTY);
            } else {
                System.setProperty(OffThreadCompression.ENABLED_PROPERTY, previous);
            }
        }
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }
}
