/*
 * JBoss, Home of Professional Open Source
 * Copyright 2012, Red Hat Middleware LLC, and individual contributors
 * by the @authors tag. See the copyright.txt in the distribution for a
 * full listing of individual contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jboss.arquillian.daemon.server;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufInputStream;
import io.netty.buffer.ByteBufOutputStream;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandler.Sharable;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundByteHandlerAdapter;
import io.netty.channel.ChannelInboundMessageHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.DelimiterBasedFrameDecoder;
import io.netty.handler.codec.Delimiters;
import io.netty.handler.codec.string.StringDecoder;

import java.io.InputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.io.UnsupportedEncodingException;
import java.net.InetSocketAddress;
import java.nio.charset.Charset;
import java.util.NoSuchElementException;
import java.util.StringTokenizer;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.jboss.arquillian.daemon.protocol.wire.WireProtocol;
import org.jboss.shrinkwrap.api.GenericArchive;
import org.jboss.shrinkwrap.api.importer.ZipImporter;

/**
 * Netty-based implementation of a {@link Server}; not thread-safe via the Java API (though invoking wire protocol
 * operations through its communication channels is). Responsible for handling I/O aspects of the server daemon.
 *
 * @author <a href="mailto:alr@jboss.org">Andrew Lee Rubinger</a>
 */
final class NettyServer extends ServerBase implements Server {

    private static final Logger log = Logger.getLogger(NettyServer.class.getName());
    private static final EofDecoder EOF_DECODER;

    static {
        try {
            EOF_DECODER = new EofDecoder();
        } catch (final UnsupportedEncodingException e) {
            throw new RuntimeException("Could not get encoding: " + WireProtocol.CHARSET, e);
        }
    }

    private static final String NAME_CHANNEL_HANDLER_EOF = "EOFHandler";
    private static final String NAME_CHANNEL_HANDLER_ACTION_CONTROLLER = "ActionControllerHandler";
    private static final String NAME_CHANNEL_HANDLER_STRING_DECODER = "StringDecoder";
    private static final String NAME_CHANNEL_HANDLER_FRAME_DECODER = "FrameDecoder";
    private static final String NAME_CHANNEL_HANDLER_DEPLOY_HANDLER = "DeployHandler";
    private static final String NAME_CHANNEL_HANDLER_COMMAND = "CommandHandler";
    private static final String[] NAME_CHANNEL_HANDLERS = {NAME_CHANNEL_HANDLER_EOF,
        NAME_CHANNEL_HANDLER_ACTION_CONTROLLER, NAME_CHANNEL_HANDLER_STRING_DECODER,
        NAME_CHANNEL_HANDLER_FRAME_DECODER, NAME_CHANNEL_HANDLER_DEPLOY_HANDLER, NAME_CHANNEL_HANDLER_COMMAND};

    private ServerBootstrap bootstrap;

    NettyServer(final InetSocketAddress bindAddress) {
        super(bindAddress);
    }

    /**
     * {@inheritDoc}
     *
     * @see org.jboss.arquillian.daemon.server.ServerBase#startInternal()
     */
    @Override
    protected void startInternal() throws ServerLifecycleException, IllegalStateException {

        // Set up Netty Boostrap
        final ServerBootstrap bootstrap = new ServerBootstrap().group(new NioEventLoopGroup(), new NioEventLoopGroup())
            .channel(NioServerSocketChannel.class).localAddress(this.getBindAddress())
            .childHandler(new ChannelInitializer<SocketChannel>() {
                @Override
                public void initChannel(final SocketChannel channel) throws Exception {
                    final ChannelPipeline pipeline = channel.pipeline();
                    NettyServer.this.resetPipeline(pipeline);
                }
            }).childOption(ChannelOption.TCP_NODELAY, true).childOption(ChannelOption.SO_KEEPALIVE, true);
        this.bootstrap = bootstrap;

        // Start 'er up
        final ChannelFuture openChannel;
        try {
            openChannel = bootstrap.bind().sync();
        } catch (final InterruptedException ie) {
            Thread.interrupted();
            throw new ServerLifecycleException("Interrupted while awaiting server start", ie);
        } catch (final RuntimeException re) {
            // Exception xlate
            throw new ServerLifecycleException("Encountered error in binding; could not start server.", re);
        }
        // Set bound address
        final InetSocketAddress boundAddress = ((InetSocketAddress) openChannel.channel().localAddress());
        this.setBoundAddress(boundAddress);
    }

