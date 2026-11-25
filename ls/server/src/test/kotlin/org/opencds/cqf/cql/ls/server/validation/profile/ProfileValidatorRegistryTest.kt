package org.opencds.cqf.cql.ls.server.validation.profile

import org.cqframework.fhir.utilities.IGContext
import org.cqframework.fhir.utilities.LoggerAdapter
import org.eclipse.lsp4j.DidChangeWatchedFilesParams
import org.eclipse.lsp4j.FileChangeType
import org.eclipse.lsp4j.FileEvent
import org.hl7.elm.r1.VersionedIdentifier
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.opencds.cqf.cql.ls.core.ContentService
import org.opencds.cqf.cql.ls.server.event.DidChangeWatchedFilesEvent
import org.opencds.cqf.cql.ls.server.manager.IgContextManager
import org.opencds.cqf.cql.ls.server.validation.testcase.TestCaseValidator
import org.opencds.cqf.cql.ls.server.validation.testcase.ValidationIssue
import org.slf4j.LoggerFactory
import java.io.InputStream
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.TimeUnit

/** Queues builds so a test decides when they run. */
private class ManualExecutor : AbstractExecutorService() {
    val queue = ArrayDeque<Runnable>()

    fun runAll() {
        while (queue.isNotEmpty()) queue.removeFirst().run()
    }

    override fun execute(command: Runnable) {
        queue.addLast(command)
    }

    override fun shutdown() = Unit

    override fun shutdownNow(): MutableList<Runnable> = mutableListOf()

    override fun isShutdown() = false

    override fun isTerminated() = false

    override fun awaitTermination(
        timeout: Long,
        unit: TimeUnit,
    ) = true
}

private class FakeIgInfo(var deps: List<String> = emptyList()) : ProfileIgInfo {
    var gen = 0L

    override fun dependsOn(igRoot: Path) = deps

    override fun generation(igRoot: Path) = gen
}

private class FakeValidator(val spec: ProfileEngineSpec) : ResourceProfileValidator {
    override fun validate(
        files: List<Path>,
        index: CaseResourceIndex,
        mode: EngineMode,
        checkReferences: Boolean,
    ) = emptyList<ValidationIssue>()
}

class ProfileValidatorRegistryTest {
    private val igInfo = FakeIgInfo(listOf("hl7.terminology.r4#6.5.0", "hl7.fhir.us.cqfmeasures#5.0.0"))
    private val executor = ManualExecutor()
    private val built = mutableListOf<ProfileEngineSpec>()
    private var failWith: String? = null
    private val registry =
        ProfileValidatorRegistry(
            igInfo,
            builder = { spec ->
                built.add(spec)
                failWith?.let { throw IllegalStateException(it) }
                FakeValidator(spec)
            },
            executor = executor,
        )

    private fun ig(
        tempDir: Path,
        name: String,
    ): Path {
        val root = tempDir.resolve(name)
        Files.createDirectories(root.resolve("input/tests/measure/M/case-1"))
        Files.writeString(root.resolve("ig.ini"), "[IG]\nig = input/ig.xml\n")
        return root
    }

    @Test
    fun `warming then ready, never blocking, and one build per key`(
        @TempDir tempDir: Path,
    ) {
        val root = ig(tempDir, "ig1")
        val case = root.resolve("input/tests/measure/M/case-1")

        val first = registry.acquire(case, emptyList(), EngineMode.QUICK)
        assertEquals(ProfileEngineState.WARMING, first.state)
        assertNull(first.validator)
        assertEquals(ProfileEngineState.WARMING, registry.acquire(case, emptyList(), EngineMode.FULL).state)
        assertEquals(1, executor.queue.size, "A second request while warming must not start another build")

        executor.runAll()
        val ready = registry.acquire(case, emptyList(), EngineMode.FULL)
        assertEquals(ProfileEngineState.READY, ready.state)
        assertNotNull(ready.validator)
        assertSame(ready.validator, registry.acquire(root, emptyList(), EngineMode.QUICK).validator, "One engine serves both modes")
        assertEquals(1, built.size)
        assertEquals(root.toAbsolutePath().normalize(), built.single().igRoot)
    }

    @Test
    fun `packages are dependsOn plus configured ones, and the key includes the sorted set`(
        @TempDir tempDir: Path,
    ) {
        val root = ig(tempDir, "ig1")
        registry.acquire(root, listOf(" hl7.fhir.us.qicore#6.0.0", "fhir.cqf.common#4.0.1", ""), EngineMode.QUICK)
        executor.runAll()
        assertEquals(
            // dependsOn first, then the configured packages in key (sorted) order
            listOf("hl7.terminology.r4#6.5.0", "hl7.fhir.us.cqfmeasures#5.0.0", "fhir.cqf.common#4.0.1", "hl7.fhir.us.qicore#6.0.0"),
            built.single().packages,
        )
        // Same set in another order: same engine
        assertEquals(
            ProfileEngineState.READY,
            registry.acquire(root, listOf("fhir.cqf.common#4.0.1", "hl7.fhir.us.qicore#6.0.0"), EngineMode.QUICK).state,
        )
        // Changed list: new key, new build
        assertEquals(ProfileEngineState.WARMING, registry.acquire(root, listOf("hl7.fhir.us.qicore#6.0.0"), EngineMode.QUICK).state)
    }

