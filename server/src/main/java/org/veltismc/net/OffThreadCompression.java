package org.veltismc.net;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.ChannelPromise;
import io.netty.util.ReferenceCountUtil;

import java.nio.channels.ClosedChannelException;
import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.Deflater;

/**
 * Opt-in offload of outbound packet compression from the Netty event loop to a
 * bounded worker pool.
 *
 * <h2>Why</h2>
 * Minecraft's {@code CompressionEncoder} is a {@code MessageToByteEncoder} on the
 * outbound path, so every packet at or above the network threshold is deflated
 * inline, on that connection's event-loop thread. On a small box — one core,
 * where the event loop also runs the transport's reads and writes — that work is
 * the difference between a tick being fast and the pipeline being. This class
 * moves the deflate off, without moving the framing.
 *
 * <h2>What is offloaded, and what is not</h2>
 * Only outbound <em>compression</em> of a packet. Framing (the uncompressed
 * length prefix), the decision to skip compression, decompression on the way in,
 * ciphers and the packet codec stay exactly where they are: the inbound
 * {@code CompressionDecoder} is bounded and reads a packet that has already been
 * counted by the time it runs, so leaving it on the event loop keeps the inbound
 * side free of any new reordering. That is the "only safe work" line.
 *
 * <h2>Ordering</h2>
 * Order is not a property of the executor; it is enforced here. Each handler
 * keeps its own FIFO and runs <b>at most one</b> task at a time, so a packet is
 * submitted only after the packet before it has been written downstream. The
 * executor parallelises across connections, never within one. A frame is thus
 * written in the order it was accepted, and the length prefix, written at
 * delivery, always agrees with the bytes.
 *
 * <h2>Backpressure</h2>
 * The pool is a fixed-size {@link ThreadPoolExecutor} with a bounded queue and a
 * {@link ThreadPoolExecutor.CallerRunsPolicy}. When the queue is full the caller
 * — the event loop — runs the task itself. That is the one policy that is both
 * bounded (the server cannot be made to retain an unbounded number of packets)
 * and correct (nothing is dropped and nothing is reordered; the connection just
 * applies its own backpressure, which is what a full send is). Other policies
 * either lose packets or grow until the heap does.
 *
 * <h2>Default</h2>
 * Off. The patch that installs it in {@code Connection.setupCompression} calls
 * {@link #installIfEnabled(ChannelPipeline, int)}, which returns {@code false}
 * unless {@value #ENABLED_PROPERTY} is {@code true}. Default-off is how a
 * behaviour change enters a running server: nothing about the wire format
 * changes, so it can be enabled per deployment and measured, and disabling it is
 * a restart with no code change.
 *
 * <h2>Ownership</h2>
 * {@link #write} takes ownership of the outbound {@link ByteBuf} and releases it
 * once the bytes are copied out; the downstream buffer is allocated at delivery
 * and its promise is the original promise. Nothing is leaked on a graceful
 * channel close, and {@code handlerRemoved} fails every queued promise and
 * releases every queued buffer.
 */
public final class OffThreadCompression {

    /** Set to {@code true} to enable. Anything else (unset included) is off. */
    public static final String ENABLED_PROPERTY = "veltis.network.offThreadCompression";

    /** Bounded pool queue capacity. */
    public static final String QUEUE_PROPERTY = "veltis.network.compressionQueue";

    /** Worker thread count. */
    public static final String THREADS_PROPERTY = "veltis.network.compressionThreads";

    /** The pipeline name of the encoder, coinciding with vanilla's. */
    public static final String HANDLER_NAME = "compress";

    private static final int DEFAULT_QUEUE = 1024;
    private static final int MAX_QUEUE = 1 << 20;
    private static final int MAX_THREADS = 16;

    private static volatile ThreadPoolExecutor executor;

    private OffThreadCompression() {
    }

    /** Whether the feature has been requested via a system property. */
    public static boolean isEnabled() {
        return Boolean.parseBoolean(System.getProperty(ENABLED_PROPERTY, "false"));
    }