    /**
     * {@inheritDoc}
     *
     * @see org.jboss.arquillian.daemon.server.ServerBase#stopInternal()
     */
    @Override
    protected void stopInternal() throws ServerLifecycleException, IllegalStateException {
        // Shutdown
        bootstrap.shutdown();
    }

    /**
     * Handler for all {@link String}-based commands to the server as specified in {@link WireProtocol}
     *
     * @author <a href="mailto:alr@jboss.org">Andrew Lee Rubinger</a>
     */
    private class StringCommandHandler extends ChannelInboundMessageHandlerAdapter<String> {

        /**
         * {@inheritDoc}
         *
         * @see io.netty.channel.ChannelInboundMessageHandlerAdapter#messageReceived(io.netty.channel.ChannelHandlerContext,
         * java.lang.Object)
         */
        @Override
        public void messageReceived(final ChannelHandlerContext ctx, final String message) throws Exception {
            if (log.isLoggable(Level.FINEST)) {
                log.finest("Got command: " + message);
            }

            // Get the buffer for the response
            final ByteBuf out = ctx.nextOutboundByteBuffer();

            // We want to catch any and all errors to to write out a proper response to the client
            try {

                // Reset the pipeline for the next call
                final ChannelPipeline pipeline = ctx.pipeline();
                NettyServer.this.resetPipeline(pipeline);

                // Stop
                if (WireProtocol.COMMAND_STOP.equals(message)) {

                    // Set the response to tell the client OK
                    NettyServer.sendResponse(ctx, out, WireProtocol.RESPONSE_OK_PREFIX + message);

                    // Now stop in another thread (after we send the response, else we might prematurely close the
                    // connection)
                    NettyServer.this.stopAsync();
                }
                // Undeployment
                else if (message.startsWith(WireProtocol.COMMAND_UNDEPLOY_PREFIX)) {

                    // Get out the deployment
                    final String deploymentName = message.substring(WireProtocol.COMMAND_UNDEPLOY_PREFIX.length())
                        .trim();
                    if (log.isLoggable(Level.FINEST)) {
                        log.finest("Requesting undeployment of: " + deploymentName);
                    }
                    final GenericArchive removedArchive = NettyServer.this.getDeployedArchives().remove(deploymentName);

                    // Check that we resulted in undeployment
                    if (removedArchive == null) {
                        if (log.isLoggable(Level.FINEST)) {
                            log.finest("Not current deployment: " + deploymentName);
                        }
                        final String response = WireProtocol.RESPONSE_ERROR_PREFIX + "Deployment " + deploymentName
                            + " could not be found in current deployments.";
                        NettyServer.sendResponse(ctx, out, response);
                        return;
                    }

                    // Tell the client OK
                    if (log.isLoggable(Level.FINEST)) {
                        log.finest("Undeployed: " + deploymentName);
                    }
                    final String response = WireProtocol.RESPONSE_OK_PREFIX + deploymentName;
                    NettyServer.sendResponse(ctx, out, response);
                }
                // Test
                else if (message.startsWith(WireProtocol.COMMAND_TEST_PREFIX)) {

                    // Parse out the arguments
                    final StringTokenizer tokenizer = new StringTokenizer(message);
                    tokenizer.nextToken();
                    tokenizer.nextToken();
                    final String archiveId = tokenizer.nextToken();
                    final String testClassName = tokenizer.nextToken();
                    final String methodName = tokenizer.nextToken();

                    // Execute the test and get the result
                    final Serializable testResult = NettyServer.this.executeTest(archiveId, testClassName, methodName);

                    ObjectOutputStream objectOutstream = null;
                    try {
                        // Write the test result
                        out.discardReadBytes();
                        objectOutstream = new ObjectOutputStream(new ByteBufOutputStream(out));
                        objectOutstream.writeObject(testResult);
                        objectOutstream.flush();
                        ctx.flush();
                    } finally {
                        if (objectOutstream != null) {
                            objectOutstream.close();
                        }
                    }
                }
                // Unsupported command
                else {
                    throw new UnsupportedOperationException("This server does not support command: " + message);
                }
            } catch (final Throwable t) {
                // Will be captured by any remote process which launched us and is piping in our output
                t.printStackTrace();
                NettyServer.sendResponse(ctx, out, WireProtocol.RESPONSE_ERROR_PREFIX
                    + "Caught unexpected error servicing request: " + t.getMessage());
            }
        }

