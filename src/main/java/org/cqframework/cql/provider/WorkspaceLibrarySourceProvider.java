package org.cqframework.cql.provider;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.filefilter.DirectoryFileFilter;
import org.apache.commons.io.filefilter.IOFileFilter;
import org.cqframework.cql.cql2elm.LibrarySourceProvider;
import org.cqframework.cql.cql2elm.model.Version;
import org.cqframework.cql.service.CqlWorkspaceService;
import org.eclipse.lsp4j.WorkspaceFolder;
import org.hl7.elm.r1.VersionedIdentifier;

import java.io.*;
import java.net.URI;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collection;
import java.util.logging.Level;
import java.util.logging.Logger;

// NOTE: This implementation is naive and assumes library file names will always take the form:
// <filename>[-<version>].cql
// And further that <filename> will never contain dashes, and that <version> will always be of the form <major>[.<minor>[.<patch>]]
// Usage outside these boundaries will result in errors or incorrect behavior.
public class WorkspaceLibrarySourceProvider implements LibrarySourceProvider {
    private static final Logger LOG = Logger.getLogger("main");

    private final URI baseUri;

    public WorkspaceLibrarySourceProvider(URI baseUri) {
        this.baseUri = baseUri;
    }

    @Override
    public InputStream getLibrarySource(VersionedIdentifier libraryIdentifier) {
        if (this.baseUri.getScheme().startsWith(("http"))) {
            return null;

        }

        File file = this.searchPath(this.baseUri, libraryIdentifier);
        if (file != null && file.exists()) {
            try {
                return new FileInputStream(file);
            } catch (FileNotFoundException e) {
                LOG.log(Level.INFO,
                        String.format("WorkspaceProvider attempted to load source for library %s but failed with: "
                                + e.getMessage()),
                        libraryIdentifier.getId());
            }

        }

        return null;
    }

    private File searchPath(URI baseUri, VersionedIdentifier libraryIdentifier) {
        Path path;
        try {
            path = Paths.get(baseUri);
        }
        catch (Exception e) {
            return null;
        }

        String libraryName = libraryIdentifier.getId();
        Path libraryPath = path.resolve(String.format("%s%s.cql", libraryName,
                libraryIdentifier.getVersion() != null ? ("-" + libraryIdentifier.getVersion()) : ""));
        File libraryFile = libraryPath.toFile();

        if (!libraryFile.exists()) {
            IOFileFilter filter = new IOFileFilter(){
            
                @Override
                public boolean accept(File dir, String name) {
                    return name.startsWith(libraryName) && name.endsWith(".cql");
                }

                @Override
                public boolean accept(File file) {
                    if (file.isFile()) {
                        return this.accept(file, file.getName());
                    }
                    else {
                        return false;
                    }
                }
            };

            File mostRecentFile = null;
            Version mostRecent = null;
            Version requestedVersion = libraryIdentifier.getVersion() == null ? null : new Version(libraryIdentifier.getVersion());
            Collection<File> files = FileUtils.listFiles(path.toFile(), filter, null);
            if (files != null) {
                for (File file: files) {
                    String fileName = file.getName();
                    int indexOfExtension = fileName.lastIndexOf(".");
                    if (indexOfExtension >= 0) {
                        fileName = fileName.substring(0, indexOfExtension);
                    }


                    int indexOfVersionSeparator = fileName.lastIndexOf("-");
                    if (indexOfVersionSeparator >= 0) {
                        Version version;
                        try {
                            version = new Version(fileName.substring(indexOfVersionSeparator + 1));
                              // If there's an exact match short circuit.
                            if (requestedVersion != null && version.compareTo(requestedVersion) == 0) {
                                return file;
                            }
                            // If the file has a version, make sure it is compatible with the version we are looking for
                            else if (requestedVersion == null || version.compatibleWith(requestedVersion)) {
                                if (mostRecent == null || version.compareTo(mostRecent) > 0) {
                                    mostRecent = version;
                                    mostRecentFile = file;
                                }
                            }
                        }
                        // Sometimes a file ends with a name like patient-view, so just spliting on - isn't
                        // a reliable way to ensure we actually have a version;
                        catch (IllegalArgumentException e) {
                            if (mostRecent == null) {
                                mostRecentFile = file;
                            }
                        }
                    }
                    else {
                        // If the file is named correctly, but has no version, consider it the most recent version
                        if (mostRecent == null) {
                            mostRecentFile = file;
                        }
                    }
                }
            }

            libraryFile = mostRecentFile;
        }

        return libraryFile;
    }
}