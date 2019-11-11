/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.zookeeper;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.ssl.SslHandler;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.GenericFutureListener;
import org.apache.zookeeper.ClientCnxn.EndOfStreamException;
import org.apache.zookeeper.ClientCnxn.Packet;
import org.apache.zookeeper.client.ZKClientConfig;
import org.apache.zookeeper.common.ClientX509Util;
import org.apache.zookeeper.common.NettyUtils;
import org.apache.zookeeper.common.X509Util;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.apache.zookeeper.common.X509Exception.SSLContextException;

/**
 * ClientCnxnSocketNetty implements ClientCnxnSocket abstract methods.
 * It's responsible for connecting to server, reading/writing network traffic and
 * being a layer between network data and higher level packets.
 *
 * 基于Netty的socket 通信框架
 *
 */
public class ClientCnxnSocketNetty extends ClientCnxnSocket {
    private static final Logger LOG = LoggerFactory.getLogger(ClientCnxnSocketNetty.class);

    private final EventLoopGroup eventLoopGroup;
    private Channel channel;
    private CountDownLatch firstConnect;
    private ChannelFuture connectFuture;
    private final Lock connectLock = new ReentrantLock();
    private final AtomicBoolean disconnected = new AtomicBoolean();
    private final AtomicBoolean needSasl = new AtomicBoolean();
    private final Semaphore waitSasl = new Semaphore(0);

    private static final AtomicReference<ByteBufAllocator> TEST_ALLOCATOR =
            new AtomicReference<>(null);

    ClientCnxnSocketNetty(ZKClientConfig clientConfig) throws IOException {
        this.clientConfig = clientConfig;
        //客户端只有一个输出socket,因此event loop group只需要一个线程.
        eventLoopGroup = NettyUtils.newNioOrEpollEventLoopGroup(1);
        // 初始化一下packet的长度
        initProperties();
    }

    /**
     * lifecycles diagram:
     * <p/>
     * loop:
     * - try:
     * - - !isConnected()
     * - - - connect()
     * - - doTransport()
     * - catch:
     * - - cleanup()
     * close()
     * <p/>
     * Other non-lifecycle methods are in jeopardy getting a null channel
     * when calling in concurrency. We must handle it.
     */

    @Override
    boolean isConnected() {
        // Assuming that isConnected() is only used to initiate connection,
        // not used by some other connection status judgement.
        /**
         * 假设isConnected()仅用于初始化连接,而不是用于其他连接状态判断.
         */
        connectLock.lock();
        try {
            return channel != null || connectFuture != null;
        } finally {
            connectLock.unlock();
        }
    }

    private Bootstrap configureBootstrapAllocator(Bootstrap bootstrap) {
        ByteBufAllocator testAllocator = TEST_ALLOCATOR.get();
        if (testAllocator != null) {
            return bootstrap.option(ChannelOption.ALLOCATOR, testAllocator);
        } else {
            return bootstrap;
        }
    }

    @Override
    void connect(InetSocketAddress addr) throws IOException {
        firstConnect = new CountDownLatch(1);

        /**
         * 读源码的时候,每一行代码都认为是理所当然的,这种思想是不对的.
         * 如果让我去设计者东西,我该怎么解决呢?
         * 比如,这里为什么要加锁呢?还有其他地方调用connect方法吗?
         * 加锁是为了保护谁?
         * 这里为什么调用firstConnect = new CountDownLatch(1);
         *
         */

        Bootstrap bootstrap = new Bootstrap()
                .group(eventLoopGroup)
                .channel(NettyUtils.nioOrEpollSocketChannel())
                .option(ChannelOption.SO_LINGER, -1)
                .option(ChannelOption.TCP_NODELAY, true)
                .handler(new ZKClientPipelineFactory(addr.getHostString(), addr.getPort()));

        bootstrap = configureBootstrapAllocator(bootstrap);
        bootstrap.validate();

        connectLock.lock();
        try {
            // 这一行code,是连接到服务端,没错了....
            connectFuture = bootstrap.connect(addr);
            connectFuture.addListener(new ChannelFutureListener() {
                @Override
                public void operationComplete(ChannelFuture channelFuture) throws Exception {
                    // this lock guarantees that channel won't be assigned after cleanup().
                    boolean connected = false;
                    connectLock.lock();
                    try {
                        if (!channelFuture.isSuccess()) {
                            LOG.info("future isn't success, cause:", channelFuture.cause());
                            return;
                        } else if (connectFuture == null) {
                            LOG.info("connect attempt cancelled");
                            // If the connect attempt was cancelled but succeeded
                            // anyway, make sure to close the channel, otherwise
                            // we may leak a file descriptor.
                            channelFuture.channel().close();
                            return;
                        }
                        // setup channel, variables, connection, etc.
                        channel = channelFuture.channel();

                        disconnected.set(false);
                        initialized = false;
                        lenBuffer.clear();
                        incomingBuffer = lenBuffer;

                        sendThread.primeConnection();
                        updateNow();
                        updateLastSendAndHeard();

                        if (sendThread.tunnelAuthInProgress()) {
                            waitSasl.drainPermits();
                            needSasl.set(true);
                            sendPrimePacket();
                        } else {
                            needSasl.set(false);
                        }
                        connected = true;
                    } finally {
                        connectFuture = null;
                        connectLock.unlock();
                        if (connected) {
                            LOG.info("channel is connected: {}", channelFuture.channel());
                        }
                        // need to wake on connect success or failure to avoid
                        // timing out ClientCnxn.SendThread which may be
                        // blocked waiting for first connect in doTransport().
                        wakeupCnxn();
                        firstConnect.countDown();
                    }
                }
            });
        } finally {
            connectLock.unlock();
        }
    }

