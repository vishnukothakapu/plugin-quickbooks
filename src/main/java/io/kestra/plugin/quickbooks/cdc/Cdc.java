package io.kestra.plugin.quickbooks.cdc;

import io.kestra.core.exceptions.IllegalVariableEvaluationException;
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
import io.kestra.core.http.HttpRequest;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;

import jakarta.validation.constraints.NotNull;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Plugin(
    examples = {
        @Example(
            title = "Get changed Invoice and Customer records since a specific date",
            full = true,
            code = """
                id: quickbooks_cdc
                namespace: company.team
                tasks:
                  - id: get_cdc
                    type: io.kestra.plugin.quickbooks.cdc.Cdc
                    clientId: "{`{ secret('QBO_CLIENT_ID') }`}"
                    clientSecret: "{{ secret('QUICKBOOKS_CLIENT_SECRET') }}"
                    refreshToken: "{{ secret('QUICKBOOKS_REFRESH_TOKEN') }}"
                    realmId: "{`{ secret('QBO_REALM_ID') }`}"
                    entities:
                      - Invoice
                      - Customer
                    changedSince: "2023-09-01T12:00:00Z"
                """
        )
    }
)
@Schema(
    title = "Retrieve changed records via Change Data Capture (CDC)",
    description = "Returns a list of entities that have changed since a given timestamp."
)
public class Cdc extends AbstractQuickBooksConnection implements RunnableTask<Cdc.Output> {

    @Schema(
        title = "Entities",
        description = "A list of QuickBooks entities to check for changes (e.g., 'Invoice', 'Customer', 'Payment')."
    )
    @PluginProperty(dynamic = true, group = "main")
    @NotNull
    private Property<List<String>> entities;

    @Schema(
        title = "Changed Since",
        description = "The timestamp to start fetching changes from (ISO-8601 format)."
    )
    @PluginProperty(dynamic = true, group = "main")
    @NotNull
    private Property<String> changedSince;

    @Override
    public Output run(RunContext runContext) throws Exception {
        String accessToken = this.getAccessToken(runContext);
        String rRealmId = runContext.render(this.getRealmId()).as(String.class).orElseThrow(() -> new io.kestra.core.exceptions.IllegalVariableEvaluationException("Variable evaluation failed"));
        String baseUrl = this.getValidatedBaseUrl(runContext);
        Integer minorVersion = this.getValidatedMinorVersion(runContext);
        
        List<String> rEntities = runContext.render(this.entities).asList(String.class);
        if (rEntities.isEmpty()) {
            throw new IllegalArgumentException("entities must not be empty");
        }
        String entitiesParam = String.join(",", rEntities);

        String rChangedSince = runContext.render(this.changedSince).as(String.class).orElseThrow(() -> new IllegalVariableEvaluationException("changedSince is required"));
        
        // Ensure standard formatting (QuickBooks expects something like 2012-07-20T22:25:51-07:00)
        ZonedDateTime zdt = ZonedDateTime.parse(rChangedSince);
        String formattedChangedSince = zdt.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);

        String endpoint = baseUrl + "/v3/company/" + rRealmId + "/cdc?minorversion=" + minorVersion +
            "&entities=" + URLEncoder.encode(entitiesParam, StandardCharsets.UTF_8) +
            "&changedSince=" + URLEncoder.encode(formattedChangedSince, StandardCharsets.UTF_8);

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
                .cdcResponse(responseMap)
                .build();
        }
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(
            title = "CDC Response",
            description = "The parsed JSON response containing the CDC results."
        )
        private final Map<String, Object> cdcResponse;
    }
}
