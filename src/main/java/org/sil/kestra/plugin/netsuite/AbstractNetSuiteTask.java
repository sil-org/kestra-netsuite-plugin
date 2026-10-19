package org.sil.kestra.plugin.netsuite;

import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.Task;
import io.kestra.core.runners.RunContext;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;

/** Connection properties shared by every NetSuite task: account and Token-Based Auth credentials. */
@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
public abstract class AbstractNetSuiteTask extends Task {
    @Schema(
        title = "NetSuite account ID",
        description = "For example `1234567` or `1234567_SB1` for a sandbox. Used as the OAuth realm and, " +
            "lowercased with `_` replaced by `-`, as the API hostname."
    )
    @PluginProperty(group = "connection")
    @NotNull
    protected Property<String> realm;

    @Schema(title = "Integration consumer key")
    @PluginProperty(secret = true, group = "connection")
    @NotNull
    protected Property<String> consumerKey;

    @Schema(title = "Integration consumer secret")
    @PluginProperty(secret = true, group = "connection")
    @NotNull
    protected Property<String> consumerSecret;

    @Schema(title = "Access token ID")
    @PluginProperty(secret = true, group = "connection")
    @NotNull
    protected Property<String> tokenId;

    @Schema(title = "Access token secret")
    @PluginProperty(secret = true, group = "connection")
    @NotNull
    protected Property<String> tokenSecret;

    @Schema(
        title = "Override the REST API base URL",
        description = "Defaults to `https://{realm}.suitetalk.api.netsuite.com`. Mainly useful for testing."
    )
    @PluginProperty(group = "advanced")
    protected Property<String> baseUrl;

    protected NetSuiteClient client(RunContext runContext) throws Exception {
        String rRealm = runContext.render(realm).as(String.class).orElseThrow();
        String rBaseUrl = runContext.render(baseUrl).as(String.class)
            .orElse("https://" + rRealm.toLowerCase().replace('_', '-') + ".suitetalk.api.netsuite.com");

        NetSuiteOAuth1 auth = new NetSuiteOAuth1(
            rRealm,
            runContext.render(consumerKey).as(String.class).orElseThrow(),
            runContext.render(consumerSecret).as(String.class).orElseThrow(),
            runContext.render(tokenId).as(String.class).orElseThrow(),
            runContext.render(tokenSecret).as(String.class).orElseThrow()
        );
        return new NetSuiteClient(rBaseUrl, auth, runContext.logger());
    }
}