    @Test
    fun `mergePackages keeps one version per id, configured wins, r4 core dropped`() {
        assertEquals(
            listOf("a#2", "b#1"),
            ProfileValidatorRegistry.mergePackages(listOf("a#1", "hl7.fhir.r4.core#4.0.1"), listOf("b#1", "a#2", " ")),
        )
    }

    @Test
    fun `generation bump invalidates and rebuilds`(
        @TempDir tempDir: Path,
    ) {
        val root = ig(tempDir, "ig1")
        registry.acquire(root, emptyList(), EngineMode.QUICK)
        executor.runAll()
        assertEquals(ProfileEngineState.READY, registry.acquire(root, emptyList(), EngineMode.QUICK).state)

        igInfo.gen++
        igInfo.deps = listOf("hl7.terminology.r4#6.5.0")
        assertEquals(ProfileEngineState.WARMING, registry.acquire(root, emptyList(), EngineMode.QUICK).state)
        executor.runAll()
        assertEquals(ProfileEngineState.READY, registry.acquire(root, emptyList(), EngineMode.QUICK).state)
        assertEquals(2, built.size)
        assertEquals(listOf("hl7.terminology.r4#6.5.0"), built.last().packages)
    }

    @Test
    fun `least recently used key is evicted beyond two`(
        @TempDir tempDir: Path,
    ) {
        val roots = (1..3).map { ig(tempDir, "ig$it") }
        registry.acquire(roots[0], emptyList(), EngineMode.QUICK)
        registry.acquire(roots[1], emptyList(), EngineMode.QUICK)
        executor.runAll()
        registry.acquire(roots[0], emptyList(), EngineMode.QUICK) // roots[1] is now least recently used
        registry.acquire(roots[2], emptyList(), EngineMode.QUICK)

        assertEquals(ProfileEngineState.READY, registry.state(registry.keyFor(roots[0].toAbsolutePath(), emptyList())))
        assertEquals(ProfileEngineState.NOT_STARTED, registry.state(registry.keyFor(roots[1].toAbsolutePath(), emptyList())))
        assertEquals(ProfileEngineState.WARMING, registry.state(registry.keyFor(roots[2].toAbsolutePath(), emptyList())))
    }

    @Test
    fun `two package sets of one IG count as two keys`(
        @TempDir tempDir: Path,
    ) {
        val root = ig(tempDir, "ig1")
        registry.acquire(root, listOf("a#1"), EngineMode.QUICK)
        registry.acquire(root, listOf("b#1"), EngineMode.QUICK)
        registry.acquire(root, listOf("c#1"), EngineMode.QUICK)
        assertEquals(ProfileEngineState.NOT_STARTED, registry.state(registry.keyFor(root.toAbsolutePath(), listOf("a#1"))))
        assertEquals(ProfileEngineState.WARMING, registry.state(registry.keyFor(root.toAbsolutePath(), listOf("b#1"))))
    }

    @Test
    fun `build failure sets FAILED with a message and does not retry`(
        @TempDir tempDir: Path,
    ) {
        val root = ig(tempDir, "ig1")
        failWith = "Unable to find package hl7.fhir.us.qicore#9.9.9 {big server map}"
        registry.acquire(root, emptyList(), EngineMode.QUICK)
        executor.runAll()
        val failed = registry.acquire(root, emptyList(), EngineMode.QUICK)
        assertEquals(ProfileEngineState.FAILED, failed.state)
        assertEquals("Profile validation unavailable: Unable to find package hl7.fhir.us.qicore#9.9.9", failed.message)
        assertTrue(executor.queue.isEmpty())
    }

    @Test
    fun `path outside any IG fails without building`(
        @TempDir tempDir: Path,
    ) {
        val lookup = registry.acquire(tempDir, emptyList(), EngineMode.QUICK)
        assertEquals(ProfileEngineState.FAILED, lookup.state)
        assertTrue(lookup.message!!.contains("No ig.ini"))
        assertTrue(executor.queue.isEmpty())
    }

    @Test
    fun `prewarm starts the build and never hands out a validator`(
        @TempDir tempDir: Path,
    ) {
        val root = ig(tempDir, "ig1")
        assertEquals(ProfileEngineState.WARMING, registry.prewarm(root.resolve("input/tests"), emptyList()).state)
        executor.runAll()
        val lookup = registry.prewarm(root, emptyList())
        assertEquals(ProfileEngineState.READY, lookup.state)
        assertNull(lookup.validator)
    }
}

