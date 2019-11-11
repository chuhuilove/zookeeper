package org.apache.zookeeper.customize;

import org.apache.zookeeper.*;

import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

/**
 * RunClient
 * <p>
 * zookeepe的客户端,添加大量数据
 *
 * @author: cyzi
 * @Date: 2019/11/8 0008
 * @Description:
 */
public class RunClient {

    public static void main(String[] args) throws IOException {


        final ZooKeeper zooKeeper = new ZooKeeper("localhost:2181", 30000, (event) -> {
            System.err.println(event.getType());
        });


        ThreadPoolExecutor executor = new ThreadPoolExecutor(10, 10, 10L, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(10), (r) -> new Thread(r));


        for (int i = 1; i <= 10; i++) {
            executor.execute(() -> {

                try {
                    String rootName = UUID.randomUUID().toString().replace("-", "").substring(0, 5);
                    final String rootPath = zooKeeper.create("/" + rootName, "nothing".getBytes(), ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);

                    IntStream.rangeClosed(1, 200000000)
                            .forEach(e -> {
                                try {
                                    String newPath = zooKeeper.create(rootPath + "/" + e, UUID.randomUUID().toString().getBytes(), ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
                                    String newNewPath = zooKeeper.create(newPath + "/" + e, UUID.randomUUID().toString().getBytes(), ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
                                    zooKeeper.create(newNewPath + "/" + e, UUID.randomUUID().toString().getBytes(), ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
                                } catch (KeeperException ex) {
                                    ex.printStackTrace();
                                } catch (InterruptedException ex) {
                                    ex.printStackTrace();
                                }
                            });
                } catch (Exception e) {
                    e.printStackTrace();
                }
            });
        }
    }


}
