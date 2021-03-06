/**
 * Kodo Kojo - Software factory done right
 * Copyright © 2016 Kodo Kojo (infos@kodokojo.io)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package io.kodokojo.service.zookeeper;

import io.kodokojo.config.ZookeeperConfig;
import io.kodokojo.model.BootstrapStackData;
import io.kodokojo.service.ConfigurationStore;
import io.kodokojo.service.ssl.SSLKeyPair;
import javaslang.control.Try;
import org.apache.zookeeper.*;
import org.apache.zookeeper.data.Stat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

import static io.kodokojo.service.zookeeper.ZookeeperBootstrapConfigurationProvider.KODOKOJO_TCP_PORT;
import static java.util.Objects.requireNonNull;

public class ZookeeperConfigurationStore implements ConfigurationStore, Watcher {

    private static final Logger LOGGER = LoggerFactory.getLogger(ZookeeperConfigurationStore.class);


    private final ZooKeeper zooKeeper;

    public ZookeeperConfigurationStore(ZookeeperConfig zookeeperConfig) {
        requireNonNull(zookeeperConfig, "zookeeperConfig must be defined.");
        try {
            zooKeeper = new ZooKeeper(zookeeperConfig.url(), 2000, this);
            zooKeeper.exists(KODOKOJO_TCP_PORT, this);
        } catch (IOException | InterruptedException | KeeperException e) {
            throw new RuntimeException("Unable to connect to Zookeeper on url " + zookeeperConfig.url() + ".", e);
        }
    }

    @Override
    public boolean storeBootstrapStackData(BootstrapStackData bootstrapStackData) {
        requireNonNull(bootstrapStackData, "bootstrapStackData must be defined.");
        return Try.of(() -> {
            String path = KODOKOJO_TCP_PORT + "/" + bootstrapStackData.getProjectName();
            byte[] data = ("" + bootstrapStackData.getSshPort()).getBytes();
            Stat stat = zooKeeper.exists(path, this);
            if (stat == null) {
                zooKeeper.create(path, data, ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
                if (LOGGER.isDebugEnabled()) {
                    LOGGER.debug("Store Tcp port {} on zookeeper path '{}'.", path, path);
                }
            } else {
                int version = stat.getVersion();
                zooKeeper.setData(path, data, version);
                if (LOGGER.isDebugEnabled()) {
                    LOGGER.debug("Update Tcp port {} on zookeeper path '{}'.", path, path);
                }
            }
            return true;
        }).isSuccess();
    }

    @Override
    public boolean storeSSLKeys(String projectName, String brickTypeName, SSLKeyPair sslKeyPair) {
        return true;
    }

    @Override
    public void process(WatchedEvent event) {
        //  Nothing to do.
    }


}
