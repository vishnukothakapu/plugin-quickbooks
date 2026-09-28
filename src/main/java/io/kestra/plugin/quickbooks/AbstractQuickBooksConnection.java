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
import jakarta.validation.constraints.NotNull;

import java.net.URI;
import io.kestra.core.http.client.HttpClient;
import io.kestra.core.http.HttpRequest;
import io.kestra.core.http.HttpResponse;
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
        description = "A valid OAuth2 refresh token. Kestra will automatically rotate and persist it in the KV Store."
    )
    @PluginProperty(secret = true, group = "connection")
    @NotNull
    @ToString.Exclude
    protected Property<String> refreshToken;

    @Schema(
        title = "QuickBooks Realm ID",
        description = "The Realm ID (Company ID) for the QuickBooks account"
    )
    @PluginProperty(secret = true, group = "connection")
    @NotNull
    @ToString.Exclude
    protected Property<String> realmId;

    @Schema(
        title = "Base URL",
        description = "The Base URL for QuickBooks API. Defaults to production.",
        defaultValue = "https://quickbooks.api.intuit.com"
    )
    @PluginProperty(group = "connection")
    @Builder.Default
    protected Property<String> baseUrl = Property.of("https://quickbooks.api.intuit.com");

    @Schema(
        title = "Minor Version",
        description = "The minor version of the QuickBooks API to use.",
        defaultValue = "75"
    )
    @PluginProperty(group = "connection")
    @Builder.Default
    protected Property<String> minorversion = Property.of("75");

    protected String getAccessToken(RunContext runContext) throws Exception {
        String realm = runContext.render(realmId).as(String.class)
            .orElseThrow(() -> new IllegalArgumentException("realmId is required"));
        String currentClientId = runContext.render(clientId).as(String.class)
            .orElseThrow(() -> new IllegalArgumentException("clientId is required"));
        String currentClientSecret = runContext.render(clientSecret).as(String.class)
            .orElseThrow(() -> new IllegalArgumentException("clientSecret is required"));
        String defaultRefreshToken = runContext.render(refreshToken).as(String.class)
            .orElseThrow(() -> new IllegalArgumentException("refreshToken is required"));

        KVStore kvStore = runContext.namespaceKv(runContext.flowInfo().namespace());
        String kvKey = "quickbooks_oauth_" + realm;

        HttpClient client = HttpClient.builder().runContext(runContext).build();

        // Check if we have a valid token in KV store
        Optional<String> kvValue = kvStore.getValue(kvKey).map(val -> val.value().toString());
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

        HttpRequest request = HttpRequest.builder()
            .uri(URI.create("https://oauth.platform.intuit.com/oauth2/v1/tokens/bearer"))
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

        HttpResponse<String> response = client.request(request, String.class);

        if (response.getStatus().getCode() >= 300) {
            throw new IllegalStateException("Intuit OAuth2 refresh failed with status " + response.getStatus().getCode() + " " + response.getBody() + ". Please re-authenticate and provide a new refresh token.");
        }

        TokenResponse tokenResponse = JacksonMapper.ofJson().readValue(response.getBody(), TokenResponse.class);

        OAuthState newState = new OAuthState();
        newState.setAccessToken(tokenResponse.getAccess_token());
        newState.setRefreshToken(tokenResponse.getRefresh_token());
        newState.setExpiresAt(Instant.now().getEpochSecond() + tokenResponse.getExpires_in());

        // Save to KV store. If this fails, the exception will propagate and fail the task loudly
        // to prevent data corruption per security guidelines.
        kvStore.put(kvKey, new io.kestra.core.storages.kv.KVValueAndMetadata(
            (io.kestra.core.storages.kv.KVMetadata) null,
            JacksonMapper.ofJson().writeValueAsString(newState)
        ));

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
