package org.opencds.cqf.cql.ls.server.command.fhirop.handlers

import ca.uhn.fhir.context.FhirContext
import ca.uhn.fhir.context.FhirVersionEnum
import ca.uhn.fhir.repository.IRepository
import org.hl7.fhir.instance.model.api.IBaseBundle
import org.hl7.fhir.instance.model.api.IBaseResource
import org.hl7.fhir.instance.model.api.IIdType
import org.opencds.cqf.cql.ls.core.utility.Uris
import org.opencds.cqf.cql.ls.server.command.NoOpRepository
import org.opencds.cqf.cql.ls.server.command.fhirop.FhirOperationContext
import org.opencds.cqf.cql.ls.server.command.fhirop.FhirOperationHandler
import org.opencds.cqf.cql.ls.server.command.fhirop.FhirOperationRequest
import org.opencds.cqf.cql.ls.server.command.fhirop.FhirOperationResponse
import org.opencds.cqf.cql.ls.server.provider.FederatedLibrarySourceProvider
import org.opencds.cqf.cql.ls.server.repository.ig.standard.FederatedTerminologyRepo
import org.opencds.cqf.cql.ls.server.repository.ig.standard.IgStandardRepository
import org.opencds.cqf.fhir.cql.EvaluationSettings
import org.opencds.cqf.fhir.cql.LibraryEngine
import org.opencds.cqf.fhir.cr.CrSettings
import org.opencds.cqf.fhir.cr.plandefinition.PlanDefinitionProcessor
import org.opencds.cqf.fhir.utility.Ids
import org.opencds.cqf.fhir.utility.monad.Eithers
import org.opencds.cqf.fhir.utility.repository.ProxyRepository
import org.slf4j.LoggerFactory
import java.nio.file.Paths
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/**
 * `PlanDefinition/$apply` — invokes clinical-reasoning's [PlanDefinitionProcessor.apply].
 *
 * Builds the [IRepository] + [EvaluationSettings] the same way
 * [org.opencds.cqf.cql.ls.server.command.CqlEvaluator] builds a [org.opencds.cqf.cql.engine.execution.CqlEngine] —
 * there is no shared `LibraryEngine`/`CqlEvaluator` bean to inject (see plan notes).
 */
