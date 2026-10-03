package io.kestra.plugin.quickbooks;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;

import io.kestra.core.exceptions.IllegalVariableEvaluationException;
import io.kestra.core.http.HttpRequest;
import io.kestra.core.http.client.HttpClient;
import io.kestra.core.http.client.HttpClientResponseException;

import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.Task;
import io.kestra.core.runners.RunContext;
import io.kestra.core.serializers.JacksonMapper;
import io.kestra.core.storages.kv.KVMetadata;
import io.kestra.core.storages.kv.KVStore;
import io.kestra.core.storages.kv.KVValueAndMetadata;
import io.swagger.v3.oas.annotations.media.Schema;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
public abstract class AbstractQuickBooksConnection extends Task {
    private static final String DEFAULT_BASE_URL = "https://quickbooks.api.intuit.com";
    private static final String DEFAULT_AUTH_URL = "https://oauth.platform.intuit.com/oauth2/v1/tokens/bearer";
    private static final Integer DEFAULT_MINOR_VERSION = 75;
    private static final java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.locks.ReentrantLock> REFRESH_LOCKS = new java.util.concurrent.ConcurrentHashMap<>();


    @Schema(
        title = "QuickBooks Client ID",
        description = "The OAuth2 Client ID for your QuickBooks application"
    )
    @PluginProperty(secret = true, group = "connection")
    @NotNull
    @ToString.Exclude
    protected Property<String> clientId;

    @Schema(
        title = "QuickBooks Client Secret",
        description = "The OAuth2 Client Secret for your QuickBooks application"
    )
    @PluginProperty(secret = true, group = "connection")
    @NotNull
    @ToString.Exclude
    protected Property<String> clientSecret;

    @Schema(
        title = "QuickBooks Refresh Token",
        description = "A valid OAuth2 refresh token. Kestra will automatically rotate and persist it in the KV Store. Note: Token rotation is not cross-worker concurrency safe, and failed KV store writes may result in lost tokens."
    )
    @PluginProperty(secret = true, group = "connection")
    @NotNull
    @ToString.Exclude
    protected Property<String> refreshToken;

    @Schema(
        title = "QuickBooks Realm ID",
        description = "The Realm ID (Company ID) for the QuickBooks account"
    )
    @PluginProperty(group = "connection")
    @NotNull
    @Pattern(regexp = "^[0-9]+$")
    protected Property<String> realmId;

    @Schema(
        title = "Base URL",
        description = "The Base URL for QuickBooks API. Must be an intuit.com domain. Defaults to production.",
        defaultValue = "https://quickbooks.api.intuit.com"
    )
    @PluginProperty(group = "connection")
    @Builder.Default
    protected Property<String> baseUrl = Property.ofValue(DEFAULT_BASE_URL);

    @Schema(
        title = "Minor Version",
        description = "The minor version of the QuickBooks API to use. Must be strictly positive.",
        defaultValue = "75"
    )
    @PluginProperty(group = "connection")
    @Min(1)
    @Builder.Default
    protected Property<Integer> minorVersion = Property.ofValue(DEFAULT_MINOR_VERSION);

    @Schema(
        title = "Authentication URL",
        description = "The OAuth2 token endpoint. Must be an intuit.com domain.",
        defaultValue = "https://oauth.platform.intuit.com/oauth2/v1/tokens/bearer"
    )
    @PluginProperty(group = "connection")
    @Builder.Default
    protected Property<String> authUrl = Property.ofValue(DEFAULT_AUTH_URL);

    private void validateUrl(String name, String url) throws Exception {
        var uri = URI.create(url);
        if (!"https".equalsIgnoreCase(uri.getScheme()) && !url.contains("localhost") && !url.contains("127.0.0.1")) {
            throw new IllegalArgumentException(name + " must use HTTPS. Provided: " + url);
        }
        if (uri.getHost() == null || (!uri.getHost().equals("intuit.com") && !uri.getHost().endsWith(".intuit.com") && !uri.getHost().equals("localhost") && !uri.getHost().equals("127.0.0.1"))) {
            throw new IllegalArgumentException(name + " host must be an Intuit domain. Provided: " + url);
        }
    }

    protected String getValidatedAuthUrl(RunContext runContext) throws Exception {
        var rAuthUrl = runContext.render(authUrl).as(String.class).orElse(DEFAULT_AUTH_URL);
        validateUrl("authUrl", rAuthUrl);
        return rAuthUrl;
    }

    protected String getValidatedBaseUrl(RunContext runContext) throws Exception {
        var rBaseUrl = runContext.render(baseUrl).as(String.class).orElse(DEFAULT_BASE_URL);
        validateUrl("baseUrl", rBaseUrl);
        return rBaseUrl;
    }

    protected Integer getValidatedMinorVersion(RunContext runContext) throws Exception {
        var rMinorVersion = runContext.render(minorVersion).as(Integer.class).orElse(DEFAULT_MINOR_VERSION);
        if (rMinorVersion < 1) {
            throw new IllegalArgumentException("minorVersion must be positive. Provided: " + rMinorVersion);
        }
        return rMinorVersion;
    }