        /**
         * Ignores all exceptions on messages received if the server is not running, else delegates to the super
         * implementation.
         *
         * @see io.netty.channel.ChannelStateHandlerAdapter#exceptionCaught(io.netty.channel.ChannelHandlerContext,
         * java.lang.Throwable)
         */
        @Override
        public void exceptionCaught(final ChannelHandlerContext ctx, final Throwable cause) throws Exception {
            // If the server isn't running, ignore everything
            if (!NettyServer.this.isRunning()) {
                // Ignore, but log if we've got a fine-grained enough level set
                if (log.isLoggable(Level.FINEST)) {
                    log.finest("Got exception while server is not running: " + cause.getMessage());
                }
                ctx.close();
            } else {
                super.exceptionCaught(ctx, cause);
            }
        }
    }

    /**
     * Handles deployment only
     *
     * @author <a href="mailto:alr@jboss.org">Andrew Lee Rubinger</a>
     */
    private final class DeployHandlerAdapter extends ChannelInboundByteHandlerAdapter {

        @Override
        public void inboundBufferUpdated(final ChannelHandlerContext ctx, final ByteBuf in) throws Exception {

            if (log.isLoggable(Level.FINEST)) {
                log.finest("Using the " + this.getClass().getSimpleName());
            }

            try {
                // Read in the archive using the isolated CL context of this domain
                final InputStream instream = new ByteBufInputStream(in);
                final GenericArchive archive = NettyServer.this.getShrinkwrapDomain().getArchiveFactory()
                    .create(ZipImporter.class).importFrom(instream).as(GenericArchive.class);
                instream.close();
                if (log.isLoggable(Level.FINEST)) {
                    log.finest("Got archive: " + archive.toString(true));
                }

                // Store the archive
                final String id = archive.getId();
                NettyServer.this.getDeployedArchives().put(id, archive);

                // Tell the client OK, and let it know the ID of the archive (so it may be undeployed)
                final ByteBuf out = ctx.nextOutboundByteBuffer();
                NettyServer.sendResponse(ctx, out, WireProtocol.RESPONSE_OK_PREFIX + WireProtocol.COMMAND_DEPLOY_PREFIX
                    + id);
            } finally {
                NettyServer.this.resetPipeline(ctx.pipeline());
            }
        }
    }

    /**
     * Determines the type of request and adjusts the pipeline to handle appropriately
     *
     * @author <a href="mailto:alr@jboss.org">Andrew Lee Rubinger</a>
     */
    private final class ActionControllerHandler extends ChannelInboundByteHandlerAdapter {

        @Override
        public void inboundBufferUpdated(final ChannelHandlerContext ctx, final ByteBuf in) throws Exception {

            // We require at least three bytes to determine the action taken
            if (in.readableBytes() < 3) {
                return;
            }

            // Get the pipeline so we can dynamically adjust it and fire events
            final ChannelPipeline pipeline = ctx.pipeline();

            // Pull out the magic header
            int readerIndex = in.readerIndex();
            final int magic1 = in.getUnsignedByte(readerIndex);
            final int magic2 = in.getUnsignedByte(readerIndex + 1);
            final int magic3 = in.getUnsignedByte(readerIndex + 2);

            // String-based Command?
            if (this.isStringCommand(magic1, magic2, magic3)) {
                // Write a line break into the buffer so we mark the frame
                in.writeBytes(Delimiters.lineDelimiter()[0]);
                // Adjust the pipeline such that we use the command handler
                pipeline.addLast(NAME_CHANNEL_HANDLER_FRAME_DECODER,
                    new DelimiterBasedFrameDecoder(2000, Delimiters.lineDelimiter()));
                pipeline.addLast(NAME_CHANNEL_HANDLER_STRING_DECODER,
                    new StringDecoder(Charset.forName(WireProtocol.CHARSET)));
                pipeline.addLast(NAME_CHANNEL_HANDLER_COMMAND, new StringCommandHandler());
                pipeline.remove(NAME_CHANNEL_HANDLER_ACTION_CONTROLLER);
                pipeline.remove(NAME_CHANNEL_HANDLER_EOF);
            }
            // Deploy command?
            else if (this.isDeployCommand(magic1, magic2, magic3)) {
                // Set the reader index so we strip out the command portion, leaving only the bytes containing the
                // archive (the frame decoder will strip off the EOF delimiter)
                in.readerIndex(in.readerIndex() + WireProtocol.COMMAND_DEPLOY_PREFIX.length());

                // Adjust the pipeline such that we use the deploy handler only
                pipeline.addLast(NAME_CHANNEL_HANDLER_DEPLOY_HANDLER, new DeployHandlerAdapter());
                pipeline.remove(NAME_CHANNEL_HANDLER_ACTION_CONTROLLER);
                pipeline.remove(NAME_CHANNEL_HANDLER_EOF);
            } else {
                // Unknown command/protocol
                NettyServer.sendResponse(ctx, ctx.nextOutboundByteBuffer(), WireProtocol.RESPONSE_ERROR_PREFIX
                    + "Unsupported Command");
                in.clear();
                ctx.close();
                return;
            }

            // Write the bytes to the next inbound buffer and re-fire so the updated handlers in the pipeline can have a
            // go at it
            final ByteBuf nextInboundByteBuffer = ctx.nextInboundByteBuffer();
            nextInboundByteBuffer.writeBytes(in);
            pipeline.fireInboundBufferUpdated();
        }

