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

import org.apache.jute.BinaryInputArchive;
import org.apache.zookeeper.ClientCnxn.Packet;
import org.apache.zookeeper.client.ZKClientConfig;
import org.apache.zookeeper.common.Time;
import org.apache.zookeeper.common.ZKConfig;
import org.apache.zookeeper.proto.ConnectResponse;
import org.apache.zookeeper.server.ByteBufferInputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.text.MessageFormat;
import java.util.List;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.atomic.AtomicLong;

import static org.apache.zookeeper.ZKUtil.logStackInfo;

/**
 * A ClientCnxnSocket does the lower level communication with a socket
 * implementation.
 * <p>
 * This code has been moved out of ClientCnxn so that a Netty implementation can
 * be provided as an alternative to the NIO socket code.
 */
abstract class ClientCnxnSocket {
    private static final Logger LOG = LoggerFactory.getLogger(ClientCnxnSocket.class);

    protected boolean initialized;

    /**
     * This buffer is only used to read the length of the incoming message.
     */
    protected final ByteBuffer lenBuffer = ByteBuffer.allocateDirect(4);

    /**
     * After the length is read, a new incomingBuffer is allocated in
     * readLength() to receive the full message.
     */
    protected ByteBuffer incomingBuffer = lenBuffer;
    protected final AtomicLong sentCount = new AtomicLong(0L);
    protected final AtomicLong recvCount = new AtomicLong(0L);
    /**
     * 设置客户端的最后新心跳时间
     */
    protected long lastHeard;
    /**
     * 客户端最后一次发送时间
     */
    protected long lastSend;
    protected long now;
    protected ClientCnxn.SendThread sendThread;
    /**
     * 发送和接收的消息会被组装成一个{@link Packet}对象,
     * 用{@code  LinkedBlockingDeque}来保存{@code Packet}对象,
     * 意味着,消息的数量理论上是无限的,且遵循先进先出的顺序.
     */
    protected LinkedBlockingDeque<Packet> outgoingQueue;
    protected ZKClientConfig clientConfig;
    private int packetLen = ZKClientConfig.CLIENT_MAX_PACKET_LENGTH_DEFAULT;

    /**
     * The sessionId is only available here for Log and Exception messages.
     * Otherwise the socket doesn't need to know it.
     * <p>
     * sessionId仅在此处可用于日志和异常消息.
     * 否则,套接字不需要知道它.
     */
    protected long sessionId;

    /**
     * 相当于自我介绍一下吧.给实际和服务端通信的对象设置当前客户端的sessionId,发送队列(outgoingQueue)和发送线程.
     *
     * @param sendThread
     * @param sessionId
     * @param outgoingQueue
     */
    void introduce(ClientCnxn.SendThread sendThread, long sessionId,
                   LinkedBlockingDeque<Packet> outgoingQueue) {
        this.sendThread = sendThread;
        this.sessionId = sessionId;
        this.outgoingQueue = outgoingQueue;
    }

    /**
     * 设置客户端的当前时间,在后面能用得到
     * 基于Netty的调用
     * 1. sendThread#run() 调用一次
     * 2. ClientCnxnSocketNetty#connect调用一次
     * 3. ClientCnxnSocketNetty#doWrite调用一次
     * 4. ClientCnxnSocketNetty#doTransport调用一次
     * 5. ClientCnxnSocketNetty.ZKClientHandler#channelRead0调用一次
     */
    void updateNow() {
        long beforeNow = now;
        now = Time.currentElapsedTime();
        logStackInfo("在updateNow函数中before now is:" + beforeNow + ",扶植之后,now is " + now);

    }

    int getIdleRecv() {
        logStackInfo("在getIdleRecv函数中now is:" + now + ",lastHeard is:" + lastHeard + ",now-lastHeart is:" + (now - lastHeard));
        return (int) (now - lastHeard);
    }

    int getIdleSend() {
        logStackInfo("在getIdleSend函数中now is:" + now + ",lastHeard is:" + lastSend + ",now-lastHeart is:" + (now - lastSend));
        return (int) (now - lastSend);
    }

    long getSentCount() {
        return sentCount.get();
    }

    long getRecvCount() {
        return recvCount.get();
    }

    void updateLastHeard() {
        this.lastHeard = now;
    }

    void updateLastSend() {
        this.lastSend = now;
    }


    void updateLastSendAndHeard() {
        logStackInfo("重新设置 lastSend and lastHeard is:" + now);
        this.lastSend = now;
        this.lastHeard = now;
    }

