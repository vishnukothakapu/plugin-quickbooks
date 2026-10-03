package io.kestra.plugin.quickbooks.query;

import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.models.tasks.common.FetchOutput;
import io.kestra.core.models.tasks.common.FetchType;
import io.kestra.core.runners.RunContext;
import io.kestra.core.serializers.FileSerde;
import io.kestra.core.serializers.JacksonMapper;
import io.kestra.core.http.HttpRequest;
import io.kestra.core.http.client.HttpClient;
import io.kestra.core.http.client.HttpClientResponseException;
import io.kestra.plugin.quickbooks.AbstractQuickBooksConnection;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;

import java.io.File;
import java.io.FileOutputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static io.kestra.core.utils.Rethrow.throwConsumer;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Plugin(
    examples = {
        @Example(
            title = "Query all open invoices with a positive balance",
            full = true,
            code = """
                id: qbo_open_invoices
                namespace: company.finance
                tasks:
                  - id: open_invoices
                    type: io.kestra.plugin.quickbooks.query.Query
                    clientId: "{{ secret('QBO_CLIENT_ID') }}"
                    clientSecret: "{{ secret('QUICKBOOKS_CLIENT_SECRET') }}"
                    refreshToken: "{{ secret('QUICKBOOKS_REFRESH_TOKEN') }}"
                    realmId: "{{ secret('QBO_REALM_ID') }}"
                    query: "select * from Invoice where Balance > '0'"
                    fetchType: STORE
                """
        )
    }
)
@Schema(
    title = "Execute a query using QuickBooks Online SQL-like Query Language",
    description = "Query QBO entities like Invoice, Customer, Payment, Bill, Vendor, Item, Account. Supports STARTPOSITION and MAXRESULTS pagination automatically if fetchType is STORE or FETCH."
)
public class Query extends AbstractQuickBooksConnection implements RunnableTask<FetchOutput> {

    @Schema(
        title = "The QBO Query Language statement",
        description = "Example: select * from Invoice where Balance > '0'"
    )
    @PluginProperty(dynamic = true, group = "main")
    @NotNull
    protected Property<String> query;

    @Schema(
        title = "The way to consume the results",
        description = "FETCH stores results in memory (useful for small datasets). STORE saves them as an internal storage file (mandatory for large datasets).",
        defaultValue = "STORE"
    )
    @PluginProperty(group = "main")
    @Builder.Default
    private Property<FetchType> fetchType = Property.ofValue(FetchType.STORE);

    @Override
    public FetchOutput run(RunContext runContext) throws Exception {
        String renderedQuery = runContext.render(this.query).as(String.class).orElseThrow(() -> new io.kestra.core.exceptions.IllegalVariableEvaluationException("Variable evaluation failed"));
        FetchType type = runContext.render(this.fetchType).as(FetchType.class).orElse(FetchType.STORE);
        String accessToken = this.getAccessToken(runContext);
        String realmId = runContext.render(this.getRealmId()).as(String.class).orElseThrow(() -> new io.kestra.core.exceptions.IllegalVariableEvaluationException("Variable evaluation failed"));
        String baseUrl = this.getValidatedBaseUrl(runContext);
        Integer minorVersion = this.getValidatedMinorVersion(runContext);

        // Strip existing pagination if provided
        renderedQuery = renderedQuery.replaceAll("(?i)\\s+(startposition|maxresults)\\s+\\d+", "");

        List<Object> rows = new ArrayList<>();
        File tempFile = null;
        FileOutputStream fileOutputStream = null;

        if (type == FetchType.STORE) {
            tempFile = runContext.workingDir().createTempFile(".ion").toFile();
            fileOutputStream = new FileOutputStream(tempFile);
        }

        int startPosition = 1;
        int maxResults = 1000;
        int totalRows = 0;
        boolean hasMore = true;

        try (var client = io.kestra.core.http.client.HttpClient.builder().runContext(runContext).build()) {
            while (hasMore) {
                String pagedQuery = renderedQuery + " STARTPOSITION " + startPosition + " MAXRESULTS " + maxResults;
                String uriStr = baseUrl + "/v3/company/" + realmId + "/query?minorversion=" + minorVersion + "&query=" + URLEncoder.encode(pagedQuery, StandardCharsets.UTF_8);

                HttpRequest request = HttpRequest.builder()
                    .uri(URI.create(uriStr))
                    .method("GET")
                    .addHeader("Accept", "application/json")
                    .addHeader("Authorization", "Bearer " + accessToken)
                    .build();

                String responseBody = null;
                int maxRetries = 5;
                int attempt = 0;
                while (attempt < maxRetries) {
                    try {
                        responseBody = client.request(request, String.class).getBody();
                        break;
                    } catch (HttpClientResponseException e) {
                        if (e.getResponse().getStatus().getCode() == 429) {
                            attempt++;
                            if (attempt >= maxRetries) {
                                throw new RuntimeException("Rate limit exceeded and max retries reached.", e);
                            }
                            // Exponential backoff
                            try {
                                Thread.sleep((long) (Math.pow(2, attempt) * 1000));
                            } catch (InterruptedException ie) {
                                Thread.currentThread().interrupt();
                                throw new RuntimeException("Thread interrupted during backoff", ie);
                            }
                        } else {
                            throw new RuntimeException("QuickBooks API request failed: " + e.getMessage(), e);
                        }
                    }
                }

                Map<String, Object> responseMap = JacksonMapper.ofJson().readValue(responseBody, Map.class);
                Map<String, Object> queryResponse = (Map<String, Object>) responseMap.get("QueryResponse");

                if (queryResponse == null || queryResponse.isEmpty()) {
                    break;
                }

                boolean foundData = false;
                for (Map.Entry<String, Object> entry : queryResponse.entrySet()) {
                    if (!entry.getKey().equals("startPosition") && !entry.getKey().equals("maxResults") && !entry.getKey().equals("totalCount")) {
                        if (entry.getValue() instanceof List<?> entityList) {
                            foundData = true;
                            totalRows += entityList.size();
                            for (Object row : entityList) {
                                if (type == FetchType.STORE) {
                                    FileSerde.write(fileOutputStream, row);
                                } else if (type == FetchType.FETCH || type == FetchType.FETCH_ONE) {
                                    rows.add(row);
                                }
                            }
                            if (entityList.size() < maxResults) {
                                hasMore = false;
                            }
                        }
                    }
                }

                if (!foundData) {
                    hasMore = false;
                }

                if (type == FetchType.FETCH_ONE && totalRows > 0) {
                    hasMore = false;
                }

                startPosition += maxResults;
            }
        } finally {
            if (fileOutputStream != null) {
                fileOutputStream.close();
            }
        }

        FetchOutput.FetchOutputBuilder builder = FetchOutput.builder().size((long) totalRows);

        if (type == FetchType.STORE) {
            builder.uri(runContext.storage().putFile(tempFile));
        } else if (type == FetchType.FETCH) {
            builder.rows((List) rows);
        } else if (type == FetchType.FETCH_ONE) {
            builder.row(!rows.isEmpty() ? (Map) rows.get(0) : null);
        }

        return builder.build();
    }
}
