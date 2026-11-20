package org.opencds.cqf.cql.ls.server.validation.spike

import ca.uhn.fhir.context.FhirContext
import ca.uhn.fhir.util.VersionUtil
import org.hl7.fhir.r5.utils.validation.constants.ReferenceValidationPolicy
import org.hl7.fhir.validation.ValidationEngine
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfSystemProperty
import org.junit.jupiter.api.io.TempDir
import org.opencds.cqf.cql.ls.server.validation.testcase.TestCaseValidator
import org.opencds.cqf.cql.ls.server.validation.testcase.ValidateTestCaseRequest
import org.opencds.cqf.cql.ls.server.validation.testcase.ValidationIssue
import org.opencds.cqf.cql.ls.server.validation.testcase.ValidationSeverity
import java.lang.management.ManagementFactory
import java.lang.management.MemoryType
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.io.path.writeText

// SPIKE ONLY: throwaway measurement harness. Run with -Dspike=true; never part of the normal suite.
@EnabledIfSystemProperty(named = "spike", matches = "true")
class FhirValidatorSpikeTest {
    private val content = Paths.get(System.getProperty("spike.content", "../../../dqm-content-qicore-2025")).toAbsolutePath().normalize()
    private val out = Paths.get(System.getProperty("spike.out", "target/spike")).toAbsolutePath()
    private val engines = System.getProperty("spike.engines", "a,b").split(",").map { it.trim() }
    private val suiteLimit = System.getProperty("spike.suiteLimit", "1000").toInt()
    private val bSkip = System.getProperty("spike.bSkip", "").split(",").filter { it.isNotBlank() }.toSet()
    private val measures = content.resolve("input/tests/measure")
    private val vocabulary = content.resolve("input/vocabulary")
    private val baseCase = measures.resolve("CMS0334FHIRPCCesareanBirth/014b44f2-2119-4009-8ef2-4b6a62d6dd7a")
    private val extraMeasures = listOf("CMS122FHIRDiabetesAssessGT9Pct", "CMS125FHIRBreastCancerScreen", "CMS108FHIRVTEProphylaxis")
    private val roots =
        System.getProperty("spike.roots", "hl7.fhir.us.qicore#6.0.0,hl7.fhir.us.cqfmeasures#5.0.0,hl7.terminology.r4#6.5.0,fhir.cqf.common#4.0.1")
            .split(",").map { it.trim().split("#").let { p -> p[0] to p[1] } }
    private val suiteDump = StringBuilder()

    private val report = StringBuilder()

    private fun say(s: String = "") {
        report.appendLine(s)
        println("[spike] $s")
    }

    private fun issuesOf(list: List<ValidationIssue>) = list.filter { it.severity != ValidationSeverity.INFORMATION }

    private fun key(i: ValidationIssue) = "${i.severity}|${i.code}|${i.message}"

    private fun usedHeapMb(): Long {
        System.gc()
        Thread.sleep(200)
        val rt = Runtime.getRuntime()
        return (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024)
    }

    private fun resetPeaks() = ManagementFactory.getMemoryPoolMXBeans().filter { it.type == MemoryType.HEAP }.forEach { it.resetPeakUsage() }

    private fun peakHeapMb() =
        ManagementFactory.getMemoryPoolMXBeans().filter { it.type == MemoryType.HEAP }.sumOf { it.peakUsage.used } / (1024 * 1024)

    private fun caseDirs(measure: String) =
        Files.list(measures.resolve(measure)).use { s ->
            s.filter { Files.isDirectory(it) && it.name != "shared" && !it.name.startsWith(".") }.sorted().toList()
        }

    private fun jsonFiles(dir: Path) = Files.list(dir).use { s -> s.filter { it.name.endsWith(".json") }.sorted().toList() }

    private fun indexFor(caseDir: Path) = CaseIndex.of(listOf(caseDir, caseDir.parent.resolve("shared")))