        /**
         * Returns to the client that some error was encountered
         *
         * @see io.netty.channel.ChannelStateHandlerAdapter#exceptionCaught(io.netty.channel.ChannelHandlerContext,
         * java.lang.Throwable)
         */
        @Override
        public void exceptionCaught(final ChannelHandlerContext ctx, final Throwable cause) throws Exception {
            NettyServer.sendResponse(ctx, ctx.nextOutboundByteBuffer(), cause.getMessage());
        }

        /**
         * Determines whether we have a {@link String}-based command
         */
        private boolean isStringCommand(final int magic1, final int magic2, final int magic3) {
            // First the bytes matches command prefix?
            return magic1 == WireProtocol.PREFIX_STRING_COMMAND.charAt(0)
                && magic2 == WireProtocol.PREFIX_STRING_COMMAND.charAt(1)
                && magic3 == WireProtocol.PREFIX_STRING_COMMAND.charAt(2);
        }

        /**
         * Determines whether we have a deployment command
         */
        private boolean isDeployCommand(final int magic1, final int magic2, final int magic3) {
            return magic1 == WireProtocol.COMMAND_DEPLOY_PREFIX.charAt(0)
                && magic2 == WireProtocol.COMMAND_DEPLOY_PREFIX.charAt(1)
                && magic3 == WireProtocol.COMMAND_DEPLOY_PREFIX.charAt(2);
        }
    }

    /**
     * {@link DelimiterBasedFrameDecoder} implementation to use the {@link WireProtocol#COMMAND_EOF_DELIMITER},
     * stripping it from the buffer. Is {@link Sharable} to allow this to be added/removed more than once.
     *
     * @author <a href="mailto:alr@jboss.org">Andrew Lee Rubinger</a>
     */
    @Sharable
    private static final class EofDecoder extends DelimiterBasedFrameDecoder {
        public EofDecoder() throws UnsupportedEncodingException {
            super(Integer.MAX_VALUE, true, Unpooled.wrappedBuffer(WireProtocol.COMMAND_EOF_DELIMITER
                .getBytes(WireProtocol.CHARSET)));
        }
    }

    private void resetPipeline(final ChannelPipeline pipeline) {
        // Remove all we've added
        for (final String handlerName : NAME_CHANNEL_HANDLERS) {
            try {
                pipeline.remove(handlerName);
            } catch (final NoSuchElementException ignore) {
            }
        }
        // Manually set up pipeline for action controller
        pipeline.addLast(NAME_CHANNEL_HANDLER_EOF, EOF_DECODER);
        pipeline.addLast(NAME_CHANNEL_HANDLER_ACTION_CONTROLLER, new ActionControllerHandler());
    }

    private static void sendResponse(final ChannelHandlerContext ctx, final ByteBuf out, final String response) {
        out.discardReadBytes();
        try {
            out.writeBytes(response.getBytes(WireProtocol.CHARSET));
            out.writeBytes(Delimiters.lineDelimiter()[0]);
        } catch (final UnsupportedEncodingException uee) {
            throw new RuntimeException("Unsupported encoding", uee);
        }
        ctx.flush();
    }
}
