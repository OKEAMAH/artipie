/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.maven.http;

import com.artipie.asto.Content;
import com.artipie.http.Headers;
import com.artipie.http.ResponseBuilder;
import com.artipie.http.Response;
import com.artipie.http.Slice;
import com.artipie.http.client.ClientSlices;
import com.artipie.http.client.jetty.JettyClientSlices;
import com.artipie.http.rq.RequestLine;
import com.artipie.http.RsStatus;
import com.artipie.http.slice.LoggingSlice;
import com.artipie.vertx.VertxSliceServer;
import io.vertx.reactivex.core.Vertx;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.net.HttpURLConnection;
import java.net.URI;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * Test for {@link RepoHead}.
 */
class RepoHeadITCase {

    private static final Vertx VERTX = Vertx.vertx();

    /**
     * Jetty client.
     */
    private final JettyClientSlices client = new JettyClientSlices();

    /**
     * Server port.
     */
    private int port;

    /**
     * Vertx slice server instance.
     */
    private VertxSliceServer server;

    @BeforeEach
    void setUp() throws Exception {
        this.client.start();
        this.server = new VertxSliceServer(
            RepoHeadITCase.VERTX,
            new LoggingSlice(new FakeProxy(this.client))
        );
        this.port = this.server.start();
    }

    @AfterEach
    void tearDown() throws Exception {
        this.client.stop();
        this.server.stop();
    }

    @Test
    void performsHeadRequest() throws Exception {
        final HttpURLConnection con = (HttpURLConnection) URI.create(
            String.format(
                "http://localhost:%s/maven2/args4j/args4j/2.32/args4j-2.32.pom", this.port
            )
        ).toURL().openConnection();
        con.setRequestMethod("GET");
        MatcherAssert.assertThat(
            con.getResponseCode(),
            new IsEqual<>(RsStatus.OK.code())
        );
        con.disconnect();
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void worksForInvalidUrl() throws Exception {
        final HttpURLConnection con = (HttpURLConnection) URI.create(
            String.format(
                "http://localhost:%s/maven2/abc/123", this.port
            )
        ).toURL().openConnection();
        con.setRequestMethod("GET");
        MatcherAssert.assertThat(
            con.getResponseCode(),
            new IsEqual<>(RsStatus.NOT_FOUND.code())
        );
        con.disconnect();
    }

    /**
     * Fake proxy slice.
     */
    private static final class FakeProxy implements Slice {

        private final ClientSlices client;

        private FakeProxy(final ClientSlices client) {
            this.client = client;
        }

        @Override
        public CompletableFuture<Response> response(
            final RequestLine line,
            final Headers headers,
            final Content body
        ) {
            return new RepoHead(this.client.https("repo.maven.apache.org"))
                .head(line.uri().toString())
                .handle(
                    (head, throwable) -> {
                        final CompletionStage<Response> res;
                        if (throwable == null) {
                            if (head.isPresent()) {
                                res = CompletableFuture.completedFuture(
                                    ResponseBuilder.ok().headers(head.get()).build()
                                );
                            } else {
                                res = CompletableFuture.completedFuture(
                                    ResponseBuilder.notFound().build()
                                );
                            }
                        } else {
                            res = CompletableFuture.failedFuture(throwable);
                        }
                        return res;
                    }
                ).thenCompose(Function.identity()).toCompletableFuture();
        }
    }

}
