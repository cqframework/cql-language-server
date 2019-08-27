package org.cqframework.cql.provider;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collection;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.filefilter.IOFileFilter;
import org.apache.commons.lang3.tuple.Pair;
import org.cqframework.cql.cql2elm.LibrarySourceProvider;
import org.cqframework.cql.cql2elm.model.Version;
import org.hl7.elm.r1.VersionedIdentifier;

// NOTE: This implementation is naive and assumes library file names will always take the form:
// <filename>[-<version>].cql
// And further that <version> will always be of the form <major>[.<minor>[.<patch>]]
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
            // The filter will give us all the files that start with the appropriate the appropriate name. We then need to
            // split apart the name and version to do a more detailed comparison
            // e.g. for a request for patient-view version 1.0.0 we might see:
            // patient-view.cql
            // patient-view-demo.cql
            // patient-view-1.0.1.cql
            // patient-view-1.0.0.cql 
            // patient-view-demo-1.0.0.cql
            if (files != null) {
                for (File file: files) {
                    String fileName = file.getName();
                    Pair<String,Version> nameAndVersion = this.getNameAndVersion(fileName);

                    if (!nameAndVersion.getLeft().equalsIgnoreCase(libraryName)) {
                        continue;
                    }

                    Version version = nameAndVersion.getRight();

                    // Exact match
                    if (version != null && requestedVersion != null && version.compareTo(requestedVersion) == 0) {
                        return file;
                    }
                     // If the file is named correctly but has no version, consider it the most recent version
                    else if (version == null) {
                        return file;
                    }
                    // Otherwise, find the most recent compatible version
                    else if (requestedVersion == null || version.compatibleWith(requestedVersion)) {
                        if (mostRecent == null || version.compareTo(mostRecent) > 0) {
                            mostRecent = version;
                            mostRecentFile = file;
                        }
                    }
                }
            }

            libraryFile = mostRecentFile;
        }

        return libraryFile;
    }

    private Version getVersion(String version) {
        try {
            return new Version(version);
        }
        catch (Exception e) {
            return null;
        }
    }

    private Pair<String,Version> getNameAndVersion(String fileName) {
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