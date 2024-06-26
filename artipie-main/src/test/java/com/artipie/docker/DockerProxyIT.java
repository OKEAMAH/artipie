/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.docker;

import com.artipie.docker.proxy.ProxyDocker;
import com.artipie.test.TestDockerClient;
import com.artipie.test.vertxmain.TestVertxMain;
import com.artipie.test.vertxmain.TestVertxMainBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.ws.rs.core.UriBuilder;
import java.nio.file.Path;

/**
 * Integration test for {@link ProxyDocker}.
 */
final class DockerProxyIT {

    @TempDir
    Path temp;

    private TestVertxMain server;

    private TestDockerClient client;

    @BeforeEach
    void setUp() throws Exception {
        server = new TestVertxMainBuilder(temp)
                .withUser("alice", "security/users/alice.yaml")
                .withDockerProxyRepo(
                        "my-docker",
                        temp.resolve("docker_proxy_data"),
                        UriBuilder.fromUri("mcr.microsoft.com").build(),
                        UriBuilder.fromUri("registry-1.docker.io").build()
                )
                .build(TestDockerClient.INSECURE_PORTS[0]);
        client = new TestDockerClient(server.port());
        client.start();
    }

    @AfterEach
    void tearDown() {
        client.stop();
        server.close();
    }

    @Test
    void shouldPullBlobRemote() throws Exception {
        final Image image = new Image.ForOs();
        final String img = new Image.From(
                client.host(),
                String.format("my-docker/%s", image.name()),
                image.digest(),
                image.layer()
        ).remoteByDigest();
        client.login("alice", "123")
                .pull(img);
    }

    @Test
    void shouldPullImageRemote() throws Exception {
        String image = client.host() + "/my-docker/library/alpine:3.19";
        client.login("alice", "123")
                .pull(image);
    }

    @Test
    void shouldPullImageWithListManifestRemote() throws Exception {
        String image = client.host() + "/my-docker/library/postgres:16.2";
        client.login("alice", "123")
                .pull(image);
    }

    @Test
    void shouldPushAndPull() throws Exception {
        final String image = client.host() + "/my-docker/alpine:3.11";
        client.login("alice", "123")
                .pull("alpine:3.11")
                .tag("alpine:3.11", image)
                .push(image)
                .remove(image)
                .pull(image);
    }
}
