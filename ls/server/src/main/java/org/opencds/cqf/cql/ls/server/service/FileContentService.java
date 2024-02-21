package org.opencds.cqf.cql.ls.server.service;

import static com.google.common.base.Preconditions.checkNotNull;

import java.io.File;
import java.net.URI;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.filefilter.IOFileFilter;
import org.apache.commons.io.filefilter.TrueFileFilter;
import org.apache.commons.lang3.tuple.Pair;
import org.cqframework.cql.cql2elm.model.Version;
import org.eclipse.lsp4j.WorkspaceFolder;
import org.hl7.elm.r1.VersionedIdentifier;
import org.opencds.cqf.cql.ls.core.ContentService;
import org.opencds.cqf.cql.ls.core.utility.Uris;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

// NOTE: This implementation is naive and assumes library file names will always take the form:
// <filename>[-<version>].cql
// And further that <version> will always be of the form <major>[.<minor>[.<patch>]]
// Usage outside these boundaries will result in errors or incorrect behavior.
public class FileContentService implements ContentService {
    private static final Logger log = LoggerFactory.getLogger(FileContentService.class);

    protected final List<WorkspaceFolder> workspaceFolders;

    public FileContentService(List<WorkspaceFolder> workspaceFolders) {
        this.workspaceFolders = workspaceFolders;
    }

    @Override
    public Set<URI> locate(URI root, VersionedIdentifier identifier) {
        checkNotNull(root);
        checkNotNull(identifier);

        Set<URI> uris = new HashSet<>();

        // This just checks to see if the requested
        // location URI is part of the workspace.
        // If not, no locations are returned.
        for (WorkspaceFolder w : this.workspaceFolders) {
            URI folderUri = Uris.parseOrNull(w.getUri());
            // If root is not a is a child of the workspace folder, skip it.
            if (folderUri == null || folderUri.relativize(root).equals(root)) {
                continue;
            }

            File file = searchFolder(root, identifier);
            if (file != null && file.exists()) {
                uris.add(file.toURI());
            }
        }

        return uris;
    }

    public static File searchFolder(URI directory, VersionedIdentifier libraryIdentifier) {
        Path path;
        try {
            path = Paths.get(directory);
        } catch (Exception e) {
            log.warn(String.format("error searching directory %s. Skipping.", directory), e);
            return null;
        }

        // First, try a direct match
        String libraryName = libraryIdentifier.getId();
        Path libraryPath = path.resolve(String.format(
                "%s%s.cql",
                libraryName, libraryIdentifier.getVersion() != null ? ("-" + libraryIdentifier.getVersion()) : ""));
        File libraryFile = libraryPath.toFile();

        if (libraryFile.exists()) {
            return libraryFile;
        } else {
            return nearestMatch(path, libraryIdentifier.getId(), libraryIdentifier.getVersion());
        }
    }

    private static IOFileFilter ioFilter(String name) {
        return new IOFileFilter() {

            @Override
            public boolean accept(File dir, String filename) {
                return filename.startsWith(name) && filename.endsWith(".cql");
            }

            @Override
            public boolean accept(File file) {
                if (file.isFile()) {
                    return this.accept(file, file.getName());
                } else {
                    return false;
                }
            }
        };
    }

    private static File nearestMatch(Path directory, String name, String version) {
        Collection<File> files = FileUtils.listFiles(directory.toFile(), ioFilter(name), TrueFileFilter.INSTANCE);
        if (files == null || files.isEmpty()) {
            return null;
        }

        File mostRecentFile = null;
        Version mostRecent = null;
        Version requestedVersion = version == null ? null : new Version(version);

        // The filter will give us all the files that start with the appropriate the
        // appropriate name. We then need to
        // split apart the name and version to do a more detailed comparison
        // e.g. for a request for patient-view version 1.0.0 we might see:
        // patient-view.cql
        // patient-view-demo.cql
        // patient-view-1.0.1.cql
        // patient-view-1.0.0.cql
        // patient-view-demo-1.0.0.cql

        for (File file : files) {
            String fileName = file.getName();
            Pair<String, Version> nameAndVersion = getNameAndVersion(fileName);

            if (!nameAndVersion.getLeft().equalsIgnoreCase(name)) {
                continue;
            }

            Version v = nameAndVersion.getRight();

            // Exact match
            if (v != null && requestedVersion != null && v.compareTo(requestedVersion) == 0) {
                return file;
            }
            // If the file is named correctly but has no version, consider it the most
            // recent version
            else if (v == null) {
                return file;
            }
            // Otherwise, find the most recent compatible version
            else if ((requestedVersion == null || v.compatibleWith(requestedVersion))
                    && (mostRecent == null || v.compareTo(mostRecent) > 0)) {
                mostRecent = v;
                mostRecentFile = file;
            }
        }

        return mostRecentFile;
    }

    private static Version getVersion(String version) {
        try {
            return new Version(version);
        } catch (Exception e) {
            return null;
        }
    }

    private static Pair<String, Version> getNameAndVersion(String fileName) {
        int indexOfExtension = fileName.lastIndexOf(".");
        if (indexOfExtension >= 0) {
            fileName = fileName.substring(0, indexOfExtension);
        }

        int indexOfVersionSeparator = fileName.lastIndexOf("-");
        Version version = null;
        if (indexOfVersionSeparator >= 0) {
            version = getVersion(fileName.substring(indexOfVersionSeparator + 1));
            if (version != null) {
                fileName = fileName.substring(0, indexOfVersionSeparator);
            }
        }

        return Pair.of(fileName, version);
    }
}