    /**
     * Installs VeltisMC's encoder under the vanilla name when the feature is
     * enabled, or returns {@code false} immediately when it is not.
     *
     * <p>This is the reflection target the patched
     * {@code Connection.setupCompression} calls. It deliberately does not
     * reference Netty types in its signature beyond the pipeline so the patch
     * stays a one-line reflective call rather than a compile-time dependency on
     * the Veltis runtime.
     *
     * @return {@code true} once the off-thread encoder is in place
     */
    public static boolean installIfEnabled(ChannelPipeline pipeline, int threshold) {
        if (!isEnabled()) {
            return false;
        }
        ChannelHandler existing = pipeline.get(HANDLER_NAME);
        if (existing instanceof Encoder encoder) {
            encoder.setThreshold(threshold);
            return true;
        }
        if (existing != null) {
            // A vanilla encoder was added before this feature was observed; take
            // the name rather than stacking two compressors.
            pipeline.remove(existing);
        }
        pipeline.addAfter("prepender", HANDLER_NAME, new Encoder(threshold, executor()));
        return true;
    }

    static Executor executor() {
        ThreadPoolExecutor pool = executor;
        if (pool == null) {
            synchronized (OffThreadCompression.class) {
                pool = executor;
                if (pool == null) {
                    pool = buildExecutor();
                    executor = pool;
                }
            }
        }
        return pool;
    }

    private static ThreadPoolExecutor buildExecutor() {
        int threads = clamp(Integer.getInteger(THREADS_PROPERTY, defaultThreads()), 1, MAX_THREADS);
        int queue = clamp(Integer.getInteger(QUEUE_PROPERTY, DEFAULT_QUEUE), 16, MAX_QUEUE);
        AtomicInteger seq = new AtomicInteger();
        ThreadPoolExecutor pool = new ThreadPoolExecutor(
            threads, threads, 30L, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(queue),
            runnable -> {
                Thread thread = new Thread(runnable, "veltis-net-compress-" + seq.incrementAndGet());
                thread.setDaemon(true);
                return thread;
            },
            new ThreadPoolExecutor.CallerRunsPolicy());
        pool.allowCoreThreadTimeOut(true);
        return pool;
    }