    protected String getAccessToken(RunContext runContext) throws Exception {
        var rRealmId = runContext.render(realmId).as(String.class)
            .orElseThrow(() -> new IllegalVariableEvaluationException("realmId is required"));
        if (!rRealmId.matches("^[0-9]+$")) {
            throw new IllegalArgumentException("realmId must be numeric");
        }

        var rAuthUrl = getValidatedAuthUrl(runContext);

        var rClientId = runContext.render(clientId).as(String.class)
            .orElseThrow(() -> new IllegalVariableEvaluationException("clientId is required"));
        var rClientSecret = runContext.render(clientSecret).as(String.class)
            .orElseThrow(() -> new IllegalVariableEvaluationException("clientSecret is required"));
        var rRefreshToken = runContext.render(refreshToken).as(String.class)
            .orElseThrow(() -> new IllegalVariableEvaluationException("refreshToken is required"));

        var seedHash = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(rRefreshToken.getBytes(StandardCharsets.UTF_8)));
        var clientIdHash = Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(rClientId.getBytes(StandardCharsets.UTF_8)));

        var kvStore = runContext.namespaceKv(runContext.flowInfo().namespace());
        var kvKey = "quickbooks_oauth_" + rRealmId + "_" + clientIdHash;

        var lock = REFRESH_LOCKS.computeIfAbsent(kvKey, k -> new java.util.concurrent.locks.ReentrantLock());
        lock.lock();
        try {
            var leaseKey = kvKey + "_lease";
            
            // Check if valid first so we don't grab lease unnecessarily
            var state = readState(runContext, kvStore, kvKey);
            if (state != null && seedHash.equals(state.getSeedHash()) && state.getExpiresAt() > Instant.now().getEpochSecond() + 60) {
                return state.getAccessToken();
            }

            if (kvStore.getValue(leaseKey).isPresent()) {
                throw new IllegalStateException("Another worker is currently rotating the QuickBooks token for this realm. Please configure task retries (e.g. 5 retries with 10s delay) to wait for the rotation to finish.");
            }
            
            kvStore.put(leaseKey, new KVValueAndMetadata(new io.kestra.core.storages.kv.KVMetadata(null, java.time.Duration.ofSeconds(30)), "locked"));
            try {
                return getOrRefreshToken(runContext, kvStore, kvKey, rClientId, rClientSecret, rRefreshToken, seedHash, rAuthUrl, false);
            } finally {
                kvStore.delete(leaseKey);
            }
        } finally {
            lock.unlock();
        }
    }


    private OAuthState readState(RunContext runContext, KVStore kvStore, String kvKey) throws Exception {
        var kvValue = kvStore.getValue(kvKey).map(val -> val.value().toString());
        if (kvValue.isPresent()) {
            String decrypted;
            try {
                decrypted = runContext.decrypt(kvValue.get());
            } catch (Exception e) {
                throw new IllegalStateException("Failed to decrypt KV store data. Please configure 'kestra.encryption.secret-key' in your Kestra configuration.", e);
            }
            try {
                return JacksonMapper.ofJson().readValue(decrypted, OAuthState.class);
            } catch (Exception e) {
                runContext.logger().warn("Failed to parse QuickBooks OAuth state from KV store for key: {}", kvKey);
                throw new IllegalStateException("Corrupted OAuth state in KV store. Please clear the KV store and re-authenticate.", e);
            }
        }
        return null;
    }

    TokenResponse performRefresh(RunContext runContext, String rAuthUrl, String rClientId, String rClientSecret, String activeRefreshToken) throws Exception {
        var authHeader = "Basic " + Base64.getEncoder().encodeToString((rClientId + ":" + rClientSecret).getBytes(StandardCharsets.UTF_8));
        var body = "grant_type=refresh_token&refresh_token=" + URLEncoder.encode(activeRefreshToken, StandardCharsets.UTF_8);

        try (var client = io.kestra.core.http.client.HttpClient.builder().runContext(runContext).build()) {
            var request = HttpRequest.builder()
                .uri(URI.create(rAuthUrl))
                .method("POST")
                .addHeader("Accept", "application/json")
                .addHeader("Content-Type", "application/x-www-form-urlencoded")
                .addHeader("Authorization", authHeader)
                .body(HttpRequest.StringRequestBody.builder()
                    .content(body)
                    .contentType("application/x-www-form-urlencoded")
                    .charset(StandardCharsets.UTF_8)
                    .build())
                .build();

            var response = client.request(request, String.class);

            if (response.getBody() == null || response.getBody().isEmpty()) {
                throw new IllegalStateException("Empty response body from Intuit OAuth2.");
            }

            TokenResponse tokenResponse;
            try {
                tokenResponse = JacksonMapper.ofJson().readValue(response.getBody(), TokenResponse.class);
            } catch (Exception e) {
                throw new IllegalStateException("Failed to parse Intuit OAuth2 response.");
            }

            if (tokenResponse.getExpiresIn() <= 0 || tokenResponse.getAccessToken() == null || tokenResponse.getAccessToken().isBlank()) {
                throw new IllegalStateException("Invalid token response received. expires_in must be > 0 and access_token must not be blank.");
            }
            return tokenResponse;
        }
    }

    private void persistState(RunContext runContext, KVStore kvStore, String kvKey, OAuthState newState, long xRefreshTokenExpiresIn) throws Exception {
        var metadata = new KVMetadata(null, xRefreshTokenExpiresIn > 0 ? Duration.ofSeconds(xRefreshTokenExpiresIn) : Duration.ofDays(100));
        String encrypted;
        try {
            encrypted = runContext.encrypt(JacksonMapper.ofJson().writeValueAsString(newState));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to encrypt KV store data. Please configure 'kestra.encryption.secret-key' in your Kestra configuration.", e);
        }

        try {
            kvStore.put(kvKey, new KVValueAndMetadata(metadata, encrypted));
        } catch (Exception e) {
            runContext.logger().warn("Failed to persist rotated QuickBooks tokens to KV store. The newly rotated refresh token is lost.");
            throw new IllegalStateException("Failed to save new refresh token. Your current refresh token may have been invalidated by Intuit. Please re-authenticate and provide a new refresh token.", e);
        }
    }

    private String getOrRefreshToken(RunContext runContext, KVStore kvStore, String kvKey, String rClientId, String rClientSecret, String rRefreshToken, String seedHash, String rAuthUrl, boolean isRetry) throws Exception {
        var activeRefreshToken = rRefreshToken;
        var state = readState(runContext, kvStore, kvKey);

        if (state != null && seedHash.equals(state.getSeedHash())) {
            if (state.getExpiresAt() > Instant.now().getEpochSecond() + 60) {
                return state.getAccessToken();
            }
            if (state.getRefreshToken() != null && !state.getRefreshToken().isEmpty()) {
                activeRefreshToken = state.getRefreshToken();
            }
        }

        boolean isSeedToken = activeRefreshToken.equals(rRefreshToken);
        if (isSeedToken && state != null) {
            runContext.logger().info("KV entry has expired and the seed token is used. The seed token may be stale.");
        }

        TokenResponse tokenResponse;
        try {
            tokenResponse = performRefresh(runContext, rAuthUrl, rClientId, rClientSecret, activeRefreshToken);
        } catch (IllegalStateException e) {
            throw e;
        } catch (HttpClientResponseException e) {
            String bodyStr = e.getMessage() != null ? e.getMessage() : "";
            if (!isRetry && e.getResponse().getStatus().getCode() == 400 && bodyStr.contains("invalid_grant")) {
                OAuthState freshState = readState(runContext, kvStore, kvKey);
                if (freshState != null && freshState.getRefreshToken() != null && !freshState.getRefreshToken().equals(activeRefreshToken)) {
                    return getOrRefreshToken(runContext, kvStore, kvKey, rClientId, rClientSecret, rRefreshToken, seedHash, rAuthUrl, true);
                }
            }
            String staleMsg = isSeedToken ? " Your seed refresh token may be stale." : "";
            if (e.getResponse().getStatus().getCode() == 400 || e.getResponse().getStatus().getCode() == 401) {
                throw new IllegalStateException("Intuit OAuth2 refresh failed with status " + e.getResponse().getStatus().getCode() + "." + staleMsg + " Please re-authenticate and provide a new refresh token.", e);
            } else {
                throw new IllegalStateException("Intuit OAuth2 refresh failed with transient status " + e.getResponse().getStatus().getCode() + ". Please retry later.", e);
            }
        } catch (Exception e) {
            String staleMsg = isSeedToken ? " Your seed refresh token may be stale." : "";
            throw new IllegalStateException("Intuit OAuth2 refresh failed." + staleMsg + " Please re-authenticate and provide a new refresh token.", e);
        }

        var newState = new OAuthState();
        newState.setAccessToken(tokenResponse.getAccessToken());
        newState.setRefreshToken(tokenResponse.getRefreshToken() != null && !tokenResponse.getRefreshToken().isEmpty() ? tokenResponse.getRefreshToken() : activeRefreshToken);
        newState.setExpiresAt(Instant.now().getEpochSecond() + tokenResponse.getExpiresIn());
        newState.setSeedHash(seedHash);

        persistState(runContext, kvStore, kvKey, newState, tokenResponse.getXRefreshTokenExpiresIn());
        return newState.getAccessToken();
    }

    @Data
    @NoArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    static class TokenResponse {
        @JsonProperty("access_token")
        @ToString.Exclude
        private String accessToken;
        @JsonProperty("refresh_token")
        @ToString.Exclude
        private String refreshToken;
        @JsonProperty("expires_in")
        private long expiresIn;
        @JsonProperty("x_refresh_token_expires_in")
        private long xRefreshTokenExpiresIn;
    }

    @Data
    @NoArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    static class OAuthState {
        @ToString.Exclude
        private String accessToken;
        @ToString.Exclude
        private String refreshToken;
        private long expiresAt;
        // Stored to invalidate the KV cache when the user updates the seed token property
        private String seedHash;
    }
}