class ProfileValidationPathsTest {
    // | Platform           | IG root          | vocabulary                      | measures                                 |
    // | macOS/Linux        | /work/ig         | /work/ig/input/vocabulary       | /work/ig/input/resources/measure         |
    // | Windows backslash  | C:\work\ig       | C:\work\ig\input\vocabulary     | C:\work\ig\input\resources\measure       |
    // | Windows slash      | C:/work/ig       | C:\work\ig\input\vocabulary     | C:\work\ig\input\resources\measure       |
    // On a POSIX JVM the Windows forms are single relative names, so the assertion is built with the
    // same Paths API to stay true on every platform.
    @Test
    fun `vocabulary and measure folders resolve under the IG root on every path form`() {
        for (root in listOf("/work/ig", "C:\\work\\ig", "C:/work/ig", "file:///work/ig")) {
            val igRoot = TestCaseValidator.normalizePath(root)
            assertEquals(igRoot.resolve("input").resolve("vocabulary"), ProfileValidationPaths.vocabularyDir(igRoot), root)
            assertEquals(igRoot.resolve("input").resolve("resources").resolve("measure"), ProfileValidationPaths.measureDir(igRoot), root)
            assertTrue(ProfileValidationPaths.vocabularyDir(igRoot).endsWith(Paths.get("input", "vocabulary")), root)
            assertEquals(igRoot, ProfileValidationPaths.measureDir(igRoot).parent.parent.parent, root)
        }
    }

    @Test
    fun `findIgRoot walks up from files and folders and stops at the nearest ig ini`(
        @TempDir tempDir: Path,
    ) {
        val outer = tempDir.resolve("outer")
        val inner = outer.resolve("nested/inner")
        Files.createDirectories(inner.resolve("input/tests/case-1"))
        Files.writeString(outer.resolve("ig.ini"), "[IG]\n")
        Files.writeString(inner.resolve("ig.ini"), "[IG]\n")
        val file = inner.resolve("input/tests/case-1/Patient.json")
        Files.writeString(file, "{}")

        assertEquals(inner.toAbsolutePath().normalize(), ProfileValidationPaths.findIgRoot(file))
        assertEquals(inner.toAbsolutePath().normalize(), ProfileValidationPaths.findIgRoot(inner))
        assertEquals(outer.toAbsolutePath().normalize(), ProfileValidationPaths.findIgRoot(outer.resolve("nested")))
        assertNull(ProfileValidationPaths.findIgRoot(tempDir))
    }
}

class IgContextManagerIgInfoTest {
    private val noContent =
        object : ContentService {
            override fun locate(
                root: URI,
                identifier: VersionedIdentifier,
            ): Set<URI> = emptySet()

            override fun read(uri: URI): InputStream? = null
        }

    private class StubIgContextManager(cs: ContentService) : IgContextManager(cs) {
        var lookups = 0

        override fun findIgContext(uri: URI): IGContext? {
            lookups++
            return IGContext(LoggerAdapter(LoggerFactory.getLogger("test")))
        }
    }

    @Test
    fun `IG context is cached under the IG root and an ig ini change clears it and bumps the generation`(
        @TempDir tempDir: Path,
    ) {
        Files.writeString(tempDir.resolve("ig.ini"), "[IG]\n")
        val manager = StubIgContextManager(noContent)
        val info = IgContextManagerIgInfo(manager)
        val igIniUri = tempDir.resolve("ig.ini").toUri()

        assertNotNull(manager.getIgContext(igIniUri))
        manager.getIgContext(igIniUri)
        assertEquals(1, manager.lookups)
        assertEquals(emptyList<String>(), info.dependsOn(tempDir))

        val before = info.generation(tempDir)
        manager.onMessageEvent(
            DidChangeWatchedFilesEvent(DidChangeWatchedFilesParams(listOf(FileEvent(igIniUri.toString(), FileChangeType.Changed)))),
        )
        assertNotEquals(before, info.generation(tempDir))
        manager.getIgContext(igIniUri)
        assertEquals(2, manager.lookups)
    }

    @Test
    fun `editing ig ini changes the generation even without a watcher event`(
        @TempDir tempDir: Path,
    ) {
        val igIni = tempDir.resolve("ig.ini")
        Files.writeString(igIni, "[IG]\n")
        val info = IgContextManagerIgInfo(StubIgContextManager(noContent))
        val before = info.generation(tempDir)
        Files.setLastModifiedTime(igIni, java.nio.file.attribute.FileTime.fromMillis(Files.getLastModifiedTime(igIni).toMillis() + 5_000))
        assertNotEquals(before, info.generation(tempDir))
    }
}
