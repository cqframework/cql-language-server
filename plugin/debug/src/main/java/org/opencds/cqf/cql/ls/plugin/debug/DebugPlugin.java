package org.opencds.cqf.cql.ls.plugin.debug;

import org.eclipse.lsp4j.services.LanguageClient;
import org.eclipse.lsp4j.services.TextDocumentService;
import org.eclipse.lsp4j.services.WorkspaceService;
import org.opencds.cqf.cql.ls.server.manager.CqlCompilationManager;
import org.opencds.cqf.cql.ls.server.plugin.CommandContribution;
import org.opencds.cqf.cql.ls.server.plugin.CqlLanguageServerPlugin;

import java.util.concurrent.CompletableFuture;

@SuppressWarnings("unused") // TODO: Remove once this class is completed
public class DebugPlugin implements CqlLanguageServerPlugin {

    @Override
    public String getName() {
        return "org.opencds.cqf.cql.ls.plugin.debug.DebugPlugin";
    }


    private CompletableFuture<LanguageClient> client;
    private WorkspaceService workspaceService;
    private TextDocumentService textDocumentService;

    private CqlCompilationManager compilationManager;
    private CommandContribution commandContribution;


    public DebugPlugin(CompletableFuture<LanguageClient> client, WorkspaceService workspaceService,
            TextDocumentService textDocumentService, CqlCompilationManager compilationManager) {
        this.client = client;
        this.workspaceService = workspaceService;
        this.textDocumentService = textDocumentService;
        this.compilationManager = compilationManager;

        this.commandContribution = new DebugCommandContribution(this.compilationManager);
    }

    @Override
    public CommandContribution getCommandContribution() {
        return this.commandContribution;
    }
}
