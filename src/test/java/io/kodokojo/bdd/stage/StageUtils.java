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
package io.kodokojo.bdd.stage;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.Ports;
import com.squareup.okhttp.Request;
import io.kodokojo.commons.model.Service;
import io.kodokojo.commons.utils.DockerTestSupport;
import io.kodokojo.commons.utils.RSAUtils;
import io.kodokojo.model.Entity;
import io.kodokojo.model.User;
import io.kodokojo.service.store.EntityStore;
import io.kodokojo.service.store.ProjectStore;
import io.kodokojo.service.store.UserStore;
import org.apache.commons.lang.StringUtils;
import org.glassfish.tyrus.client.ClientManager;
import org.glassfish.tyrus.client.ClientProperties;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;
import redis.clients.jedis.exceptions.JedisConnectionException;

import javax.websocket.ClientEndpointConfig;
import javax.websocket.DeploymentException;
import javax.websocket.Session;
import java.io.IOException;
import java.math.BigInteger;
import java.net.URI;
import java.net.URISyntaxException;
import java.security.KeyPair;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.interfaces.RSAPublicKey;
import java.util.Base64;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

public class StageUtils {

    private StageUtils() {
        //
    }

    public static Request.Builder addBasicAuthentification(User user, Request.Builder builder) {
        assert user != null : "user must be defined";
        assert builder != null : "builder must be defined";
        return addBasicAuthentification(user.getUsername(), user.getPassword(), builder);
    }

    public static Request.Builder addBasicAuthentification(UserInfo user, Request.Builder builder) {
        assert user != null : "user must be defined";
        assert builder != null : "builder must be defined";
        return addBasicAuthentification(user.getUsername(), user.getPassword(), builder);
    }

    public static Request.Builder addBasicAuthentification(String username, String password, Request.Builder builder) {
        assert StringUtils.isNotBlank(username) : "username must be defined";
        assert builder != null : "builder must be defined";

        String value = "Basic " + Base64.getEncoder().encodeToString((username + ":" + password).getBytes());
        builder.addHeader("Authorization", value);
        return builder;
    }

    public static String generateHelloWebSocketMessage(User user) {
        String aggregateCredentials = String.format("%s:%s", user.getUsername(), user.getPassword());
        String encodedCredentials = Base64.getEncoder().encodeToString(aggregateCredentials.getBytes());
        return "{\n" +
                "  \"entity\": \"user\",\n" +
                "  \"action\": \"authentication\",\n" +
                "  \"data\": {\n" +
                "    \"authorization\": \"Basic " + encodedCredentials + "\"\n" +
                "  }\n" +
                "}";
    }

    public static Service startDockerRedis(DockerTestSupport dockerTestSupport) {
        DockerClient dockerClient = dockerTestSupport.getDockerClient();

        Ports portBinding = new Ports();
        ExposedPort exposedPort = ExposedPort.tcp(6379);
        portBinding.bind(exposedPort, Ports.Binding(null));

        CreateContainerResponse createContainerResponse = dockerClient.createContainerCmd("redis:latest")
                .withExposedPorts(exposedPort)
                .withPortBindings(portBinding)
                .exec();
        dockerClient.startContainerCmd(createContainerResponse.getId()).exec();
        dockerTestSupport.addContainerIdToClean(createContainerResponse.getId());

        String redisHost = dockerTestSupport.getServerIp();
        int redisPort = dockerTestSupport.getExposedPort(createContainerResponse.getId(), 6379);

        long end = System.currentTimeMillis() + 10000;
        boolean redisIsReady = false;
        while (!redisIsReady && (end - System.currentTimeMillis()) > 0) {
            JedisPool jedisPool = new JedisPool(new JedisPoolConfig(), redisHost, redisPort);
            try (Jedis jedis = jedisPool.getResource()) {
                String resPing = jedis.ping();
                redisIsReady = "PONG".equals(resPing);
            } catch (JedisConnectionException e) {
                //  Silently ignore, Redis may not be available
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        assertThat(redisIsReady).isTrue();
        return new Service("redis", redisHost, redisPort);
    }

    public static User createUser(String username, UserStore userStore, EntityStore entityStore) {
        String identifier = userStore.generateId();
        String password = new BigInteger(130, new SecureRandom()).toString(32);
        User user = null;

        try {
            KeyPair keyPair = RSAUtils.generateRsaKeyPair();
            RSAPublicKey publicKey = (RSAPublicKey) keyPair.getPublic();
            String email = username + "@kodokojo.io";
            String sshPublicKey = RSAUtils.encodePublicKey(publicKey, email);
            user = new User(identifier, username, username, email, password, sshPublicKey);
            String entityId = entityStore.addEntity(new Entity(username, user));
            boolean userAdded = userStore.addUser(new User(user.getIdentifier(), entityId, username, username, email, password, sshPublicKey));
            assertThat(userAdded).isTrue();

        } catch (NoSuchAlgorithmException e) {
            fail(e.getMessage());
        }
        return user;
    }


    public static WebSocketConnectionResult connectToWebSocket(String entryPointUrl, User user, CountDownLatch nbMessageExpected) {
        final ClientEndpointConfig cec = ClientEndpointConfig.Builder.create().build();

        ClientManager client = ClientManager.createClient();
        client.getProperties().put(ClientProperties.CREDENTIALS, new org.glassfish.tyrus.client.auth.Credentials(user.getUsername(), user.getPassword()));
        String uriStr = "ws://" + entryPointUrl + "/api/v1/event";
        CountDownLatch webSocketConnected = new CountDownLatch(1);
        WebSocketEventsListener listener = new WebSocketEventsListener(new WebSocketEventsListener.CallBack() {
            @Override
            public void open(Session session) {
                try {
                    session.getBasicRemote().sendText(StageUtils.generateHelloWebSocketMessage(user));
                } catch (IOException e) {
                    fail(e.getMessage());
                }
                webSocketConnected.countDown();
            }

            @Override
            public void receive(Session session, String message) {
                nbMessageExpected.countDown();
            }

            @Override
            public void close(Session session) {
                webSocketConnected.countDown();
            }
        });
        Session session = null;
        try {
            session = client.connectToServer(listener, cec, new URI(uriStr));
            webSocketConnected.await(10, TimeUnit.SECONDS);
        } catch (DeploymentException | IOException | URISyntaxException | InterruptedException e) {
            e.printStackTrace();
            fail(e.getMessage());
        }
        return new WebSocketConnectionResult(session,listener);
    }

}
