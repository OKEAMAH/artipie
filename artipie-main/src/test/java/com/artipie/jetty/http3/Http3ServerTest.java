/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.jetty.http3;

import com.artipie.asto.Content;
import com.artipie.asto.Splitting;
import com.artipie.http.Headers;
import com.artipie.http.Response;
import com.artipie.http.Slice;
import com.artipie.http.rq.RequestLine;
import com.artipie.http.rs.BaseResponse;
import com.artipie.http.rs.StandardRs;
import com.artipie.nuget.RandomFreePort;
import io.reactivex.Flowable;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpURI;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.http.MetaData;
import org.eclipse.jetty.http3.api.Session;
import org.eclipse.jetty.http3.api.Stream;
import org.eclipse.jetty.http3.client.HTTP3Client;
import org.eclipse.jetty.http3.frames.DataFrame;
import org.eclipse.jetty.http3.frames.HeadersFrame;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Test for {@link Http3Server}.
 */
class Http3ServerTest {

    /**
     * Test header name with request method.
     */
    private static final String RQ_METHOD = "rq_method";

    /**
     * Some test small data chunk.
     */
    private static final byte[] SMALL_DATA = "abc123".getBytes();

    /**
     * Test data size.
     */
    private static final int SIZE = 1024 * 1024;

    private Http3Server server;

    private HTTP3Client client;

    private int port;

    private Session.Client session;

    @BeforeEach
    void init() throws Exception {
        this.port = new RandomFreePort().value();
        final SslContextFactory.Server sslserver = new SslContextFactory.Server();
        sslserver.setKeyStoreType("jks");
        sslserver.setKeyStorePath("src/test/resources/ssl/keystore.jks");
        sslserver.setKeyStorePassword("secret");
        this.server = new Http3Server(new TestSlice(), this.port, sslserver);
        this.server.start();
        this.client = new HTTP3Client();
        this.client.getHTTP3Configuration().setStreamIdleTimeout(15_000);
        final SslContextFactory.Client ssl = new SslContextFactory.Client();
        ssl.setTrustAll(true);
        this.client.getClientConnector().setSslContextFactory(ssl);
        this.client.start();
        this.session = this.client.connect(
            new InetSocketAddress("localhost", this.port), new Session.Client.Listener() { }
        ).get();
    }

    @AfterEach
    void stop() throws Exception {
        this.client.stop();
        this.server.stop();
    }

    @ParameterizedTest
    @ValueSource(strings = {"GET", "HEAD", "DELETE"})
    void sendsRequestsAndReceivesResponseWithNoData(final String method) throws ExecutionException,
        InterruptedException, TimeoutException {
        final CountDownLatch count = new CountDownLatch(1);
        this.session.newRequest(
            new HeadersFrame(
                new MetaData.Request(
                    method, HttpURI.from(String.format("http://localhost:%d/no_data", this.port)),
                    HttpVersion.HTTP_3, HttpFields.from()
                ), true
            ),
            new Stream.Client.Listener() {
                @Override
                public void onResponse(final Stream.Client stream, final HeadersFrame frame) {
                    final MetaData meta = frame.getMetaData();
                    final MetaData.Response response = (MetaData.Response) meta;
                    MatcherAssert.assertThat(
                        response.getHttpFields().get(Http3ServerTest.RQ_METHOD),
                        new IsEqual<>(method)
                    );
                    count.countDown();
                }
            }
        ).get(5, TimeUnit.SECONDS);
        MatcherAssert.assertThat("Response was not received", count.await(5, TimeUnit.SECONDS));
    }

    @Test
    void getWithSmallResponseData() throws ExecutionException,
        InterruptedException, TimeoutException {
        final MetaData.Request request = new MetaData.Request(
            "GET", HttpURI.from(String.format("http://localhost:%d/small_data", this.port)),
            HttpVersion.HTTP_3, HttpFields.from()
        );
        final CountDownLatch rlatch = new CountDownLatch(1);
        final CountDownLatch dlatch = new CountDownLatch(1);
        final ByteBuffer resp = ByteBuffer.allocate(Http3ServerTest.SMALL_DATA.length);
        this.session.newRequest(
            new HeadersFrame(request, true),
            new Stream.Client.Listener() {

                @Override
                public void onResponse(final Stream.Client stream, final HeadersFrame frame) {
                    rlatch.countDown();
                    stream.demand();
                }

                @Override
                public void onDataAvailable(final Stream.Client stream) {
                    final Stream.Data data = stream.readData();
                    if (data != null) {
                        resp.put(data.getByteBuffer());
                        data.release();
                        if (data.isLast()) {
                            dlatch.countDown();
                        }
                    }
                    stream.demand();
                }
            }
        ).get(5, TimeUnit.SECONDS);
        MatcherAssert.assertThat("Response was not received", rlatch.await(5, TimeUnit.SECONDS));
        MatcherAssert.assertThat("Data were not received", dlatch.await(5, TimeUnit.SECONDS));
        Assertions.assertArrayEquals(Http3ServerTest.SMALL_DATA, resp.array());
    }