    @Override
    void cleanup() {
        connectLock.lock();
        try {
            if (connectFuture != null) {
                connectFuture.cancel(false);
                connectFuture = null;
            }
            if (channel != null) {
                channel.close().syncUninterruptibly();
                channel = null;
            }
        } finally {
            connectLock.unlock();
        }
        Iterator<Packet> iter = outgoingQueue.iterator();
        while (iter.hasNext()) {
            Packet p = iter.next();
            if (p == WakeupPacket.getInstance()) {
                iter.remove();
            }
        }
    }

    @Override
    void close() {
        eventLoopGroup.shutdownGracefully();
    }

    @Override
    void saslCompleted() {
        needSasl.set(false);
        waitSasl.release();
    }

    @Override
    void connectionPrimed() {
    }

    @Override
    void packetAdded() {
        // NO-OP. Adding a packet will already wake up a netty connection
        // so we don't need to add a dummy packet to the queue to trigger
        // a wake-up.
        // 添加一个包将会唤醒一个网络连接,所以我们不需要向队列添加一个虚拟包来触发唤醒.
    }

    @Override
    void onClosing() {
        firstConnect.countDown();
        wakeupCnxn();
        LOG.info("channel is told closing");
    }

    private void wakeupCnxn() {
        if (needSasl.get()) {
            waitSasl.release();
        }
        outgoingQueue.add(WakeupPacket.getInstance());
    }

    /**
     *  Do transportation work:
     * - read packets into incomingBuffer. 将数据包读入incomingBuffer
     * - write outgoing queue packets.    写入传出队列数据包
     * - update relevant timestamp.       更新相关的时间戳
     * @param waitTimeOut timeout in blocking wait. Unit in MilliSecond.
     * @param pendingQueue These are the packets that have been sent and
     *                     are waiting for a response.
     * @param cnxn
     * @throws IOException
     * @throws InterruptedException
     */
    @Override
    void doTransport(int waitTimeOut,
                     List<Packet> pendingQueue,
                     ClientCnxn cnxn)
            throws IOException, InterruptedException {
        try {
            if (!firstConnect.await(waitTimeOut, TimeUnit.MILLISECONDS)) {
                return;
            }
            Packet head = null;
            if (needSasl.get()) {
                if (!waitSasl.tryAcquire(waitTimeOut, TimeUnit.MILLISECONDS)) {
                    return;
                }
            } else {
                head = outgoingQueue.poll(waitTimeOut, TimeUnit.MILLISECONDS);
            }
            // check if being waken up on closing.
            /**
             * 如果{@link sendThread}的线程状态已经不是处于活跃状态了,则将请求包再次放入{@link outgoingQueue}
             */
            if (!sendThread.getZkState().isAlive()) {
                // adding back the packet to notify of failure in conLossPacket().
                addBack(head);
                return;
            }
            // channel disconnection happened
            /**
             * channel 已经关闭
             */
            if (disconnected.get()) {
                addBack(head);
                throw new EndOfStreamException("channel for sessionid 0x"
                        + Long.toHexString(sessionId)
                        + " is lost");
            }
            if (head != null) {
                doWrite(pendingQueue, head, cnxn);
            }
        } finally {
            updateNow();
        }
    }

    private void addBack(Packet head) {
        if (head != null && head != WakeupPacket.getInstance()) {
            outgoingQueue.addFirst(head);
        }
    }

    /**
     * Sends a packet to the remote peer and flushes the channel.
     * @param p packet to send.
     * @return a ChannelFuture that will complete when the write operation
     *         succeeds or fails.
     */
    private ChannelFuture sendPktAndFlush(Packet p) {
        return sendPkt(p, true);
    }

