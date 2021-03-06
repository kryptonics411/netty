/*
 * Copyright 2013 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package io.netty.channel;

import io.netty.buffer.BufUtil;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.MessageBuf;
import io.netty.util.Signal;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;

public final class ChannelHandlerUtil {

    public static final Signal ABORT = new Signal(ChannelHandlerUtil.class.getName() + ".ABORT");

    private static final InternalLogger logger = InternalLoggerFactory.getInstance(ChannelHandlerUtil.class);

    public static <T> void handleInboundBufferUpdated(
            ChannelHandlerContext ctx, SingleInboundMessageHandler<T> handler) throws Exception {
        MessageBuf<Object> in = ctx.inboundMessageBuffer();
        if (in.isEmpty() || !handler.beginMessageReceived(ctx)) {
            return;
        }

        MessageBuf<Object> out = ctx.nextInboundMessageBuffer();
        int oldOutSize = out.size();
        try {
            for (;;) {
                Object msg = in.poll();
                if (msg == null) {
                    break;
                }

                if (!handler.acceptInboundMessage(msg)) {
                    out.add(msg);
                    continue;
                }

                @SuppressWarnings("unchecked")
                T imsg = (T) msg;
                try {
                    handler.messageReceived(ctx, imsg);
                } finally {
                    BufUtil.release(imsg);
                }
            }
        } catch (Signal abort) {
            abort.expect(ABORT);
        } finally {
            if (oldOutSize != out.size()) {
                ctx.fireInboundBufferUpdated();
            }

            handler.endMessageReceived(ctx);
        }
    }

    public static <T> void handleFlush(
            ChannelHandlerContext ctx, ChannelPromise promise,
            SingleOutboundMessageHandler<T> handler) throws Exception {

        handleFlush(ctx, promise, true, handler);
    }

    public static <T> void handleFlush(
            ChannelHandlerContext ctx, ChannelPromise promise, boolean closeOnFailedFlush,
            SingleOutboundMessageHandler<T> handler) throws Exception {

        MessageBuf<Object> in = ctx.outboundMessageBuffer();
        MessageBuf<Object> out = null;

        final int inSize = in.size();
        if (inSize == 0) {
            ctx.flush(promise);
            return;
        }

        int processed = 0;
        try {
            handler.beginFlush(ctx);
            for (;;) {
                Object msg = in.poll();
                if (msg == null) {
                    break;
                }

                if (!handler.acceptOutboundMessage(msg)) {
                    if (out == null) {
                        out = ctx.nextOutboundMessageBuffer();
                    }
                    out.add(msg);
                    processed ++;
                    continue;
                }

                @SuppressWarnings("unchecked")
                T imsg = (T) msg;
                try {
                    handler.flush(ctx, imsg);
                    processed ++;
                } finally {
                    BufUtil.release(imsg);
                }
            }
        } catch (Throwable t) {
            PartialFlushException pfe;
            String msg = processed + " out of " + inSize + " message(s) flushed";
            if (t instanceof Signal) {
                Signal abort = (Signal) t;
                abort.expect(ABORT);
                pfe = new PartialFlushException("aborted: " + msg);
            } else {
                pfe = new PartialFlushException(msg, t);
            }
            fail(ctx, promise, closeOnFailedFlush, pfe);
        }

        try {
            handler.endFlush(ctx);
        } catch (Throwable t) {
            if (promise.isDone()) {
                logger.warn("endFlush() raised a masked exception due to failed flush().", t);
            } else {
                fail(ctx, promise, closeOnFailedFlush, t);
            }
        }

        if (!promise.isDone()) {
            ctx.flush(promise);
        }
    }

    private static void fail(
            ChannelHandlerContext ctx, ChannelPromise promise, boolean closeOnFailedFlush, Throwable cause) {
        promise.setFailure(cause);
        if (closeOnFailedFlush) {
            ctx.close();
        }
    }

    /**
     * Allocate a {@link ByteBuf} taking the {@link ChannelConfig#getDefaultHandlerByteBufType()}
     * setting into account.
     */
    public static ByteBuf allocate(ChannelHandlerContext ctx) {
        switch(ctx.channel().config().getDefaultHandlerByteBufType()) {
            case DIRECT:
                return ctx.alloc().directBuffer();
            case PREFER_DIRECT:
                return ctx.alloc().ioBuffer();
            case HEAP:
                return ctx.alloc().heapBuffer();
            default:
                throw new IllegalStateException();
        }
    }

    /**
     * Allocate a {@link ByteBuf} taking the {@link ChannelConfig#getDefaultHandlerByteBufType()}
     * setting into account.
     */
    public static ByteBuf allocate(ChannelHandlerContext ctx, int initialCapacity) {
        switch(ctx.channel().config().getDefaultHandlerByteBufType()) {
            case DIRECT:
                return ctx.alloc().directBuffer(initialCapacity);
            case PREFER_DIRECT:
                return ctx.alloc().ioBuffer(initialCapacity);
            case HEAP:
                return ctx.alloc().heapBuffer(initialCapacity);
            default:
                throw new IllegalStateException();
        }
    }

    /**
     * Allocate a {@link ByteBuf} taking the {@link ChannelConfig#getDefaultHandlerByteBufType()}
     * setting into account.
     */
    public static ByteBuf allocate(ChannelHandlerContext ctx, int initialCapacity, int maxCapacity) {
        switch(ctx.channel().config().getDefaultHandlerByteBufType()) {
            case DIRECT:
                return ctx.alloc().directBuffer(initialCapacity, maxCapacity);
            case PREFER_DIRECT:
                return ctx.alloc().ioBuffer(initialCapacity, maxCapacity);
            case HEAP:
                return ctx.alloc().heapBuffer(initialCapacity, maxCapacity);
            default:
                throw new IllegalStateException();
        }
    }
    private ChannelHandlerUtil() { }

    public interface SingleInboundMessageHandler<T> {
        /**
         * Returns {@code true} if and only if the specified message can be handled by this handler.
         *
         * @param msg the message
         */
        boolean acceptInboundMessage(Object msg) throws Exception;

        /**
         * Will get notified once {@link ChannelStateHandler#inboundBufferUpdated(ChannelHandlerContext)} was called.
         *
         * If this method returns {@code false} no further processing of the {@link MessageBuf}
         * will be done until the next call of {@link ChannelStateHandler#inboundBufferUpdated(ChannelHandlerContext)}.
         *
         * This will return {@code true} by default, and may get overriden by sub-classes for
         * special handling.
         *
         * @param ctx           the {@link ChannelHandlerContext} which this {@link ChannelHandler} belongs to
         */
        boolean beginMessageReceived(ChannelHandlerContext ctx) throws Exception;

        /**
         * Is called once a message was received.
         *
         * @param ctx           the {@link ChannelHandlerContext} which this {@link ChannelHandler} belongs to
         * @param msg           the message to handle
         */
        void messageReceived(ChannelHandlerContext ctx, T msg) throws Exception;

        /**
         * Is called when {@link #messageReceived(ChannelHandlerContext, Object)} returns.
         *
         * Super-classes may-override this for special handling.
         *
         * @param ctx           the {@link ChannelHandlerContext} which this {@link ChannelHandler} belongs to
         */
        void endMessageReceived(ChannelHandlerContext ctx) throws Exception;
    }

    public interface SingleOutboundMessageHandler<T> {
        /**
         * Returns {@code true} if and only if the specified message can be handled by this handler.
         *
         * @param msg the message
         */
        boolean acceptOutboundMessage(Object msg) throws Exception;

        /**
         * Will get notified once {@link ChannelOperationHandler#flush(ChannelHandlerContext, ChannelPromise)}
         * was called.
         *
         * @param ctx           the {@link ChannelHandlerContext} which this {@link ChannelHandler} belongs to
         */
        void beginFlush(ChannelHandlerContext ctx) throws Exception;

        /**
         * Is called once a message is being flushed.
         *
         * @param ctx           the {@link ChannelHandlerContext} which this {@link ChannelHandler} belongs to
         * @param msg           the message to handle
         */
        void flush(ChannelHandlerContext ctx, T msg) throws Exception;

        /**
         * Is called when {@link ChannelOperationHandler#flush(ChannelHandlerContext, ChannelPromise)} returns.
         *
         * Super-classes may-override this for special handling.
         *
         * @param ctx           the {@link ChannelHandlerContext} which this {@link ChannelHandler} belongs to
         */
        void endFlush(ChannelHandlerContext ctx) throws Exception;
    }
}
