/*
 * The MIT License (MIT)
 *
 * Copyright (c) 2020 artipie.com
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included
 * in all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NON-INFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package com.artipie.metrics.asto;

import com.artipie.asto.Content;
import com.artipie.asto.Key;
import com.artipie.asto.Storage;
import com.artipie.asto.ext.PublisherAs;
import com.artipie.metrics.Counter;
import com.artipie.metrics.Gauge;
import com.artipie.metrics.Metrics;
import com.jcabi.log.Logger;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * Storage metrics.
 * @since 0.19
 */
public final class StorageMetrics implements Metrics {

    /**
     * Storage for metrics.
     */
    private final Storage storage;

    /**
     * New storage metrics.
     * @param storage Storage
     */
    public StorageMetrics(final Storage storage) {
        this.storage = storage;
    }

    @Override
    public Counter counter(final String name) {
        return new StorageDatabase(this.storage, new Key.From(name));
    }

    @Override
    public Gauge gauge(final String name) {
        return new StorageDatabase(this.storage, new Key.From(name));
    }

    /**
     * Storage metrics database.
     * @since 0.19
     */
    private static final class StorageDatabase implements Counter, Gauge {

        /**
         * Storage.
         */
        private final Storage storage;

        /**
         * Metrics key.
         */
        private final Key key;

        /**
         * New storage metrics database.
         * @param storage Storage
         * @param key Key
         */
        StorageDatabase(final Storage storage, final Key key) {
            this.storage = storage;
            this.key = key;
        }

        @Override
        public void set(final long value) {
            this.storage.save(this.key, content(value))
                .handle(StorageDatabase::handle);
        }

        @Override
        public void add(final long amount) {
            this.storage.exists(this.key).thenCompose(
                exists -> {
                    final CompletionStage<Long> res;
                    if (exists) {
                        res = this.storage.value(this.key).thenCompose(
                            content -> new PublisherAs(content)
                                .string(StandardCharsets.UTF_8)
                                .thenApply(Long::valueOf)
                        );
                    } else {
                        res = CompletableFuture.completedFuture(0L);
                    }
                    return res;
                }
            ).thenCompose(val -> this.storage.save(this.key, content(val + amount)))
                .handle(StorageDatabase::handle);
        }

        @Override
        public void inc() {
            this.add(1);
        }

        /**
         * Content for long.
         * @param val Long value
         * @return Content publisher
         */
        private static Content content(final long val) {
            return new Content.From(Long.toString(val).getBytes(StandardCharsets.UTF_8));
        }

        /**
         * Handle async result.
         * @param none Void result
         * @param err Error
         * @return Nothing
         */
        private static Void handle(final Void none, final Throwable err) {
            if (err != null) {
                Logger.warn(
                    StorageMetrics.class, "Failed to update metric value: %[exception]s", err
                );
            }
            return none;
        }
    }
}
