/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.docker.proxy;

import com.artipie.asto.Content;
import com.artipie.asto.FailedCompletionStage;
import com.artipie.docker.Digest;
import com.artipie.docker.Manifests;
import com.artipie.docker.Repo;
import com.artipie.docker.RepoName;
import com.artipie.docker.Tag;
import com.artipie.docker.Tags;
import com.artipie.docker.http.DigestHeader;
import com.artipie.docker.manifest.JsonManifest;
import com.artipie.docker.manifest.Manifest;
import com.artipie.docker.ref.ManifestRef;
import com.artipie.http.Headers;
import com.artipie.http.Slice;
import com.artipie.http.headers.Header;
import com.artipie.http.rq.RequestLine;
import com.artipie.http.rq.RqMethod;
import com.artipie.http.rs.RsStatus;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * Proxy implementation of {@link Repo}.
 */
public final class ProxyManifests implements Manifests {

    private static final Headers MANIFEST_ACCEPT_HEADERS = new Headers.From(
            new Header("Accept", "application/json"),
            new Header("Accept", "application/vnd.oci.image.index.v1+json"),
            new Header("Accept", "application/vnd.oci.image.manifest.v1+json"),
            new Header("Accept", "application/vnd.docker.distribution.manifest.v1+prettyjws"),
            new Header("Accept", "application/vnd.docker.distribution.manifest.v2+json"),
            new Header("Accept", "application/vnd.docker.distribution.manifest.list.v2+json")
    );

    /**
     * Remote repository.
     */
    private final Slice remote;

    /**
     * Repository name.
     */
    private final RepoName name;

    /**
     * Ctor.
     *
     * @param remote Remote repository.
     * @param name Repository name.
     */
    public ProxyManifests(final Slice remote, final RepoName name) {
        this.remote = remote;
        this.name = name;
    }

    @Override
    public CompletionStage<Manifest> put(final ManifestRef ref, final Content content) {
        throw new UnsupportedOperationException();
    }

    @Override
    public CompletionStage<Optional<Manifest>> get(final ManifestRef ref) {
        return new ResponseSink<>(
            this.remote.response(
                new RequestLine(RqMethod.GET, new ManifestPath(this.name, ref).string()).toString(),
                MANIFEST_ACCEPT_HEADERS,
                Content.EMPTY
            ),
            (status, headers, body) -> {
                final CompletionStage<Optional<Manifest>> result;
                if (status == RsStatus.OK) {
                    final Digest digest = new DigestHeader(headers).value();
                    result = new Content.From(body).asBytesFuture().thenApply(
                        bytes -> Optional.of(new JsonManifest(digest, bytes))
                    );
                } else if (status == RsStatus.NOT_FOUND) {
                    result = CompletableFuture.completedFuture(Optional.empty());
                } else {
                    result = unexpected(status);
                }
                return result;
            }
        ).result();
    }

    @Override
    public CompletionStage<Tags> tags(final Optional<Tag> from, final int limit) {
        return new ResponseSink<>(
            this.remote.response(
                new RequestLine(
                    RqMethod.GET,
                    new TagsListUri(this.name, from, limit).string()
                ).toString(),
                Headers.EMPTY,
                Content.EMPTY
            ),
            (status, headers, body) -> {
                final CompletionStage<Tags> result;
                if (status == RsStatus.OK) {
                    result = CompletableFuture.completedFuture(()-> new Content.From(body));
                } else {
                    result = unexpected(status);
                }
                return result;
            }
        ).result();
    }

    /**
     * Creates completion stage failed with unexpected status exception.
     *
     * @param status Status to be reported in error.
     * @param <T> Completion stage result type.
     * @return Failed completion stage.
     */
    private static <T> CompletionStage<T> unexpected(final RsStatus status) {
        return new FailedCompletionStage<>(
            new IllegalArgumentException(String.format("Unexpected status: %s", status))
        );
    }
}
