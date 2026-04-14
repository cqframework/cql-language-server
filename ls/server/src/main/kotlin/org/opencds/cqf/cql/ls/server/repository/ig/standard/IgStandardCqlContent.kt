package org.opencds.cqf.cql.ls.server.repository.ig.standard

import ca.uhn.fhir.rest.server.exceptions.ResourceNotFoundException
import org.hl7.fhir.instance.model.api.IBaseResource
import java.io.IOException
import java.nio.file.Path

object IgStandardCqlContent {
    fun loadCqlContent(
        resource: IBaseResource,
        resourcePath: Path,
    ) {
        requireNotNull(resource) { "resource can not be null" }
        requireNotNull(resourcePath) { "resourcePath can not be null" }

        if ("Library" != resource.fhirType()) {
            return
        }

        val (cqlPathExtractor, cqlContentAttacher) =
            when (resource.structureFhirVersionEnum) {
                ca.uhn.fhir.context.FhirVersionEnum.DSTU3 ->
                    Pair(
                        { r: IBaseResource -> org.opencds.cqf.fhir.utility.dstu3.AttachmentUtil.getCqlLocation(r) },
                        { r: IBaseResource, s: String ->
                            org.opencds.cqf.fhir.utility.dstu3.AttachmentUtil.addData(r, s)
                            Unit
                        },
                    )
                ca.uhn.fhir.context.FhirVersionEnum.R4 ->
                    Pair(
                        { r: IBaseResource -> org.opencds.cqf.fhir.utility.r4.AttachmentUtil.getCqlLocation(r) },
                        { r: IBaseResource, s: String ->
                            org.opencds.cqf.fhir.utility.r4.AttachmentUtil.addData(r, s)
                            Unit
                        },
                    )
                ca.uhn.fhir.context.FhirVersionEnum.R5 ->
                    Pair(
                        { r: IBaseResource -> org.opencds.cqf.fhir.utility.r5.AttachmentUtil.getCqlLocation(r) },
                        { r: IBaseResource, s: String ->
                            org.opencds.cqf.fhir.utility.r5.AttachmentUtil.addData(r, s)
                            Unit
                        },
                    )
                else -> throw IllegalArgumentException("Unsupported FHIR version: ${resource.structureFhirVersionEnum}")
            }

        readAndAttachCqlContent(resource, resourcePath, cqlPathExtractor, cqlContentAttacher)
    }

    private fun readAndAttachCqlContent(
        resource: IBaseResource,
        resourcePath: Path,
        cqlPathExtractor: (IBaseResource) -> String?,
        cqlContentAttacher: (IBaseResource, String) -> Unit,
    ) {
        val cqlPath = cqlPathExtractor(resource) ?: return
        val cqlContent = getCqlContent(resourcePath, cqlPath)
        cqlContentAttacher(resource, cqlContent)
    }

    internal fun getCqlContent(
        rootPath: Path,
        relativePath: String,
    ): String {
        val path = rootPath.resolve(relativePath).normalize()
        return try {
            path.toFile().readText(Charsets.UTF_8)
        } catch (e: IOException) {
            throw ResourceNotFoundException("Unable to read CQL content from path: $path")
        }
    }
}