    void readLength() throws IOException {
        int len = incomingBuffer.getInt();
        if (len < 0 || len >= packetLen) {
            throw new IOException("Packet len" + len + " is out of range!");
        }
        incomingBuffer = ByteBuffer.allocate(len);
    }

    void readConnectResult() throws IOException {
        if (LOG.isTraceEnabled()) {
            StringBuilder buf = new StringBuilder("0x[");
            for (byte b : incomingBuffer.array()) {
                buf.append(Integer.toHexString(b) + ",");
            }
            buf.append("]");
            LOG.trace("readConnectResult " + incomingBuffer.remaining() + " "
                    + buf.toString());
        }
        ByteBufferInputStream bbis = new ByteBufferInputStream(incomingBuffer);
        BinaryInputArchive bbia = BinaryInputArchive.getArchive(bbis);
        ConnectResponse conRsp = new ConnectResponse();
        conRsp.deserialize(bbia, "connect");

        // read "is read-only" flag
        boolean isRO = false;
        try {
            isRO = bbia.readBool("readOnly");
        } catch (IOException e) {
            // this is ok -- just a packet from an old server which
            // doesn't contain readOnly field
            LOG.warn("Connected to an old server; r-o mode will be unavailable");
        }

        this.sessionId = conRsp.getSessionId();
        sendThread.onConnected(conRsp.getTimeOut(), this.sessionId,
                conRsp.getPasswd(), isRO);
    }

    abstract boolean isConnected();

    abstract void connect(InetSocketAddress addr) throws IOException;

    /**
     * Returns the address to which the socket is connected.
     */
    abstract SocketAddress getRemoteSocketAddress();

    /**
     * Returns the address to which the socket is bound.
     */
    abstract SocketAddress getLocalSocketAddress();

    /**
     * Clean up resources for a fresh new socket.
     * It's called before reconnect or close.
     */
    abstract void cleanup();

    /**
     * new packets are added to outgoingQueue.
     */
    abstract void packetAdded();

    /**
     * connState is marked CLOSED and notify ClientCnxnSocket to react.
     */
    abstract void onClosing();

    /**
     * Sasl completes. Allows non-priming packgets to be sent.
     * Note that this method will only be called if Sasl starts and completes.
     */
    abstract void saslCompleted();

    /**
     * being called after ClientCnxn finish PrimeConnection
     */
    abstract void connectionPrimed();

    /**
     * Do transportation work:
     * - read packets into incomingBuffer.
     * - write outgoing queue packets.
     * - update relevant timestamp.
     *
     * @param waitTimeOut  timeout in blocking wait. Unit in MilliSecond.
     * @param pendingQueue These are the packets that have been sent and
     *                     are waiting for a response.
     * @param cnxn
     * @throws IOException
     * @throws InterruptedException
     */
    abstract void doTransport(int waitTimeOut, List<Packet> pendingQueue,
                              ClientCnxn cnxn)
            throws IOException, InterruptedException;

    /**
     * Close the socket.
     */
    abstract void testableCloseSocket() throws IOException;

    /**
     * Close this client.
     */
    abstract void close();

    /**
     * Send Sasl packets directly.
     * The Sasl process will send the first (requestHeader == null) packet,
     * and then block the doTransport write,
     * finally unblock it when finished.
     */
    abstract void sendPacket(Packet p) throws IOException;

    /**
     * 设置了客户端发送到服务端的{@link Packet}的最大大小,
     * 如果没有指定,则设置为{@link ZKClientConfig#CLIENT_MAX_PACKET_LENGTH_DEFAULT}
     * 即:{@code 4MB}.
     *
     * @throws IOException
     */
    protected void initProperties() throws IOException {
        try {
            packetLen = clientConfig.getInt(ZKConfig.JUTE_MAXBUFFER,
                    ZKClientConfig.CLIENT_MAX_PACKET_LENGTH_DEFAULT);
            LOG.info("{} value is {} Bytes", ZKConfig.JUTE_MAXBUFFER,
                    packetLen);
        } catch (NumberFormatException e) {
            String msg = MessageFormat.format(
                    "Configured value {0} for property {1} can not be parsed to int",
                    clientConfig.getProperty(ZKConfig.JUTE_MAXBUFFER),
                    ZKConfig.JUTE_MAXBUFFER);
            LOG.error(msg);
            throw new IOException(msg);
        }
    }

}