    private fun validateFolder(
        engine: SpikeEngine,
        dir: Path,
        index: CaseIndex = indexFor(dir),
    ) = jsonFiles(dir).flatMap { engine.validateFile(it, index) }

    private fun baseline(path: Path) =
        TestCaseValidator().validate(ValidateTestCaseRequest(path = path.toString())).testCases.flatMap { it.issues }

    // ---- defects --------------------------------------------------------------------------------

    data class Defect(
        val name: String,
        val flat: Boolean,
        val bundle: Boolean,
        val apply: (Path) -> Unit,
        val expected: (String) -> Int?,
        val target: String,
    )

    private val patientId = baseCase.name
    private val encounterFile = "Encounter-725eb42f-6d57-47dc-a8d1-4bc27af95447.json"
    private val practitionerId = "fcb2d7bd-7845-4272-a14c-aeb3f12ee180"

    private fun edit(
        dir: Path,
        file: String,
        transform: (String) -> String,
    ) {
        val f = dir.resolve(file)
        val before = f.readText()
        val after = transform(before)
        check(before != after) { "defect edit did not apply to $file" }
        f.writeText(after)
    }

    private fun lineAfter(
        text: String,
        anchor: String,
        needle: String,
    ): Int? {
        val lines = text.lines()
        val start = lines.indexOfFirst { it.contains(anchor) }.coerceAtLeast(0)
        return (start until lines.size).firstOrNull { lines[it].contains(needle) }?.plus(1)
    }

    private val defects =
        listOf(
            Defect("dangling reference", true, true, { d ->
                edit(d, encounterFile) { it.replace("Condition/fe292e1c-3991-4628-8f00-291aadc5c071", "Condition/does-not-exist") }
            }, { lineOf(it, "Condition/does-not-exist") }, "Encounter"),
            Defect("wrong target type (Encounter.subject -> Practitioner)", true, true, { d ->
                edit(d, encounterFile) { it.replace("\"Patient/$patientId\"", "\"Practitioner/$practitionerId\"") }
            }, { lineAfter(it, "725eb42f-6d57-47dc-a8d1-4bc27af95447", practitionerId) }, "Encounter"),
            Defect("target breaks its profile (Patient missing gender)", true, true, { d ->
                edit(d, "Patient-$patientId.json") { it.replace(Regex("\\s*\"gender\"\\s*:\\s*\"female\",?"), "") }
            }, { lineAfter(it, "\"resourceType\": \"Patient\"", "\"resourceType\"") }, "Patient"),
            Defect("missing required field (Encounter.status)", true, true, { d ->
                edit(d, encounterFile) { it.replace(Regex("\\s*\"status\"\\s*:\\s*\"finished\","), "") }
            }, { lineAfter(it, "\"resourceType\": \"Encounter\"", "\"resourceType\"") }, "Encounter"),
            Defect("contained #ref valid", true, true, { d ->
                edit(d, encounterFile) {
                    it.replaceFirst(
                        "\"status\": \"finished\",",
                        "\"status\": \"finished\",\n  \"contained\": [ { \"resourceType\": \"Location\", \"id\": \"loc1\", \"status\": \"active\" } ],\n" +
                            "  \"location\": [ { \"location\": { \"reference\": \"#loc1\" } } ],",
                    )
                }
            }, { null }, "Encounter"),
            Defect("contained #ref invalid", true, true, { d ->
                edit(d, encounterFile) {
                    it.replaceFirst("\"status\": \"finished\",", "\"status\": \"finished\",\n  \"location\": [ { \"location\": { \"reference\": \"#loc-missing\" } } ],")
                }
            }, { lineOf(it, "#loc-missing") }, "Encounter"),
            Defect("bad fullUrl/id pair", false, true, { }, { lineOf(it, "http://example.org/fhir/Encounter/not-the-id") }, "Bundle"),
            Defect("malformed JSON", true, true, { d ->
                edit(d, "Procedure-f55a7821-6b64-4f07-876f-d6bc6d02053e.json") { it.trimEnd().removeSuffix("}") }
            }, { it.lines().size }, "Procedure"),
        )