class ApplyPlanDefinitionHandler(
    private val executor: Executor = Executors.newCachedThreadPool(),
) : FhirOperationHandler {
    companion object {
        private val log = LoggerFactory.getLogger(ApplyPlanDefinitionHandler::class.java)
    }

    override val operationId: String = "PlanDefinition/\$apply"

    override fun run(
        request: FhirOperationRequest,
        ctx: FhirOperationContext,
    ): CompletableFuture<FhirOperationResponse> {
        return CompletableFuture.supplyAsync({ runInternal(request, ctx) }, executor)
    }

    private fun runInternal(
        request: FhirOperationRequest,
        ctx: FhirOperationContext,
    ): FhirOperationResponse {
        log.info("PlanDefinition/\$apply: starting — rootDir=${request.rootDir}, resourceUri=${request.resourceUri}, parameters=${request.parameters}")
        val errors = mutableListOf<String>()
        try {
            val fhirContext = FhirContext.forCached(FhirVersionEnum.valueOf(request.fhirVersion))

            val subject = request.parameters["subject"]
            if (subject.isNullOrBlank()) {
                log.warn("PlanDefinition/\$apply: missing required 'subject' parameter")
                return FhirOperationResponse(request.operation, errors = listOf("Missing required parameter: 'subject'"))
            }

            val repository = buildRepository(request, fhirContext, ctx)
            log.info("PlanDefinition/\$apply: repository built for rootDir=${request.rootDir}")

            val planDefinition =
                resolvePlanDefinition(request, fhirContext, errors, repository, ctx)
                    ?: return FhirOperationResponse(request.operation, errors = errors)

            log.info("PlanDefinition/\$apply: planDefinition resolved, subject=$subject")

            val dataBundle = resolveDataBundle(request, fhirContext, errors)
            if (errors.isNotEmpty()) return FhirOperationResponse(request.operation, errors = errors)

            val evaluationSettings = buildEvaluationSettings(request, fhirContext, ctx)
            val libraryEngine = LibraryEngine(repository, evaluationSettings)

            val processor = PlanDefinitionProcessor(repository, CrSettings.getDefault().withEvaluationSettings(evaluationSettings))
            val result =
                processor.apply(
                    Eithers.forRight3<org.hl7.fhir.instance.model.api.IPrimitiveType<String>, IIdType, IBaseResource>(planDefinition),
                    subject,
                    request.parameters["encounter"],
                    request.parameters["practitioner"],
                    request.parameters["organization"],
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    dataBundle,
                    null,
                    libraryEngine,
                )

            val resultJson = fhirContext.newJsonParser().encodeResourceToString(result)
            log.info("PlanDefinition/\$apply: success — resultType=${result.fhirType()}, resultJson length=${resultJson.length}")
            return FhirOperationResponse(
                operation = request.operation,
                resultJson = resultJson,
                resultType = result.fhirType(),
            )
        } catch (e: Exception) {
            log.error("PlanDefinition/\$apply failed", e)
            return FhirOperationResponse(request.operation, errors = listOf(e.message ?: e.javaClass.simpleName))
        }
    }

    private fun resolvePlanDefinition(
        request: FhirOperationRequest,
        fhirContext: FhirContext,
        errors: MutableList<String>,
        repository: IRepository,
        ctx: FhirOperationContext,
    ): IBaseResource? {
        val resourceUri = request.resourceUri
        if (!resourceUri.isNullOrBlank()) {
            val uri = Uris.parseOrNull(resourceUri)
            if (uri == null) {
                errors.add("Could not parse resourceUri: $resourceUri")
                return null
            }
            // Read via the content service (not a raw file open) so an unsaved editor buffer for
            // the resource being applied is honored, matching CqlEvaluator's read path.
            val stream = ctx.contentService.read(uri)
            if (stream == null) {
                errors.add("Could not read resourceUri: $resourceUri")
                return null
            }
            return stream.use {
                try {
                    fhirContext.newJsonParser().parseResource(it) as IBaseResource
                } catch (e: Exception) {
                    errors.add("Could not parse PlanDefinition at $resourceUri: ${e.message}")
                    null
                }
            }
        }

        val resourceId = request.resourceId
        if (!resourceId.isNullOrBlank()) {
            val resourceType = fhirContext.getResourceDefinition("PlanDefinition").implementingClass
            val idType = Ids.newId<IIdType>(FhirVersionEnum.valueOf(request.fhirVersion), Ids.ensureIdType(resourceId, "PlanDefinition"))
            return try {
                @Suppress("UNCHECKED_CAST")
                repository.read(resourceType as Class<IBaseResource>, idType, emptyMap())
            } catch (e: Exception) {
                errors.add("Could not resolve PlanDefinition '$resourceId': ${e.message}")
                null
            }
        }

        errors.add("One of 'resourceUri' or 'resourceId' is required")
        return null
    }

    /**
     * [request.dataBundle] is a raw JSON string sent by the client. If present it must parse to
     * a Bundle — clinical-reasoning's [PlanDefinitionProcessor.apply] accepts a Bundle/IBaseBundle
     * of context data; the handler owns deserialization so the client only ever sends a string.
     */
    private fun resolveDataBundle(
        request: FhirOperationRequest,
        fhirContext: FhirContext,
        errors: MutableList<String>,
    ): IBaseBundle? {
        val dataBundle = request.dataBundle ?: return null
        return try {
            val parsed = fhirContext.newJsonParser().parseResource(dataBundle)
            parsed as? IBaseBundle
                ?: run {
                    errors.add("'dataBundle' must be a FHIR Bundle, got: ${parsed.fhirType()}")
                    null
                }
        } catch (e: Exception) {
            errors.add("Could not parse 'dataBundle': ${e.message}")
            null
        }
    }

    private fun buildRepository(
        request: FhirOperationRequest,
        fhirContext: FhirContext,
        ctx: FhirOperationContext,
    ): IRepository {
        val rootDir = request.rootDir
        val data: IRepository =
            if (!rootDir.isNullOrBlank()) {
                val rootUri = Uris.parseOrNull(rootDir)?.let { Uris.addPath(it, "input") }
                if (rootUri != null) {
                    IgStandardRepository(fhirContext, Paths.get(rootUri))
                } else {
                    NoOpRepository(fhirContext)
                }
            } else {
                NoOpRepository(fhirContext)
            }

        val terminologyRepo: IRepository =
            ctx.libraryResolutionManager.getInputDirectories().takeIf { it.isNotEmpty() }
                ?.let { FederatedTerminologyRepo(fhirContext, it) }
                ?: data

        return ProxyRepository(data, data, terminologyRepo)
    }

    private fun buildEvaluationSettings(
        request: FhirOperationRequest,
        fhirContext: FhirContext,
        ctx: FhirOperationContext,
    ): EvaluationSettings {
        val evaluationSettings = EvaluationSettings.getDefault()

        val rootDir = request.rootDir
        val resourceUri = request.resourceUri
        val cqlRootUri =
            rootDir?.let { Uris.addPath(Uris.addPath(Uris.parseOrNull(it)!!, "input")!!, "cql") }
                ?: resourceUri?.let { Uris.parseOrNull(it) }

        if (cqlRootUri != null) {
            evaluationSettings.librarySourceProviders.add(
                FederatedLibrarySourceProvider(cqlRootUri, ctx.contentService, ctx.igContextManager.getContext(cqlRootUri)),
            )
        }
        return evaluationSettings
    }
}
