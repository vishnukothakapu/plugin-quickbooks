package io.kestra.plugin.quickbooks.bills;

import io.kestra.core.http.HttpRequest;
import io.kestra.core.http.client.HttpClient;
import io.kestra.core.http.client.HttpClientResponseException;
import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.runners.RunContext;
import io.kestra.plugin.quickbooks.AbstractQuickBooksConnection;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;

import jakarta.validation.constraints.NotNull;
import java.net.URI;
import java.util.Map;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Plugin(
    examples = {
        @Example(
            title = "Get a Bill by ID",
            full = true,
            code = """
                id: get_bills
                namespace: company.team
                tasks:
                  - id: get
                    type: io.kestra.plugin.quickbooks.bills.Get
                    clientId: "{`{ secret('QBO_CLIENT_ID') }`}"
                    clientSecret: "{{ secret('QUICKBOOKS_CLIENT_SECRET') }}"
                    refreshToken: "{{ secret('QUICKBOOKS_REFRESH_TOKEN') }}"
                    realmId: "{`{ secret('QBO_REALM_ID') }`}"
                    entityId: "123"
                """
        )
    }
)
@Schema(title = "Get a Bill by ID", description = "Get a Bill by ID")
public class Get extends AbstractQuickBooksConnection implements RunnableTask<Get.Output> {

    @Schema(
        title = "The ID of the Bill to fetch"
    )
    @PluginProperty(dynamic = true, group = "main")
    @NotNull
    private Property<String> entityId;

    @Override
    public Output run(RunContext runContext) throws Exception {
        String accessToken = this.getAccessToken(runContext);
        String rRealmId = runContext.render(this.getRealmId()).as(String.class).orElseThrow(() -> new io.kestra.core.exceptions.IllegalVariableEvaluationException("Variable evaluation failed"));
        String baseUrl = this.getValidatedBaseUrl(runContext);
        Integer minorVersion = this.getValidatedMinorVersion(runContext);
        
        String rEntityId = runContext.render(this.entityId).as(String.class).orElseThrow(() -> new io.kestra.core.exceptions.IllegalVariableEvaluationException("Variable evaluation failed"));

        String endpoint = baseUrl + "/v3/company/" + rRealmId + "/bill/" + java.net.URLEncoder.encode(rEntityId, java.nio.charset.StandardCharsets.UTF_8) + "?minorversion=" + minorVersion;

        try (HttpClient client = io.kestra.core.http.client.HttpClient.builder().runContext(runContext).build()) {
            HttpRequest request = HttpRequest.builder()
                .uri(URI.create(endpoint))
                .method("GET")
                .addHeader("Authorization", "Bearer " + accessToken)
                .addHeader("Accept", "application/json")
                .build();

            String responseBody = null;
            try {
                responseBody = client.request(request, String.class).getBody();
            } catch (HttpClientResponseException e) {
                if (e.getResponse().getStatus().getCode() == 429) {
                    throw new RuntimeException("Rate limit exceeded", e);
                }
                throw e;
            }

            Map<String, Object> responseMap = io.kestra.core.serializers.JacksonMapper.ofJson().readValue(responseBody, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
            
            return Output.builder()
                .row(responseMap)
                .build();
        }
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(
            title = "The fetched Bill"
        )
        private final Map<String, Object> row;
    }
}
