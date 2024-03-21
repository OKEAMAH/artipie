/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.http.group;

import com.artipie.asto.Content;
import com.artipie.http.Headers;
import com.artipie.http.Slice;
import com.artipie.http.async.AsyncSlice;
import com.artipie.http.hm.RsHasBody;
import com.artipie.http.hm.RsHasStatus;
import com.artipie.http.hm.SliceHasResponse;
import com.artipie.http.rq.RequestLine;
import com.artipie.http.rq.RqMethod;
import com.artipie.http.rs.RsFull;
import com.artipie.http.rs.RsStatus;
import com.artipie.http.slice.SliceSimple;
import org.hamcrest.MatcherAssert;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

/**
 * Test case for {@link GroupSlice}.
 *
 * @since 0.16
 */
final class GroupSliceTest {

    @Test
    @Timeout(1)
    void returnsFirstOrderedSuccessResponse() {
        final String expects = "ok-150";
        MatcherAssert.assertThat(
            new GroupSlice(
                slice(RsStatus.NOT_FOUND, "not-found-250", Duration.ofMillis(250)),
                slice(RsStatus.NOT_FOUND, "not-found-50", Duration.ofMillis(50)),
                slice(RsStatus.OK, expects, Duration.ofMillis(150)),
                slice(RsStatus.NOT_FOUND, "not-found-200", Duration.ofMillis(200)),
                slice(RsStatus.OK, "ok-50", Duration.ofMillis(50)),
                slice(RsStatus.OK, "ok-never", Duration.ofDays(1))
            ),
            new SliceHasResponse(
                Matchers.allOf(
                    new RsHasStatus(RsStatus.OK),
                    new RsHasBody(expects, StandardCharsets.UTF_8)
                ),
                new RequestLine(RqMethod.GET, "/")
            )
        );
    }

    @Test
    void returnsNotFoundIfAllFails() {
        MatcherAssert.assertThat(
            new GroupSlice(
                slice(RsStatus.NOT_FOUND, "not-found-140", Duration.ofMillis(250)),
                slice(RsStatus.NOT_FOUND, "not-found-10", Duration.ofMillis(50)),
                slice(RsStatus.NOT_FOUND, "not-found-110", Duration.ofMillis(200))
            ),
            new SliceHasResponse(
                new RsHasStatus(RsStatus.NOT_FOUND),
                new RequestLine(RqMethod.GET, "/foo")
            )
        );
    }

    @Test
    @Timeout(1)
    void returnsNotFoundIfSomeFailsWithException() {
        MatcherAssert.assertThat(
            new GroupSlice(
                (line, headers, body) -> connection -> {
                    final CompletableFuture<Void> future = new CompletableFuture<>();
                    future.completeExceptionally(new IllegalStateException());
                    return future;
                }
            ),
            new SliceHasResponse(
                new RsHasStatus(RsStatus.NOT_FOUND),
                new RequestLine(RqMethod.GET, "/faulty/path")
            )
        );
    }

    private static Slice slice(final RsStatus status, final String body, final Duration delay) {
        return new SliceWithDelay(
            new SliceSimple(
                new RsFull(status, Headers.EMPTY, new Content.From(body.getBytes(StandardCharsets.UTF_8)))
            ), delay
        );
    }

    /**
     * Slice testing decorator to add delay before sending request to origin slice.
     * @since 0.16
     */
    private static final class SliceWithDelay extends Slice.Wrap {

        /**
         * Add delay for slice.
         * @param origin Origin slice
         * @param delay Delay duration
         */
        SliceWithDelay(final Slice origin, final Duration delay) {
            super(
                new AsyncSlice(
                    CompletableFuture.runAsync(
                        () -> {
                            try {
                                Thread.sleep(delay.toMillis());
                            } catch (final InterruptedException ignore) {
                                Thread.currentThread().interrupt();
                            }
                        }
                    ).thenApply(none -> origin)
                )
            );
        }
    }
}