    /**
     * Sends a packet to the remote peer but does not flush() the channel.
     * @param p packet to send.
     * @return a ChannelFuture that will complete when the write operation
     *         succeeds or fails.
     */
    private ChannelFuture sendPktOnly(Packet p) {
        return sendPkt(p, false);
    }

    // Use a single listener instance to reduce GC
    private final GenericFutureListener<Future<Void>> onSendPktDoneListener = f -> {
        if (f.isSuccess()) {
            sentCount.getAndIncrement();
        }
    };

    private ChannelFuture sendPkt(Packet p, boolean doFlush) {
        // 假设包将被成功发送出去.因为如果失败,通道将关闭并清理队列 Because if it fails,
        // the channel will close and clean up queues.
        p.createBB();
        updateLastSend();

        // sendPkt的调用链
//       org.apache.zookeeper.ClientCnxnSocketNetty.sendPkt(ClientCnxnSocketNetty.java:376)
//       org.apache.zookeeper.ClientCnxnSocketNetty.sendPktOnly(ClientCnxnSocketNetty.java:360)
//       org.apache.zookeeper.ClientCnxnSocketNetty.doWrite(ClientCnxnSocketNetty.java:421)
//       org.apache.zookeeper.ClientCnxnSocketNetty.doTransport(ClientCnxnSocketNetty.java:330)
//       org.apache.zookeeper.ClientCnxn$SendThread.run(ClientCnxn.java:1298)


        final ByteBuf writeBuffer = Unpooled.wrappedBuffer(p.bb);
        final ChannelFuture result = doFlush
                ? channel.writeAndFlush(writeBuffer)
                : channel.write(writeBuffer);
        result.addListener(onSendPktDoneListener);
        return result;
    }

    private void sendPrimePacket() {
        // assuming the first packet is the priming packet.
        sendPktAndFlush(outgoingQueue.remove());
    }

    /**
     * doWrite handles writing the packets from outgoingQueue via network to server.
     */
    private void doWrite(List<Packet> pendingQueue, Packet p, ClientCnxn cnxn) {
        updateNow();
        boolean anyPacketsSent = false;
        while (true) {
            if (p != WakeupPacket.getInstance()) {
                if ((p.requestHeader != null) &&
                        (p.requestHeader.getType() != ZooDefs.OpCode.ping) &&
                        (p.requestHeader.getType() != ZooDefs.OpCode.auth)) {
                    p.requestHeader.setXid(cnxn.getXid());
                    /**
                     * doWrite的调用链是
                     * ClientCnxn
                     * sendThread.run()
                     * -> clientCnxnSocket.doTransport(ClientCnxnSocketNetty)
                     * ->doWrite
                     *
                     * pendingQueue参数一路传递过来,实际上是ClientCnxn的成员变量
                     * 而pendingQueue的实现{@link java.util.LinkedList}不是线程安全的.
                     * 又因为这里要对pendingQueue进行写操作,
                     * 所以需要对其加锁.
                     *
                     */
                    synchronized (pendingQueue) {
                        pendingQueue.add(p);
                    }
                }
                sendPktOnly(p);
                anyPacketsSent = true;
            }
            if (outgoingQueue.isEmpty()) {
                break;
            }
            p = outgoingQueue.remove();
        }
        // TODO: maybe we should flush in the loop above every N packets/bytes?
        // But, how do we determine the right value for N ...
        if (anyPacketsSent) {
            channel.flush();
        }
    }

    @Override
    void sendPacket(ClientCnxn.Packet p) throws IOException {
        if (channel == null) {
            throw new IOException("channel has been closed");
        }
        sendPktAndFlush(p);
    }

    @Override
    SocketAddress getRemoteSocketAddress() {
        Channel copiedChanRef = channel;
        return (copiedChanRef == null) ? null : copiedChanRef.remoteAddress();
    }

    @Override
    SocketAddress getLocalSocketAddress() {
        Channel copiedChanRef = channel;
        return (copiedChanRef == null) ? null : copiedChanRef.localAddress();
    }

    @Override
    void testableCloseSocket() throws IOException {
        Channel copiedChanRef = channel;
        if (copiedChanRef != null) {
            copiedChanRef.disconnect().awaitUninterruptibly();
        }
    }


    // *************** <END> CientCnxnSocketNetty </END> ******************
    private static class WakeupPacket {
        private static final Packet instance = new Packet(null, null, null, null, null);

        protected WakeupPacket() {
            // Exists only to defeat instantiation.
        }

