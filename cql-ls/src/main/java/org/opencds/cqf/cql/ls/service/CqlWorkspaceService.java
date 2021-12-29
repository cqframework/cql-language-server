package org.opencds.cqf.cql.ls.service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

import com.google.common.collect.ImmutableList;

import org.eclipse.lsp4j.DidChangeConfigurationParams;
import org.eclipse.lsp4j.DidChangeWatchedFilesParams;
import org.eclipse.lsp4j.DidChangeWatchedFilesRegistrationOptions;
import org.eclipse.lsp4j.DidChangeWorkspaceFoldersParams;
import org.eclipse.lsp4j.ExecuteCommandOptions;
import org.eclipse.lsp4j.ExecuteCommandParams;
import org.eclipse.lsp4j.FileSystemWatcher;
import org.eclipse.lsp4j.InitializeParams;
import org.eclipse.lsp4j.MessageParams;
import org.eclipse.lsp4j.MessageType;
import org.eclipse.lsp4j.Registration;
import org.eclipse.lsp4j.RegistrationParams;
import org.eclipse.lsp4j.ServerCapabilities;
import org.eclipse.lsp4j.Unregistration;
import org.eclipse.lsp4j.UnregistrationParams;
import org.eclipse.lsp4j.WorkspaceFolder;
import org.eclipse.lsp4j.WorkspaceFoldersOptions;
import org.eclipse.lsp4j.WorkspaceServerCapabilities;
import org.eclipse.lsp4j.services.LanguageClient;
import org.eclipse.lsp4j.services.WorkspaceService;
import org.greenrobot.eventbus.EventBus;
import org.opencds.cqf.cql.ls.Constants;
import org.opencds.cqf.cql.ls.FuturesHelper;
import org.opencds.cqf.cql.ls.event.DidChangeWatchedFilesEvent;
import org.opencds.cqf.cql.ls.plugin.CommandContribution;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CqlWorkspaceService implements WorkspaceService {
    private static final Logger Log = LoggerFactory.getLogger(CqlWorkspaceService.class);

    private static final List<String> basicWatchers = Arrays.asList(
            "**/cql-options.json",
            "ig.ini");

    private final CompletableFuture<LanguageClient> client;

    private Map<String, WorkspaceFolder> workspaceFolders = new HashMap<String, WorkspaceFolder>();
    private CompletableFuture<List<CommandContribution>> commandContributions;

    public CqlWorkspaceService(CompletableFuture<LanguageClient> client,
            CompletableFuture<List<CommandContribution>> commandContributions) {
        this.client = client;
        this.commandContributions = commandContributions;
    }

    @SuppressWarnings("deprecation")
    public void initialize(InitializeParams params, ServerCapabilities serverCapabilities) {

        List<WorkspaceFolder> workspaceFolders = new ArrayList<WorkspaceFolder>();
        if (params.getWorkspaceFolders() != null) {
            workspaceFolders.addAll(params.getWorkspaceFolders());
        }

        if (params.getRootUri() != null) {
            workspaceFolders.add(new WorkspaceFolder(params.getRootUri()));
        }

        this.addFolders(workspaceFolders);

        WorkspaceServerCapabilities wsc = new WorkspaceServerCapabilities();

        // Register for workspace change notifications
        WorkspaceFoldersOptions wfo = new WorkspaceFoldersOptions();
        wfo.setChangeNotifications(true);
        wsc.setWorkspaceFolders(wfo);

        // Register for file change notifications
        // FileOperationsServerCapabilities fosc = new
        // FileOperationsServerCapabilities();
        // wsc.setFileOperations(fosc);

        // Project symbol search
        // serverCapabilities.setWorkspaceSymbolProvider(true);

        // Set workspace capabilities
        serverCapabilities.setWorkspace(wsc);

        // Register commands
        serverCapabilities.setExecuteCommandProvider(new ExecuteCommandOptions(this.getSupportedCommands()));
    }

    public void initialized() {
        EventBus.getDefault().register(this);

        this.client.join()
                .unregisterCapability(new UnregistrationParams(
                        Arrays.asList(new Unregistration(Constants.WORKSPACE_DID_CHANGE_WATCHED_FILES_ID,
                                Constants.WORKSPACE_DID_CHANGE_WATCHED_FILES_METHOD))));


        List<FileSystemWatcher> watchers = basicWatchers.stream().map(x -> new FileSystemWatcher(x))
                .collect(Collectors.toList());
        DidChangeWatchedFilesRegistrationOptions dcfro = new DidChangeWatchedFilesRegistrationOptions(watchers);

        this.client.join()
                .registerCapability(new RegistrationParams(
                        Arrays.asList(new Registration(Constants.WORKSPACE_DID_CHANGE_WATCHED_FILES_ID,
                                Constants.WORKSPACE_DID_CHANGE_WATCHED_FILES_METHOD, dcfro))));

    }

    @Override
    public CompletableFuture<Object> executeCommand(ExecuteCommandParams params) {
        try {
            return this.executeCommandFromContributions(params);
        } catch (Exception e) {
            this.client.join().showMessage(
                    new MessageParams(MessageType.Error,
                            String.format("Command %s failed with: %s", params.getCommand(), e.getMessage())));
            return FuturesHelper.failedFuture(e);
        }
    }

    @Override
    public void didChangeWorkspaceFolders(DidChangeWorkspaceFoldersParams params) {
        try {
            this.addFolders(params.getEvent().getAdded());
            this.removeFolders(params.getEvent().getRemoved());
        } catch (Exception e) {
            Log.error("didChangeWorkspaceFolders: {}", e.getMessage());
        }
    }

    @Override
    public void didChangeConfiguration(DidChangeConfigurationParams params) {
    }

    @Override
    public void didChangeWatchedFiles(DidChangeWatchedFilesParams params) {
        EventBus.getDefault().post(new DidChangeWatchedFilesEvent(params));
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

    protected CompletableFuture<Object> executeCommandFromContributions(ExecuteCommandParams params) {
        String command = params.getCommand();

        for (CommandContribution commandContribution : this.commandContributions.join()) {
            if (commandContribution.getCommands().contains(command)) {
                return commandContribution.executeCommand(params);
            }
        }

        this.client.join()
                .showMessage(new MessageParams(MessageType.Error, String.format("Unknown Command %s", command)));
        return CompletableFuture.completedFuture(null);
    }

    public List<String> getSupportedCommands() {
        Set<String> commands = new HashSet<>();
        for (CommandContribution commandContribution : this.commandContributions.join()) {
            if (commandContribution.getCommands() != null) {
                for (String command : commandContribution.getCommands()) {
                    if (commands.contains(command)) {
                        throw new IllegalArgumentException(
                                String.format("The command %s was contributed multiple times", command));
                    }

                    commands.add(command);
                }
            }
        }

        return ImmutableList.copyOf(commands);
    }

    public void stop() {
        EventBus.getDefault().unregister(this);
    }
}