    @Test
    void getWithChunkedResponseData() throws ExecutionException,
        InterruptedException, TimeoutException {
        final MetaData.Request request = new MetaData.Request(
            "GET", HttpURI.from(String.format("http://localhost:%d/random_chunks", this.port)),
            HttpVersion.HTTP_3, HttpFields.from()
        );
        final CountDownLatch rlatch = new CountDownLatch(1);
        final CountDownLatch dlatch = new CountDownLatch(1);
        final ByteBuffer resp = ByteBuffer.allocate(Http3ServerTest.SIZE);
        this.session.newRequest(
            new HeadersFrame(request, true),
            new Stream.Client.Listener() {
                @Override
                public void onResponse(final Stream.Client stream, final HeadersFrame frame) {
                    rlatch.countDown();
                    stream.demand();
                }

                @Override
                public void onDataAvailable(final Stream.Client stream) {
                    final Stream.Data data = stream.readData();
                    if (data != null) {
                        resp.put(data.getByteBuffer());
                        data.release();
                        if (data.isLast()) {
                            dlatch.countDown();
                        }
                    }
                    stream.demand();
                }
            }
        ).get(5, TimeUnit.SECONDS);
        MatcherAssert.assertThat("Response was not received", rlatch.await(5, TimeUnit.SECONDS));
        MatcherAssert.assertThat("Data were not received", dlatch.await(60, TimeUnit.SECONDS));
        MatcherAssert.assertThat(resp.position(), new IsEqual<>(Http3ServerTest.SIZE));
    }

    @Test
    void putWithRequestDataResponse()
        throws ExecutionException, InterruptedException, TimeoutException {
        final int size = 964;
        final MetaData.Request request = new MetaData.Request(
            "PUT", HttpURI.from(String.format("http://localhost:%d/return_back", this.port)),
            HttpVersion.HTTP_3,
            HttpFields.build()
        );
        final CountDownLatch responseLatch = new CountDownLatch(1);
        final CountDownLatch dataAvailableLatch = new CountDownLatch(1);
        final byte[] data = new byte[size];
        final ByteBuffer resp = ByteBuffer.allocate(size * 2);
        new Random().nextBytes(data);
        this.session.newRequest(
            new HeadersFrame(request, false),
            new Stream.Client.Listener() {
                @Override
                public void onResponse(final Stream.Client stream, final HeadersFrame frame) {
                    responseLatch.countDown();
                    stream.demand();
                }

                @Override
                public void onDataAvailable(final Stream.Client stream) {
                    final Stream.Data data = stream.readData();
                    if (data != null) {
                        resp.put(data.getByteBuffer());
                        data.release();
                        if (data.isLast()) {
                            dataAvailableLatch.countDown();
                        }
                    }
                    stream.demand();
                }
            }
        ).thenCompose(cl -> cl.data(new DataFrame(ByteBuffer.wrap(data), false)))
            .thenCompose(cl -> cl.data(new DataFrame(ByteBuffer.wrap(data), true)))
            .get(5, TimeUnit.SECONDS);

        MatcherAssert.assertThat("Response was not received", responseLatch.await(10, TimeUnit.SECONDS));
        MatcherAssert.assertThat("Data were not received", dataAvailableLatch.await(60, TimeUnit.SECONDS));
        final ByteBuffer copy = ByteBuffer.allocate(size * 2);
        copy.put(data);
        copy.put(data);
        Assertions.assertArrayEquals(copy.array(), resp.array());
    }

    /**
     * Slice for tests.
     */
    static final class TestSlice implements Slice {

        @Override
        public Response response(RequestLine line, Headers headers, Content body) {
            if (line.toString().contains("no_data")) {
                return BaseResponse.ok()
                    .header( Http3ServerTest.RQ_METHOD, line.method().value());
            }
            if (line.toString().contains("small_data")) {
                return BaseResponse.ok().body(Http3ServerTest.SMALL_DATA);
            }
            if (line.toString().contains("random_chunks")) {
                final Random random = new Random();
                final byte[] data = new byte[Http3ServerTest.SIZE];
                random.nextBytes(data);
                return BaseResponse.ok().body(
                    new Content.From(
                        Flowable.fromArray(ByteBuffer.wrap(data))
                            .flatMap(
                                buffer -> new Splitting(
                                    buffer, (random.nextInt(9) + 1) * 1024
                                ).publisher()
                            )
                            .delay(random.nextInt(5_000), TimeUnit.MILLISECONDS)
                    )
                );
            }
            if (line.toString().contains("return_back")) {
                return BaseResponse.ok().body(body);
            }
            return StandardRs.NOT_FOUND;
        }
    }
}
