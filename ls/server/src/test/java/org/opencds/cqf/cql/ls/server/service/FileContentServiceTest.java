package org.opencds.cqf.cql.ls.server.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.InputStream;
import java.net.URI;
import java.util.Collections;
import java.util.Set;
import org.eclipse.lsp4j.WorkspaceFolder;
import org.hl7.elm.r1.VersionedIdentifier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileContentServiceTest {

    @TempDir
    File tempDir;

    private VersionedIdentifier id(String name, String version) {
        return new VersionedIdentifier().withId(name).withVersion(version);
    }

    private VersionedIdentifier id(String name) {
        return new VersionedIdentifier().withId(name);
    }

    // -----------------------------------------------------------------------
    // searchFolder (static)
    // -----------------------------------------------------------------------

    @Test
    void searchFolder_exactVersionedMatch_returnsFile() throws Exception {
        File cqlFile = new File(tempDir, "FHIRHelpers-4.0.1.cql");
        cqlFile.createNewFile();

        File result = FileContentService.searchFolder(tempDir.toURI(), id("FHIRHelpers", "4.0.1"));

        assertNotNull(result);
        assertEquals(cqlFile.getCanonicalPath(), result.getCanonicalPath());
    }

    @Test
    void searchFolder_unversionedFilename_treatedAsNearestMatch() throws Exception {
        // A file named "FHIRHelpers.cql" (no version suffix) is returned when
        // the versioned file does not exist, as it is treated as the most-recent version.
        File cqlFile = new File(tempDir, "FHIRHelpers.cql");
        cqlFile.createNewFile();

        File result = FileContentService.searchFolder(tempDir.toURI(), id("FHIRHelpers", "4.0.1"));

        assertNotNull(result);
        assertEquals(cqlFile.getCanonicalPath(), result.getCanonicalPath());
    }

    @Test
    void searchFolder_noMatch_returnsNull() {
        File result = FileContentService.searchFolder(tempDir.toURI(), id("NonExistent", "1.0.0"));
        assertNull(result);
    }

    @Test
    void searchFolder_compatibleVersion_returnsNearest() throws Exception {
        // 4.0.1 is compatible with a request for 4.0.0 (same major/minor, higher patch).
        new File(tempDir, "FHIRHelpers-4.0.1.cql").createNewFile();

        File result = FileContentService.searchFolder(tempDir.toURI(), id("FHIRHelpers", "4.0.0"));

        assertNotNull(result);
    }

    @Test
    void searchFolder_noMatchingPrefix_returnsNull() throws Exception {
        // "SomeOtherLib.cql" does not start with "MyLib", so the filter never returns it.
        new File(tempDir, "SomeOtherLib.cql").createNewFile();

        File result = FileContentService.searchFolder(tempDir.toURI(), id("MyLib", "1.0.0"));

        assertNull(result);
    }

    // -----------------------------------------------------------------------
    // read(URI)
    // -----------------------------------------------------------------------

    @Test
    void read_existingFile_returnsStream() throws Exception {
        File cqlFile = new File(tempDir, "Test.cql");
        cqlFile.createNewFile();

        FileContentService svc = new FileContentService(Collections.emptyList());
        InputStream stream = svc.read(cqlFile.toURI());

        assertNotNull(stream);
        stream.close();
    }

    @Test
    void read_nonExistentFile_returnsNull() {
        FileContentService svc = new FileContentService(Collections.emptyList());
        URI uri = new File(tempDir, "does-not-exist.cql").toURI();

        assertNull(svc.read(uri));
    }

    // -----------------------------------------------------------------------
    // locate(URI, VersionedIdentifier)
    // -----------------------------------------------------------------------

    @Test
    void locate_rootWithinWorkspace_returnsUri() throws Exception {
        new File(tempDir, "One.cql").createNewFile();

        WorkspaceFolder folder = new WorkspaceFolder();
        folder.setUri(tempDir.toURI().toString());
        FileContentService svc = new FileContentService(Collections.singletonList(folder));

        Set<URI> result = svc.locate(tempDir.toURI(), id("One"));

        assertNotNull(result);
        assertTrue(result.size() >= 1);
    }

    @Test
    void locate_rootOutsideWorkspace_returnsEmpty() throws Exception {
        File workspace = new File(tempDir, "workspace");
        workspace.mkdirs();
        new File(workspace, "One.cql").createNewFile();

        // workspace folder is the subdirectory; root is the parent tempDir
        WorkspaceFolder folder = new WorkspaceFolder();
        folder.setUri(workspace.toURI().toString());
        FileContentService svc = new FileContentService(Collections.singletonList(folder));

        // root = tempDir, which is NOT inside the workspace subfolder
        Set<URI> result = svc.locate(tempDir.toURI(), id("One"));

        assertTrue(result.isEmpty());
    }
}
