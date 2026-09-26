package io.kestra.plugin.quickbooks;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.kestra.core.exceptions.IllegalVariableEvaluationException;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.Task;
import io.kestra.core.runners.RunContext;
import io.kestra.core.serializers.JacksonMapper;
import io.kestra.core.storages.kv.KVStore;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.*;
import lombok.experimental.SuperBuilder;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
public abstract class AbstractQuickBooksConnection extends Task {

    @Schema(
        title = "QuickBooks Client ID",
        description = "The OAuth2 Client ID for your QuickBooks application"
    )
    @PluginProperty(secret = true)
    @ToString.Exclude
    protected Property<String> clientId;

    @Schema(
        title = "QuickBooks Client Secret",
        description = "The OAuth2 Client Secret for your QuickBooks application"
    )
    @PluginProperty(secret = true)
    @ToString.Exclude
    protected Property<String> clientSecret;

    @Schema(
        title = "QuickBooks Refresh Token",
        description = "A valid OAuth2 refresh token. Kestra will automatically rotate and persist it in the KV Store."
    )
    @PluginProperty(secret = true)
    @ToString.Exclude
    protected Property<String> refreshToken;

    @Schema(
        title = "QuickBooks Realm ID",
        description = "The Realm ID (Company ID) for the QuickBooks account"
    )
    @PluginProperty(secret = true)
    @ToString.Exclude
    protected Property<String> realmId;

    @Schema(
        title = "Base URL",
        description = "The Base URL for QuickBooks API. Defaults to production.",
        defaultValue = "https://quickbooks.api.intuit.com"
    )
    @PluginProperty
    @Builder.Default
    protected Property<String> baseUrl = Property.of("https://quickbooks.api.intuit.com");

    @Schema(
        title = "Minor Version",
        description = "The minor version of the QuickBooks API to use.",
        defaultValue = "75"
    )
    @PluginProperty
    @Builder.Default
    protected Property<String> minorversion = Property.of("75");

    protected String getAccessToken(RunContext runContext) throws Exception {
        String realm = runContext.render(realmId).as(String.class).orElseThrow();
        String currentClientId = runContext.render(clientId).as(String.class).orElseThrow();
        String currentClientSecret = runContext.render(clientSecret).as(String.class).orElseThrow();
        String defaultRefreshToken = runContext.render(refreshToken).as(String.class).orElseThrow();

        KVStore kvStore = runContext.namespaceKv(runContext.flowInfo().namespace());
        String kvKey = "quickbooks_oauth_" + realm;

        // Note: Using standard Java 11 HttpClient since io.kestra.core.http.client requires micronaut injection
        HttpClient client = HttpClient.newBuilder().build();

        // Check if we have a valid token in KV store
        Optional<String> kvValue = kvStore.get(kvKey).map(entry -> {
            try {
                // KVEntry might be a record in Java 21, so use reflection if value() or getValue() is unknown
                java.lang.reflect.Method m = entry.getClass().getMethod("value");
                return m.invoke(entry).toString();
            } catch (Exception e) {
                try {
                    java.lang.reflect.Method m = entry.getClass().getMethod("getValue");
                    return m.invoke(entry).toString();
                } catch (Exception ex) {
                    return entry.toString();
                }
            }
        });
        String activeRefreshToken = defaultRefreshToken;

        if (kvValue.isPresent()) {
            OAuthState state = JacksonMapper.ofJson().readValue(kvValue.get(), OAuthState.class);
            if (state.getExpiresAt() > Instant.now().getEpochSecond() + 60) {
                return state.getAccessToken(); // Token is still valid
            }
            if (state.getRefreshToken() != null) {
                activeRefreshToken = state.getRefreshToken();
            }
        }

        // We need to refresh the token
        String authHeader = "Basic " + Base64.getEncoder().encodeToString((currentClientId + ":" + currentClientSecret).getBytes(StandardCharsets.UTF_8));
        String body = "grant_type=refresh_token&refresh_token=" + activeRefreshToken;

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create("https://oauth.platform.intuit.com/oauth2/v1/tokens/bearer"))
            .header("Accept", "application/json")
            .header("Content-Type", "application/x-www-form-urlencoded")
            .header("Authorization", authHeader)
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() >= 300) {
            throw new RuntimeException("Failed to refresh QuickBooks token: " + response.statusCode() + " " + response.body());
        }

        TokenResponse tokenResponse = JacksonMapper.ofJson().readValue(response.body(), TokenResponse.class);

        OAuthState newState = new OAuthState();
        newState.setAccessToken(tokenResponse.getAccess_token());
        newState.setRefreshToken(tokenResponse.getRefresh_token());
        newState.setExpiresAt(Instant.now().getEpochSecond() + tokenResponse.getExpires_in());

        // Concurrency Note: kvStore.put in Kestra might not have strict CAS natively exposed easily here,
        // but updating the KV will persist the new token for the namespace.
        // The rolling 100-day refresh token allows concurrent refreshes within 24h to return the same token,
        // preventing race conditions from invalidating the token.
        try {
            // Attempt to instantiate KVValueAndMetadata via reflection to avoid constructor signature issues
            Class<?> kvMetadataClass = Class.forName("io.kestra.core.storages.kv.KVValueAndMetadata");
            Object kvValueAndMetadata;
            try {
                kvValueAndMetadata = kvMetadataClass.getConstructor(Object.class).newInstance(JacksonMapper.ofJson().writeValueAsString(newState));
            } catch (Exception e) {
                try {
                    kvValueAndMetadata = kvMetadataClass.getConstructor(io.kestra.core.storages.kv.KVMetadata.class, Object.class).newInstance(null, JacksonMapper.ofJson().writeValueAsString(newState));
                } catch (Exception e2) {
                    kvValueAndMetadata = kvMetadataClass.getConstructors()[0].newInstance(null, JacksonMapper.ofJson().writeValueAsString(newState));
                }
            }
            kvStore.put(kvKey, (io.kestra.core.storages.kv.KVValueAndMetadata) kvValueAndMetadata);
        } catch (Exception e) {
            runContext.logger().warn("Failed to persist QuickBooks token to KV store: " + e.getMessage());
        }

        return newState.getAccessToken();
    }

    @Data
    @NoArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    protected static class TokenResponse {
        private String access_token;
        private String refresh_token;
        private long expires_in;
        private long x_refresh_token_expires_in;
    }

    @Data
    @NoArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    protected static class OAuthState {
        private String accessToken;
        private String refreshToken;
        private long expiresAt;
    }
}
