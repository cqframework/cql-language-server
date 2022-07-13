package org.opencds.cqf.cql.ls.plugin.debug;

import java.util.concurrent.CompletableFuture;

import org.eclipse.lsp4j.services.LanguageClient;
import org.eclipse.lsp4j.services.TextDocumentService;
import org.eclipse.lsp4j.services.WorkspaceService;
import org.opencds.cqf.cql.ls.server.manager.CqlTranslationManager;
import org.opencds.cqf.cql.ls.server.plugin.CommandContribution;
import org.opencds.cqf.cql.ls.server.plugin.CqlLanguageServerPlugin;

public class DebugPlugin implements CqlLanguageServerPlugin {

    @Override
    public String getName() {
        return "org.opencds.cqf.cql.ls.plugin.debug.DebugPlugin";
    }
    // private CompletableFuture<LanguageClient> client;
    // private WorkspaceService workspaceService;
    // private TextDocumentService textDocumentService;
    private CqlTranslationManager translationManager;
    private CommandContribution commandContribution;


    public DebugPlugin(CompletableFuture<LanguageClient> client, WorkspaceService workspaceService,
    TextDocumentService textDocumentService, CqlTranslationManager translationManager) {
        // this.client = client;
        // this.workspaceService = workspaceService;
        // this.textDocumentService = textDocumentService;
        this.translationManager = translationManager;

        this.commandContribution = new DebugCommandContribution(this.translationManager);
    }

    @Override
    public CommandContribution getCommandContribution() {
        return this.commandContribution;
    }
}
