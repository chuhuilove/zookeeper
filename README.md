## zk源码阅读笔记

**2019年11月10日18:56:34**

从2019年11月3日开始,到现在为止,只是一个初入门,大致明白了zk服务端的启动过程和zk客户端的启动过程,但这还远远不够.

zk的持久化机制,还没有完全明白.客户端和服务端之间的心跳过程,zk集群的选举机制,这一切都还没有整明白.

持久化机制,还没有彻底整明白. 这个可以先放一放,还是先学习一下内部的通信机制吧.


**2019-12-23 11:19:47**

**客户端启动过程**

客户端的使用基于Netty(`org.apache.zookeeper.ClientCnxnSocketNetty`)的socket框架,不使用基于NIO的框架.


`org.apache.zookeeper.ZooKeeper`

在`org.apache.zookeeper.ZooKeeper.ZooKeeper(java.lang.String, int, org.apache.zookeeper.Watcher, boolean, org.apache.zookeeper.client.HostProvider, org.apache.zookeeper.client.ZKClientConfig)`
这个函数中,初始化了客户端操作的上下文(`org.apache.zookeeper.ClientCnxn`)对象(以下简称上下文对象).
这时,和服务端通信的socket对象已经通过反射创建了(`org.apache.zookeeper.ZooKeeper.getClientCnxnSocket()`).

虽然已经创建,但是还没有启动.

直到`ZooKeeper`的构造函数,调用了上下文对象的`start`方法.

在上下文对象的`start()`函数中,启动了两个守护线程

1. `sendThread`位于`org.apache.zookeeper.ClientCnxn.SendThread`,是一个内置类.

看一下`sendThread`线程中的`run`方法(`org.apache.zookeeper.ClientCnxn.SendThread.run`).




2. `eventThread`位于`org.apache.zookeeper.ClientCnxn.EventThread`,也是一个线程




















