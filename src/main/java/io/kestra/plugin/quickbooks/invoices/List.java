package io.kestra.plugin.quickbooks.invoices;

import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Property;
import io.kestra.core.runners.RunContext;
import io.kestra.plugin.quickbooks.query.Query;
import io.swagger.v3.oas.annotations.media.Schema;
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
@Plugin(
    examples = {
        @Example(
            title = "List Invoice records",
            full = true,
            code = """
                id: list_invoices
                namespace: company.team
                tasks:
                  - id: list
                    type: io.kestra.plugin.quickbooks.invoices.List
                    clientId: "{`{ secret('QBO_CLIENT_ID') }`}"
                    clientSecret: "{{ secret('QUICKBOOKS_CLIENT_SECRET') }}"
                    refreshToken: "{{ secret('QUICKBOOKS_REFRESH_TOKEN') }}"
                    realmId: "{`{ secret('QBO_REALM_ID') }`}"
                    fetchType: FETCH
                """
        )
    }
)
@Schema(title = "List Invoice records", description = "List Invoice records")
public class List extends Query {

    @Schema(
        title = "Optional WHERE clause to filter Invoice records",
        description = "Example: `Balance > '0'`"
    )
    @PluginProperty(dynamic = true, group = "main")
    private Property<String> where;

    @Override
    public io.kestra.core.models.tasks.common.FetchOutput run(RunContext runContext) throws Exception {
        String rWhere = this.where != null ? runContext.render(this.where).as(String.class).orElse(null) : null;
        String queryStr = "select * from Invoice";
        if (rWhere != null && !rWhere.isBlank()) {
            queryStr += " where " + rWhere;
        }
        
        // Temporarily set the query property of the parent Query class
        this.query = Property.ofValue(queryStr);
        
        return super.run(runContext);
    }
}
