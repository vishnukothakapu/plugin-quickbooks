package io.kestra.plugin.quickbooks.companyinfo;

import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.runners.RunContext;
import io.kestra.core.http.HttpRequest;
import io.kestra.core.http.client.HttpClient;
import io.kestra.core.http.client.HttpClientResponseException;
import io.kestra.plugin.quickbooks.AbstractQuickBooksConnection;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;

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
            title = "Get QuickBooks Company Info",
            full = true,
            code = """
                id: qbo_company_info
                namespace: company.finance
                tasks:
                  - id: get_company_info
                    type: io.kestra.plugin.quickbooks.companyinfo.Get
                    clientId: "{{ secret('QBO_CLIENT_ID') }}"
                    clientSecret: "{{ secret('QUICKBOOKS_CLIENT_SECRET') }}"
                    refreshToken: "{{ secret('QUICKBOOKS_REFRESH_TOKEN') }}"
                    realmId: "{{ secret('QBO_REALM_ID') }}"
                """
        )
    }
)
@Schema(
    title = "Get QuickBooks Company Information",
    description = "Retrieves the company info for the given Realm ID."
)
public class Get extends AbstractQuickBooksConnection implements RunnableTask<Get.Output> {

    @Override
    public Output run(RunContext runContext) throws Exception {
        String accessToken = this.getAccessToken(runContext);
        String realmId = runContext.render(this.getRealmId()).as(String.class).orElseThrow(() -> new io.kestra.core.exceptions.IllegalVariableEvaluationException("Variable evaluation failed"));
        String baseUrl = this.getValidatedBaseUrl(runContext);
        Integer minorVersion = this.getValidatedMinorVersion(runContext);

        String endpoint = baseUrl + "/v3/company/" + realmId + "/companyinfo/" + realmId + "?minorversion=" + minorVersion;

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
            Map<String, Object> companyInfo = (Map<String, Object>) responseMap.get("CompanyInfo");

            return Output.builder()
                .row(companyInfo)
                .build();
        }
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(
            title = "Company Info properties"
        )
        private final Map<String, Object> row;
    }
}