    private static int defaultThreads() {
        return Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors() / 2));
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    /**
     * The outbound compressor. One per channel, so the {@link Deflater} and the
     * FIFO are the connection's own and no lock is needed; every field is
     * touched on the channel's event loop, including the delivery hop from the
     * pool.
     */
    static final class Encoder extends ChannelOutboundHandlerAdapter {

        private final Executor executor;
        private final Queue<Frame> queue = new ArrayDeque<>();

        private volatile int threshold;
        private boolean inFlight;
        private Frame runningFrame;
        private boolean flushRequested;
        private boolean removed;
        private Deflater deflater;
        private byte[] scratch;

        Encoder(int threshold, Executor executor) {
            this.threshold = threshold;
            this.executor = executor;
        }

        void setThreshold(int threshold) {
            this.threshold = threshold;
        }

        int pendingFrames() {
            return queue.size() + (inFlight ? 1 : 0);
        }

        @Override
        public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
            if (removed) {
                ReferenceCountUtil.release(msg);
                promise.tryFailure(new ClosedChannelException());
                return;
            }
            if (!(msg instanceof ByteBuf buf)) {
                ctx.write(msg, promise);
                return;
            }
            int readable = buf.readableBytes();
            if (readable < threshold) {
                // Small packet: vanilla writes the zero marker and the raw
                // bytes. There is nothing to run off, so this is done here and
                // only ordered against its siblings.
                ByteBuf out = ctx.alloc().buffer(readable + 5);
                writeVarInt(out, 0);
                out.writeBytes(buf);
                buf.release();
                queue.add(new Frame(out, promise));
            } else {
                byte[] input = new byte[readable];
                buf.readBytes(input);
                buf.release();
                queue.add(new Frame(input, promise));
            }
            pump(ctx);
        }

        @Override
        public void flush(ChannelHandlerContext ctx) {
            if (queue.isEmpty() && !inFlight) {
                ctx.flush();
            } else {
                // Deliver after the outstanding frames are written, or the
                // flush would pass the queued bytes by.
                flushRequested = true;
            }
        }

        @Override
        public void handlerRemoved(ChannelHandlerContext ctx) {
            removed = true;
            inFlight = false;
            Frame running = runningFrame;
            runningFrame = null;
            if (running != null) {
                running.fail(new ClosedChannelException());
            }
            Frame frame;
            while ((frame = queue.poll()) != null) {
                frame.fail(new ClosedChannelException());
            }
            Deflater current = deflater;
            if (current != null) {
                current.end();
                deflater = null;
            }
        }

        private void pump(ChannelHandlerContext ctx) {
            if (removed) {
                return;
            }
            while (!inFlight) {
                Frame head = queue.peek();
                if (head == null) {
                    break;
                }
                queue.poll();
                if (head.inline != null) {
                    ctx.write(head.inline, head.promise);
                } else {
                    inFlight = true;
                    runningFrame = head;
                    submit(ctx, head);
                    break;
                }
            }
            maybeFlush(ctx);
        }

        private void submit(ChannelHandlerContext ctx, Frame frame) {
            try {
                executor.execute(() -> {
                    byte[] compressed;
                    try {
                        compressed = compress(frame.input, frame.input.length);
                    } catch (Throwable t) {
                        postCompletion(ctx, frame, null, t);
                        return;
                    }
                    postCompletion(ctx, frame, compressed, null);
                });
            } catch (RejectedExecutionException e) {
                postCompletion(ctx, frame, null, e);
            }
        }

        private void postCompletion(ChannelHandlerContext ctx, Frame frame, byte[] compressed, Throwable failure) {
            try {
                ctx.executor().execute(() -> complete(frame, compressed, failure, ctx));
            } catch (Throwable ignored) {
                // The channel's executor is gone; the promise must still settle.
                inFlight = false;
                runningFrame = null;
                frame.fail(failure != null ? failure : new ClosedChannelException());
            }
        }

        private void complete(Frame frame, byte[] compressed, Throwable failure, ChannelHandlerContext ctx) {
            inFlight = false;
            runningFrame = null;
            if (removed || !ctx.channel().isActive()) {
                frame.fail(new ClosedChannelException());
                return;
            }
            if (failure != null) {
                frame.fail(failure);
            } else {
                ByteBuf out = ctx.alloc().buffer(compressed.length + 5);
                writeVarInt(out, frame.input.length);
                out.writeBytes(compressed);
                ctx.write(out, frame.promise);
            }
            pump(ctx);
        }

        private void maybeFlush(ChannelHandlerContext ctx) {
            if (flushRequested && !inFlight && queue.isEmpty()) {
                flushRequested = false;
                ctx.flush();
            }
        }

        private byte[] compress(byte[] input, int length) {
            Deflater current = deflater;
            if (current == null) {
                current = new Deflater();
                deflater = current;
            }
            current.setInput(input, 0, length);
            current.finish();
            if (scratch == null) {
                scratch = new byte[8192];
            }
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(length);
            while (!current.finished()) {
                int written = current.deflate(scratch);
                if (written > 0) {
                    out.write(scratch, 0, written);
                }
            }
            current.reset();
            return out.toByteArray();
        }

        private static void writeVarInt(ByteBuf output, int value) {
            while ((value & -128) != 0) {
                output.writeByte(value & 127 | 128);
                value >>>= 7;
            }
            output.writeByte(value);
        }
    }

    /** One outbound packet: an already-built buffer, or bytes to deflate. */
    private static final class Frame {
        final ByteBuf inline;
        final byte[] input;
        final ChannelPromise promise;

        Frame(ByteBuf inline, ChannelPromise promise) {
            this.inline = inline;
            this.input = null;
            this.promise = promise;
        }

        Frame(byte[] input, ChannelPromise promise) {
            this.inline = null;
            this.input = input;
            this.promise = promise;
        }

        void fail(Throwable cause) {
            if (inline != null) {
                ReferenceCountUtil.release(inline);
            }
            promise.tryFailure(cause);
        }
    }
}
