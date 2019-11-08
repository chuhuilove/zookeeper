package org.apache.zookeeper.customize;

import org.apache.zookeeper.*;

import java.io.IOException;
import java.util.UUID;
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

    public static void main(String[] args) {

        IntStream.rangeClosed(1, 200)
                .parallel()
                .forEach(ep -> {
                    try {

                        final ZooKeeper zooKeeper = new ZooKeeper("localhost:2181", 30000, (event) -> {
                            System.err.println(event.getType());
                        });




                        String rootName = UUID.randomUUID().toString().replace("-", "").substring(0, 5);
                        final String rootPath = zooKeeper.create("/" + rootName, "nothing".getBytes(), ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);

                        IntStream.rangeClosed(1, 20)
                                .parallel()
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
