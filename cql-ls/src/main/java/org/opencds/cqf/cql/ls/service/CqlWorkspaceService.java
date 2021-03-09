package org.opencds.cqf.cql.ls.service;

import java.io.ByteArrayOutputStream;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.net.URI;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

import com.google.common.collect.ImmutableList;
import com.google.gson.JsonElement;

import org.cqframework.cql.cql2elm.CqlTranslator;
import org.eclipse.lsp4j.DidChangeConfigurationParams;
import org.eclipse.lsp4j.DidChangeWatchedFilesParams;
import org.eclipse.lsp4j.DidChangeWorkspaceFoldersParams;
import org.eclipse.lsp4j.ExecuteCommandParams;
import org.eclipse.lsp4j.MessageParams;
import org.eclipse.lsp4j.MessageType;
import org.eclipse.lsp4j.WorkspaceFolder;
import org.eclipse.lsp4j.services.LanguageClient;
import org.eclipse.lsp4j.services.WorkspaceService;
import org.opencds.cqf.cql.evaluator.cli.Main;
import org.opencds.cqf.cql.ls.CqlLanguageServer;
import org.opencds.cqf.cql.ls.FuturesHelper;
import org.opencds.cqf.cql.ls.plugin.CommandContribution;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CqlWorkspaceService implements WorkspaceService {
    private static final Logger Log = LoggerFactory.getLogger(CqlTextDocumentService.class);


    private final CompletableFuture<LanguageClient> client;
    private final CqlLanguageServer server;

    private static final String VIEW_ELM_COMMAND = "org.opencds.cqf.cql.ls.viewElm";

    // TODO: Delete once the plugin is fully supported
    public static final String START_DEBUG_COMMAND = "org.opencds.cqf.cql.ls.plugin.debug.startDebugSession";

    private Map<String,WorkspaceFolder> workspaceFolders = new HashMap<String, WorkspaceFolder>();
    private CompletableFuture<List<CommandContribution>> commandContributions;
    
    
    public CqlWorkspaceService(CompletableFuture<LanguageClient> client, CqlLanguageServer server, CompletableFuture<List<CommandContribution>> commandContributions) {
        this.client = client;
        this.server = server;
        this.commandContributions = commandContributions;
    }

    public void initialize(List<WorkspaceFolder> folders) {
        this.addFolders(folders);
    }

    @Override
    public CompletableFuture<Object> executeCommand(ExecuteCommandParams params) {
        try {
            String command = params.getCommand();
            switch (command) {
                case VIEW_ELM_COMMAND:
                    return this.viewElm(params);
                case START_DEBUG_COMMAND:
                    return this.executeCql(params);
                default:
                    return this.executeCommandFromContributions(params);
            }
        }
        catch (Exception e) {
            Log.error("executeCommand: {}", e.getMessage());
            return FuturesHelper.failedFuture(e);
        }
	}

    @Override
	public void didChangeWorkspaceFolders(DidChangeWorkspaceFoldersParams params) {
        try {
            this.addFolders(params.getEvent().getAdded());
            this.removeFolders(params.getEvent().getRemoved());
        }
        catch (Exception e) {
            Log.error("didChangeWorkspaceFolders: {}", e.getMessage());
        }
    }

    @Override
    public void didChangeConfiguration(DidChangeConfigurationParams params) {
    }

    @Override
    public void didChangeWatchedFiles(DidChangeWatchedFilesParams params) {
    }

    public Collection<WorkspaceFolder> getWorkspaceFolders() {
        return this.workspaceFolders.values();
    }

    private void addFolders(List<WorkspaceFolder> folders) {
        for (WorkspaceFolder f : folders) {
            workspaceFolders.putIfAbsent(f.getUri(), f);
        }
    }

    private void removeFolders(List<WorkspaceFolder> folders) {
        for (WorkspaceFolder f : folders) {
            if (workspaceFolders.containsKey(f.getUri())) {
                workspaceFolders.remove(f.getUri());
            }
        }
    }


    // There's currently not a "show text file" or similar command in the LSP spec,
    // So it's not client agnostic. The client has to know that the result of this command
    // is XML and display it accordingly.
    private CompletableFuture<Object> viewElm(ExecuteCommandParams params) {
        try {
            String uriString = ((JsonElement)params.getArguments().get(0)).getAsString();
            URI uri = new URI(uriString);
            Optional<String> content = ((CqlTextDocumentService)this.server.getTextDocumentService()).activeContent(uri);
            if (content.isPresent()) {
                CqlTranslator translator = this.server.getTranslationManager().translate(uri, content.get());
                return CompletableFuture.completedFuture(translator.toXml());
            }

            return null;
        }
        catch(Exception e) {
            this.client.join().showMessage(new MessageParams(MessageType.Error, String.format("View ELM failed with: {}", e.getMessage())));
            Log.error("viewElm: {}", e.getMessage());
            return FuturesHelper.failedFuture(e);
        }
    }

    // TODO: To be moved to the debug plugin once fully baked.
    private CompletableFuture<Object> executeCql(ExecuteCommandParams params) {
        try {
            List<String> arguments = params.getArguments().stream().map(x -> (JsonElement)x).map(x -> x.getAsString()).collect(Collectors.toList());

            // Temporarily redirect std out, because uh... I didn't do that very smart.
            ByteArrayOutputStream baosOut = new ByteArrayOutputStream();
            System.setOut(new PrintStream(baosOut));

            ByteArrayOutputStream baosErr = new ByteArrayOutputStream();
            System.setErr(new PrintStream(baosErr));

            Main.run(arguments.toArray(new String[arguments.size()]));

            String out = baosOut.toString();
            String err = baosErr.toString();

            if (err.length() > 0) {
                out += "\nThe following errors were encountered during evaluation:\n";
                out += err;
            }

            return CompletableFuture.completedFuture(out);
        }
        catch(Exception e) {
            this.client.join().showMessage(new MessageParams(MessageType.Error, String.format("Execute CQL failed with: {}", e.getMessage())));
            Log.error("startDebugSession / executeCql: {}", e.getMessage());
            return FuturesHelper.failedFuture(e);
        }
        finally {
            System.setOut(new PrintStream(new FileOutputStream(FileDescriptor.out)));
            System.setErr(new PrintStream(new FileOutputStream(FileDescriptor.err)));
        }   
    }

    protected Collection<String> getLanguageServerCommands() {
        return Collections.singletonList(VIEW_ELM_COMMAND);
    }

    protected CompletableFuture<Object> executeCommandFromContributions(ExecuteCommandParams params) {
        String command = params.getCommand();

        for (CommandContribution commandContribution : this.commandContributions.join()) {
            if (commandContribution.getCommands().contains(command)) {
                return commandContribution.executeCommand(params);
            }
        }

        this.client.join().showMessage(new MessageParams(MessageType.Error, String.format("Unknown Command {}", command)));
        return CompletableFuture.completedFuture(null);
    }

    public List<String> getSupportedCommands() {
        Set<String> commands = new HashSet<>();

        commands.addAll(this.getLanguageServerCommands());

        for (CommandContribution commandContribution : this.commandContributions.join()) {
            if (commandContribution.getCommands() != null) {
                for (String command : commandContribution.getCommands()) {
                    if (commands.contains(command)) {
                        throw new IllegalArgumentException(String.format("The command %s was contributed multiple times", command));
                    }

                    commands.add(command);
                }
            }
        }

        return ImmutableList.copyOf(commands);
    }
}