    private fun makeBundle(
        caseDir: Path,
        bundleDir: Path,
        defect: Defect?,
    ): Path {
        Files.createDirectories(bundleDir)
        var text = buildTransactionBundle(caseDir)
        when (defect?.name) {
            "bad fullUrl/id pair" ->
                text = text.replace("\"fullUrl\": \"urn:uuid:725eb42f-6d57-47dc-a8d1-4bc27af95447\"", "\"fullUrl\": \"http://example.org/fhir/Encounter/not-the-id\"")
            "malformed JSON" -> text = text.trimEnd().removeSuffix("}")
        }
        val f = bundleDir.resolve("bundle.json")
        f.writeText(text)
        return f
    }

    // ---- test -----------------------------------------------------------------------------------

    @Test
    fun spike(
        @TempDir tmp: Path,
    ) {
        Files.createDirectories(out)
        say("# FHIR validator spike raw results")
        say("content=$content engines=$engines")
        say("hapi-fhir-base VersionUtil=${VersionUtil.getVersion()} jar=${FhirContext::class.java.protectionDomain.codeSource.location}")
        say("org.hl7.fhir.validation jar=${ValidationEngine::class.java.protectionDomain.codeSource.location}")
        say("max heap MB=${Runtime.getRuntime().maxMemory() / (1024 * 1024)}")

        // Package resolution (downloads missing public packages into ~/.fhir/packages)
        val t0 = System.nanoTime()
        val (packages, loads) = resolvePackages(roots)
        say("\n## Packages (resolved in ${(System.nanoTime() - t0) / 1_000_000} ms)")
        loads.forEach { say("- ${it.id}#${it.version} cachedBefore=${it.wasCached} ok=${it.ok} ${it.note}") }

        // Clean real cases: flat folders + bundles built in tmp
        val cleanFlat = listOf(baseCase) + extraMeasures.map { caseDirs(it).first() }
        val cleanBundles =
            cleanFlat.mapIndexed { i, dir ->
                indexFor(dir) to makeBundle(dir, tmp.resolve("clean-bundle-$i"), null)
            }

        // Defect copies (flat + bundle)
        val seeded = mutableListOf<Seeded>()
        val cleanBaseCopy = copyCase(baseCase, tmp.resolve("clean-base/${baseCase.name}"))
        val cleanBaseBundle = makeBundle(cleanBaseCopy, tmp.resolve("clean-base-bundle"), null)
        defects.forEachIndexed { i, d ->
            val dir = copyCase(baseCase, tmp.resolve("defect-$i/${baseCase.name}"))
            if (d.name != "malformed JSON") d.apply(dir)
            if (d.flat) {
                val flatDir = copyCase(baseCase, tmp.resolve("defect-$i-flat/${baseCase.name}"))
                d.apply(flatDir)
                seeded.add(Seeded(d, "flat", flatDir, CaseIndex.of(listOf(flatDir)), jsonFiles(flatDir)))
            }
            if (d.bundle) {
                val b = makeBundle(dir, tmp.resolve("defect-$i-bundle"), d)
                seeded.add(Seeded(d, "bundle", b, CaseIndex.of(listOf(b.parent)), listOf(b)))
            }
        }

        // Baseline TestCaseValidator
        val baselineCleanFlat = issuesOf(baseline(cleanBaseCopy)).map(::key).toSet()
        val baselineCleanBundle = issuesOf(baseline(cleanBaseBundle)).map(::key).toSet()
        say("\n## Baseline TestCaseValidator on clean base case: flat=${baselineCleanFlat.size} bundle=${baselineCleanBundle.size} issues")
        baselineCleanFlat.forEach { say("  flat: $it") }
        baselineCleanBundle.forEach { say("  bundle: $it") }
        say("\n## Defect matrix: TestCaseValidator")
        for (s in seeded) {
            val clean = if (s.layout == "flat") baselineCleanFlat else baselineCleanBundle
            val found = issuesOf(baseline(s.path)).filter { key(it) !in clean }
            say("- [${s.layout}] ${s.defect.name}: ${if (found.isEmpty()) "MISSED" else found.joinToString { "${it.code}@${it.line}" }}")
        }

        for (engineId in engines) {
            resetPeaks()
            val heapBefore = usedHeapMb()
            val buildStart = System.nanoTime()
            val advisor = ReferenceCheckingPolicyAdvisor(ReferenceValidationPolicy.CHECK_EXISTS_AND_TYPE)
            val engine: SpikeEngine =
                try {
                    when (engineId) {
                        "a" -> EngineA(roots.map { "${it.first}#${it.second}" }, vocabulary, advisor)
                        else -> EngineB(packages.filter { it.name() !in bSkip }, vocabulary, advisor)
                    }
                } catch (e: Throwable) {
                    say("\n## Engine $engineId: BUILD FAILED ${e.javaClass.name}: ${e.message}")
                    e.printStackTrace()
                    continue
                }
            try {
                runEngine(engine, engineId, advisor, buildStart, heapBefore, cleanBaseCopy, cleanBaseBundle, cleanFlat, cleanBundles, seeded)
            } catch (e: Throwable) {
                say("\n## Engine $engineId: RUN FAILED ${e.javaClass.name}: ${e.message}")
                say("  at ${e.stackTrace.take(4).joinToString(" <- ")}")
                e.printStackTrace()
            }
        }

        out.resolve("spike-suite-${System.getProperty("spike.tag", "x")}.txt").writeText(suiteDump.toString())
        out.resolve("spike-results-${System.getProperty("spike.tag", engines.joinToString(""))}.md").writeText(report.toString())
    }

