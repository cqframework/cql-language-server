package org.opencds.cqf.cql.ls.server.config;

import java.util.ArrayList;
import java.util.List;
import java.util.ServiceLoader;
import java.util.concurrent.CompletableFuture;
import org.eclipse.lsp4j.services.LanguageClient;
import org.opencds.cqf.cql.ls.server.command.DebugCqlCommandContribution;
import org.opencds.cqf.cql.ls.server.command.ViewElmCommandContribution;
import org.opencds.cqf.cql.ls.server.manager.CqlCompilationManager;
import org.opencds.cqf.cql.ls.server.manager.IgContextManager;
import org.opencds.cqf.cql.ls.server.plugin.CommandContribution;
import org.opencds.cqf.cql.ls.server.plugin.CqlLanguageServerPlugin;
import org.opencds.cqf.cql.ls.server.plugin.CqlLanguageServerPluginFactory;
import org.opencds.cqf.cql.ls.server.service.CqlTextDocumentService;
import org.opencds.cqf.cql.ls.server.service.CqlWorkspaceService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class PluginConfig {

    // The indirection here is to break the cycle between the workspace service and the plugin
    // contributions
    @Bean
    CompletableFuture<List<CommandContribution>> futureCommandContributions() {
        return new CompletableFuture<>();
    }

    @Bean
    public List<CommandContribution> pluginCommandContributions(
            CompletableFuture<LanguageClient> client,
            CqlWorkspaceService cqlWorkspaceService,
            CqlTextDocumentService cqlTextDocumentService,
            CqlCompilationManager cqlCompilationManager,
            IgContextManager igContextManager,
            CompletableFuture<List<CommandContribution>> futureCommandContributions) {

        ServiceLoader<CqlLanguageServerPluginFactory> pluginFactories =
                ServiceLoader.load(CqlLanguageServerPluginFactory.class);

        List<CommandContribution> pluginCommandContributions = new ArrayList<>();

        for (CqlLanguageServerPluginFactory pluginFactory : pluginFactories) {
            CqlLanguageServerPlugin plugin = pluginFactory.createPlugin(
                    client, cqlWorkspaceService, cqlTextDocumentService, cqlCompilationManager);
            if (plugin.getCommandContribution() != null) {
                pluginCommandContributions.add(plugin.getCommandContribution());
            }
        }

        pluginCommandContributions.add(new ViewElmCommandContribution(cqlCompilationManager));
        pluginCommandContributions.add(new DebugCqlCommandContribution(igContextManager));

        futureCommandContributions.complete(pluginCommandContributions);

        return pluginCommandContributions;
    }
}