        public static Packet getInstance() {
            return instance;
        }
    }

    /**
     * ZKClientPipelineFactory is the netty pipeline factory for this netty
     * connection implementation.
     *
     * ZKClientPipelineFactory 这个类,就相当于自己写Netty示例的时候,使用的xxxInitializer.
     * 在里面添加各种pipeline
     *
     */
    private class ZKClientPipelineFactory extends ChannelInitializer<SocketChannel> {
        private SSLContext sslContext = null;
        private SSLEngine sslEngine = null;
        private String host;
        private int port;

        public ZKClientPipelineFactory(String host, int port) {
            this.host = host;
            this.port = port;
        }

        @Override
        protected void initChannel(SocketChannel ch) throws Exception {
            ChannelPipeline pipeline = ch.pipeline();
            if (clientConfig.getBoolean(ZKClientConfig.SECURE_CLIENT)) {
                initSSL(pipeline);
            }
            pipeline.addLast("handler", new ZKClientHandler());
        }

        // The synchronized is to prevent the race on shared variable "sslEngine".
        // Basically we only need to create it once.
        private synchronized void initSSL(ChannelPipeline pipeline) throws SSLContextException {
            if (sslContext == null || sslEngine == null) {
                try (X509Util x509Util = new ClientX509Util()) {
                    sslContext = x509Util.createSSLContext(clientConfig);
                    sslEngine = sslContext.createSSLEngine(host, port);
                    sslEngine.setUseClientMode(true);
                }
            }
            pipeline.addLast("ssl", new SslHandler(sslEngine));
            LOG.info("SSL handler added for channel: {}", pipeline.channel());
        }
    }

    /**
     * ZKClientHandler is the netty handler that sits in netty upstream last
     * place. It mainly handles read traffic and helps synchronize connection state.
     *
     * ZKClientHandler是实际发送/接收处理包的地方
     * 由于其继承了{@code SimpleChannelInboundHandler}且,将{@code ByteBuf}作为泛型
     * 所以,其接收和发送的数据包,都是由ByteBuf封装而成的.
     *
     */
    private class ZKClientHandler extends SimpleChannelInboundHandler<ByteBuf> {
        AtomicBoolean channelClosed = new AtomicBoolean(false);

        @Override
        public void channelInactive(ChannelHandlerContext ctx) throws Exception {
            LOG.info("channel is disconnected: {}", ctx.channel());
            cleanup();
        }

        /**
         * netty handler has encountered problems. We are cleaning it up and tell outside to close
         * the channel/connection.
         */
        private void cleanup() {
            if (!channelClosed.compareAndSet(false, true)) {
                return;
            }
            disconnected.set(true);
            onClosing();
        }

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, ByteBuf buf) throws Exception {
            updateNow();
            while (buf.isReadable()) {
                if (incomingBuffer.remaining() > buf.readableBytes()) {
                    int newLimit = incomingBuffer.position()
                            + buf.readableBytes();
                    incomingBuffer.limit(newLimit);
                }
                buf.readBytes(incomingBuffer);
                incomingBuffer.limit(incomingBuffer.capacity());

                if (!incomingBuffer.hasRemaining()) {
                    incomingBuffer.flip();
                    if (incomingBuffer == lenBuffer) {
                        recvCount.getAndIncrement();
                        readLength();
                    } else if (!initialized) {
                        readConnectResult();
                        lenBuffer.clear();
                        incomingBuffer = lenBuffer;
                        initialized = true;
                        updateLastHeard();
                    } else {
                        /**
                         * 读取到来自服务端的数据后,
                         * 调用readResponse进行解析
                         */
                        sendThread.readResponse(incomingBuffer);
                        lenBuffer.clear();
                        incomingBuffer = lenBuffer;
                        updateLastHeard();
                    }
                }
            }
            wakeupCnxn();
            // Note: SimpleChannelInboundHandler releases the ByteBuf for us
            // so we don't need to do it.
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            LOG.warn("Exception caught", cause);
            cleanup();
        }
    }

    /**
     * Sets the test ByteBufAllocator. This allocator will be used by all
     * future instances of this class.
     * It is not recommended to use this method outside of testing.
     * @param allocator the ByteBufAllocator to use for all netty buffer
     *                  allocations.
     */
    static void setTestAllocator(ByteBufAllocator allocator) {
        TEST_ALLOCATOR.set(allocator);
    }

    /**
     * Clears the test ByteBufAllocator. The default allocator will be used
     * by all future instances of this class.
     * It is not recommended to use this method outside of testing.
     */
    static void clearTestAllocator() {
        TEST_ALLOCATOR.set(null);
    }
}