    data class Seeded(val defect: Defect, val layout: String, val path: Path, val index: CaseIndex, val validateTargets: List<Path>)

    private fun runEngine(
        engine: SpikeEngine,
        engineId: String,
        advisor: ReferenceCheckingPolicyAdvisor,
        buildStart: Long,
        heapBefore: Long,
        cleanBaseCopy: Path,
        cleanBaseBundle: Path,
        cleanFlat: List<Path>,
        cleanBundles: List<Pair<CaseIndex, Path>>,
        seeded: List<Seeded>,
    ) {
        run {
            val buildMs = (System.nanoTime() - buildStart) / 1_000_000
            say("\n## Engine ${engine.name} (id=$engineId, bSkip=$bSkip)")
            when (engine) {
                is EngineA -> engine.loadNotes.forEach { say("  load: $it") }
                is EngineB -> engine.loadNotes.forEach { say("  load: $it") }
            }
            say("build ms=$buildMs")

            val firstStart = System.nanoTime()
            val cleanBaseIssues = validateFolder(engine, cleanBaseCopy)
            say("first case (flat, ${jsonFiles(cleanBaseCopy).size} files) ms=${(System.nanoTime() - firstStart) / 1_000_000}")
            val warm =
                (1..3).map {
                    val s = System.nanoTime()
                    validateFolder(engine, cleanBaseCopy)
                    (System.nanoTime() - s) / 1_000_000
                }
            say("warm flat case ms=$warm")
            val warmBundle =
                (1..3).map {
                    val s = System.nanoTime()
                    engine.validateFile(cleanBaseBundle, CaseIndex.of(listOf(cleanBaseBundle.parent)))
                    (System.nanoTime() - s) / 1_000_000
                }
            say("warm bundle case ms=$warmBundle")
            say("heap after build MB=${usedHeapMb() - heapBefore} (retained delta), peak heap MB=${peakHeapMb()}")

            // False positives on clean real cases
            val fp = mutableMapOf<String, Int>()
            val fpExamples = mutableMapOf<String, String>()

            fun tally(
                layout: String,
                list: List<ValidationIssue>,
            ) {
                for (i in issuesOf(list)) {
                    val k = "$layout|${i.severity}|${i.code}"
                    fp.merge(k, 1, Int::plus)
                    fpExamples.putIfAbsent(k, "${Paths.get(i.filePath ?: "").name}:${i.line} ${i.jsonPath} :: ${i.message.take(220)}")
                }
            }
            cleanFlat.forEach { tally("flat", validateFolder(engine, it)) }
            cleanBundles.forEach { (_, b) -> tally("bundle", engine.validateFile(b, CaseIndex.of(listOf(b.parent)))) }

            // Full measure suite (flat)
            val suite = caseDirs("CMS0334FHIRPCCesareanBirth").take(suiteLimit)
            resetPeaks()
            val suiteStart = System.nanoTime()
            var suiteFiles = 0
            suite.forEach { dir ->
                val files = jsonFiles(dir)
                suiteFiles += files.size
                val found = files.flatMap { engine.validateFile(it, indexFor(dir)) }
                issuesOf(found).forEach { suiteDump.appendLine("${dir.name}/${Paths.get(it.filePath ?: "").name}|${it.line}|${it.severity}|${it.code}|${it.jsonPath}|${it.message}") }
                tally("suite", found)
            }
            val suiteMs = (System.nanoTime() - suiteStart) / 1_000_000
            say("suite CMS0334 cases=${suite.size} files=$suiteFiles ms=$suiteMs (${suiteMs / suite.size.coerceAtLeast(1)} ms/case) peak heap MB=${peakHeapMb()}")

            say("\n### Issues on clean real cases by layout|severity|messageId (count) :: example")
            fp.entries.sortedWith(compareBy({ it.key.substringBefore("|") }, { -it.value })).forEach {
                say("- ${it.key} (${it.value}) :: ${fpExamples[it.key]}")
            }

            // Detection matrix
            val cleanFlatKeys = issuesOf(cleanBaseIssues).map(::key).toSet()
            val cleanBundleKeys = issuesOf(engine.validateFile(cleanBaseBundle, CaseIndex.of(listOf(cleanBaseBundle.parent)))).map(::key).toSet()
            for (policy in listOf(ReferenceValidationPolicy.CHECK_EXISTS_AND_TYPE, ReferenceValidationPolicy.CHECK_VALID)) {
                advisor.withPolicy(policy)
                say("\n### Defect matrix, policy=$policy")
                for (s in seeded) {
                    val clean = if (s.layout == "flat") cleanFlatKeys else cleanBundleKeys
                    val found = issuesOf(s.validateTargets.flatMap { engine.validateFile(it, s.index) }).filter { key(it) !in clean }
                    val targetText =
                        if (s.layout == "bundle") {
                            s.path.readText()
                        } else {
                            s.validateTargets.firstOrNull { it.name.startsWith(s.defect.target) }?.readText() ?: ""
                        }
                    val expectedLine = s.defect.expected(targetText)
                    val detail =
                        found.joinToString("; ") {
                            "${it.severity.name.first()}:${it.code}@${Paths.get(it.filePath ?: "").name}:${it.line} [${it.jsonPath}] ${it.message.take(160)}"
                        }
                    say("- [${s.layout}] ${s.defect.name} expectedLine=$expectedLine -> ${if (found.isEmpty()) "MISSED" else detail}")
                }
            }
            advisor.withPolicy(ReferenceValidationPolicy.CHECK_EXISTS_AND_TYPE)
            val fetcher = (engine as? EngineA)?.fetcher ?: (engine as EngineB).fetcher
            say("\nfetcher: fetched=${fetcher.fetched.size} refusedAbsolute=${fetcher.refusedAbsolute.distinct().take(10)}")
        }
    }
}
