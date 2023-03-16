/*
 * The MIT License (MIT) Copyright (c) 2020-2021 artipie.com
 * https://github.com/artipie/artipie/LICENSE.txt
 */
package com.artipie.api;

import com.artipie.api.ssl.KeyStore;
import com.artipie.asto.Storage;
import com.artipie.asto.blocking.BlockingStorage;
import com.artipie.auth.JwtTokens;
import com.artipie.security.policy.CachedYamlPolicy;
import com.artipie.settings.ArtipieSecurity;
import com.artipie.settings.RepoData;
import com.artipie.settings.Settings;
import com.artipie.settings.cache.ArtipieCaches;
import com.jcabi.log.Logger;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.http.HttpServer;
import io.vertx.ext.auth.jwt.JWTAuth;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.handler.JWTAuthHandler;
import io.vertx.ext.web.handler.StaticHandler;
import io.vertx.ext.web.openapi.RouterBuilder;
import java.util.Arrays;
import java.util.Optional;

/**
 * Vert.x {@link io.vertx.core.Verticle} for exposing Rest API operations.
 * @since 0.26
 * @checkstyle ClassDataAbstractionCouplingCheck (500 lines)
 * @checkstyle MemberNameCheck (500 lines)
 * @checkstyle ParameterNameCheck (500 lines)
 */
public final class RestApi extends AbstractVerticle {

    /**
     * The name of the security scheme (from the Open API description yaml).
     */
    private static final String SECURITY_SCHEME = "bearerAuth";

    /**
     * Artipie caches.
     */
    private final ArtipieCaches caches;

    /**
     * Artipie settings storage.
     */
    private final Storage configsStorage;

    /**
     * Artipie layout.
     */
    private final String layout;

    /**
     * Application port.
     */
    private final int port;

    /**
     * Artipie security.
     */
    private final ArtipieSecurity security;

    /**
     * KeyStore.
     */
    private final Optional<KeyStore> keystore;

    /**
     * Jwt authentication provider.
     */
    private final JWTAuth jwt;

    /**
     * Primary ctor.
     * @param caches Artipie settings caches
     * @param configsStorage Artipie settings storage
     * @param layout Artipie layout
     * @param port Port to run API on
     * @param security Artipie security
     * @param keystore KeyStore
     * @param jwt Jwt authentication provider
     * @checkstyle ParameterNumberCheck (10 lines)
     */
    public RestApi(
        final ArtipieCaches caches,
        final Storage configsStorage,
        final String layout,
        final int port,
        final ArtipieSecurity security,
        final Optional<KeyStore> keystore,
        final JWTAuth jwt
    ) {
        this.caches = caches;
        this.configsStorage = configsStorage;
        this.layout = layout;
        this.port = port;
        this.security = security;
        this.keystore = keystore;
        this.jwt = jwt;
    }

    /**
     * Ctor.
     * @param settings Artipie settings
     * @param port Port to start verticle on
     * @param jwt Jwt authentication provider
     * @checkstyle ParameterNumberCheck (5 lines)
     */
    public RestApi(final Settings settings, final int port, final JWTAuth jwt) {
        this(
            settings.caches(), settings.configStorage(), settings.layout().toString(),
            port, settings.authz(), settings.keyStore(), jwt
        );
    }

    @Override
    public void start() throws Exception {
        //@checkstyle LineLengthCheck (10 line)
        RouterBuilder.create(this.vertx, String.format("swagger-ui/yaml/repo-%s.yaml", this.layout)).compose(
            repoRb -> RouterBuilder.create(this.vertx, "swagger-ui/yaml/users.yaml").compose(
                userRb -> RouterBuilder.create(this.vertx, "swagger-ui/yaml/token-gen.yaml").compose(
                    tokenRb -> RouterBuilder.create(this.vertx, "swagger-ui/yaml/settings.yaml").compose(
                        settingsRb -> RouterBuilder.create(this.vertx, "swagger-ui/yaml/roles.yaml").onSuccess(
                            rolesRb -> this.startServices(repoRb, userRb, tokenRb, settingsRb, rolesRb)
                        ).onFailure(Throwable::printStackTrace)
                    )
                )
            )
        );
    }

    /**
     * Start rest services.
     * @param repoRb Repository RouterBuilder
     * @param userRb User RouterBuilder
     * @param tokenRb Token RouterBuilder
     * @param settingsRb Settings RouterBuilder
     * @param rolesRb Roles RouterBuilder
     * @checkstyle ParameterNameCheck (4 lines)
     * @checkstyle ParameterNumberCheck (3 lines)
     * @checkstyle ExecutableStatementCountCheck (30 lines)
     */
    private void startServices(final RouterBuilder repoRb, final RouterBuilder userRb,
        final RouterBuilder tokenRb, final RouterBuilder settingsRb, final RouterBuilder rolesRb) {
        this.addJwtAuth(tokenRb, repoRb, userRb, settingsRb, rolesRb);
        final Router router = repoRb.createRouter();
        final BlockingStorage asto = new BlockingStorage(this.configsStorage);
        new RepositoryRest(
            new ManageRepoSettings(asto),
            new RepoData(this.configsStorage, this.caches.storagesCache()), this.layout
        ).init(repoRb);
        new StorageAliasesRest(this.caches.storagesCache(), asto, this.layout).init(repoRb);
        if (this.security.policyStorage().isPresent()) {
            new UsersRest(
                new ManageUsers(new BlockingStorage(this.security.policyStorage().get())),
                this.caches.usersCache(), this.caches.policyCache(), this.security.authentication()
            ).init(userRb);
            router.route("/*").subRouter(userRb.createRouter());
        }
        if (this.security.policy() instanceof CachedYamlPolicy) {
            new RolesRest(
                new ManageRoles(new BlockingStorage(this.security.policyStorage().get())),
                this.caches.policyCache()
            ).init(rolesRb);
            router.route("/*").subRouter(rolesRb.createRouter());
        }
        new SettingsRest(this.port, this.layout).init(settingsRb);
        router.route("/*").subRouter(tokenRb.createRouter());
        router.route("/*").subRouter(settingsRb.createRouter());
        router.route("/api/*").handler(
            StaticHandler.create("swagger-ui")
                .setIndexPage(String.format("index-%s.html", this.layout))
        );
        final HttpServer server;
        final String schema;
        if (this.keystore.isPresent() && this.keystore.get().enabled()) {
            server = vertx.createHttpServer(
                this.keystore.get().secureOptions(this.vertx, this.configsStorage)
            );
            schema = "https";
        } else {
            server = this.vertx.createHttpServer();
            schema = "http";
        }
        server.requestHandler(router)
            .listen(this.port)
            //@checkstyle LineLengthCheck (1 line)
            .onComplete(res -> Logger.info(this, String.format("Rest API started on port %d, swagger is available on %s://localhost:%d/api/index-%s.html", this.port, schema, this.port, this.layout)))
            .onFailure(err -> Logger.error(this, err.getMessage()));
    }

    /**
     * Create and add all JWT-auth related settings:
     *  - initialize rest method to issue JWT tokens;
     *  - add security handlers to all REST API requests.
     * @param token Auth tokens generate API router builder
     * @param builders Router builders to add token auth to
     */
    private void addJwtAuth(final RouterBuilder token, final RouterBuilder... builders) {
        new AuthTokenRest(new JwtTokens(this.jwt), this.security.authentication()).init(token);
        Arrays.stream(builders).forEach(
            item -> item.securityHandler(RestApi.SECURITY_SCHEME, JWTAuthHandler.create(this.jwt))
        );
    }
}
