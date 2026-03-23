package org.opencds.cqf.cql.ls.server.repository.ig.standard

import ca.uhn.fhir.context.FhirContext
import ca.uhn.fhir.model.api.IQueryParameterType
import ca.uhn.fhir.parser.DataFormatException
import ca.uhn.fhir.parser.IParser
import ca.uhn.fhir.repository.IRepository
import ca.uhn.fhir.rest.api.EncodingEnum
import ca.uhn.fhir.rest.api.MethodOutcome
import ca.uhn.fhir.rest.param.TokenParam
import ca.uhn.fhir.rest.server.exceptions.ForbiddenOperationException
import ca.uhn.fhir.rest.server.exceptions.ResourceNotFoundException
import ca.uhn.fhir.rest.server.exceptions.UnclassifiedServerFailureException
import ca.uhn.fhir.util.BundleBuilder
import com.google.common.cache.CacheBuilder
import com.google.common.collect.BiMap
import com.google.common.collect.ImmutableBiMap
import com.google.common.collect.ImmutableMap
import com.google.common.collect.Multimap
import org.hl7.fhir.instance.model.api.IBaseBundle
import org.hl7.fhir.instance.model.api.IBaseParameters
import org.hl7.fhir.instance.model.api.IBaseResource
import org.hl7.fhir.instance.model.api.IIdType
import org.opencds.cqf.fhir.utility.Ids
import org.opencds.cqf.fhir.utility.matcher.ResourceMatcher
import org.opencds.cqf.fhir.utility.repository.IRepositoryOperationProvider
import org.opencds.cqf.fhir.utility.repository.Repositories
import org.slf4j.LoggerFactory
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

open class IgStandardRepository : IRepository {

    companion object {
        private val log = LoggerFactory.getLogger(IgStandardRepository::class.java)

        const val SOURCE_PATH_TAG = "sourcePath"
        const val EXTERNAL_DIRECTORY = "external"
        const val FHIR_COMPARTMENT_HEADER = "X-FHIR-Compartment"

        @JvmField
        val CATEGORY_DIRECTORIES: Map<IgStandardResourceCategory, String> = ImmutableMap.builder<IgStandardResourceCategory, String>()
            .put(IgStandardResourceCategory.CONTENT, "resources")
            .put(IgStandardResourceCategory.DATA, "tests")
            .put(IgStandardResourceCategory.TERMINOLOGY, "vocabulary")
            .build()

        @JvmField
        val FILE_EXTENSIONS: BiMap<EncodingEnum, String> = ImmutableBiMap.builder<EncodingEnum, String>()
            .put(EncodingEnum.JSON, "json")
            .put(EncodingEnum.XML, "xml")
            .put(EncodingEnum.RDF, "rdf")
            .build()

        private fun parserForEncoding(fhirContext: FhirContext, encodingEnum: EncodingEnum): IParser {
            return when (encodingEnum) {
                EncodingEnum.JSON -> fhirContext.newJsonParser()
                EncodingEnum.XML -> fhirContext.newXmlParser()
                EncodingEnum.RDF -> fhirContext.newRDFParser()
                else -> throw IllegalArgumentException("NDJSON is not supported")
            }
        }
    }

    private val fhirContext: FhirContext
    private val root: Path
    private val conventions: IgStandardConventions
    private val encodingBehavior: IgStandardEncodingBehavior
    private val resourceMatcher: ResourceMatcher
    private var operationProvider: IRepositoryOperationProvider?

    private val resourceCache = CacheBuilder.newBuilder()
        .concurrencyLevel(10)
        .maximumSize(500)
        .build<Path, IBaseResource>()

    /**
     * Creates a new IgRepository with auto-detected conventions and default encoding behavior.
     */
    constructor(fhirContext: FhirContext, root: Path) :
        this(fhirContext, root, IgStandardConventions.autoDetect(root), IgStandardEncodingBehavior.DEFAULT, null)

    constructor(
        fhirContext: FhirContext,
        root: Path,
        conventions: IgStandardConventions,
        encodingBehavior: IgStandardEncodingBehavior,
        operationProvider: IRepositoryOperationProvider?
    ) {
        this.fhirContext = requireNotNull(fhirContext) { "fhirContext cannot be null" }
        this.root = requireNotNull(root) { "root cannot be null" }
        this.conventions = requireNotNull(conventions) { "conventions is required" }
        this.encodingBehavior = requireNotNull(encodingBehavior) { "encodingBehavior is required" }
        this.resourceMatcher = Repositories.getResourceMatcher(this.fhirContext)
        this.operationProvider = operationProvider
    }

    fun setOperationProvider(operationProvider: IRepositoryOperationProvider) {
        this.operationProvider = operationProvider
    }

    fun clearCache() {
        resourceCache.invalidateAll()
    }

    private fun isExternalPath(path: Path): Boolean =
        path.parent != null && path.parent.toString().lowercase().endsWith(EXTERNAL_DIRECTORY)

    protected open fun <T : IBaseResource, I : IIdType> preferredPathForResource(
        resourceType: Class<T>, id: I, igRepositoryCompartment: IgStandardRepositoryCompartment
    ): Path {
        val directory = directoryForResource(resourceType, igRepositoryCompartment)
        val fileName = fileNameForResource(resourceType.simpleName, id.idPart, encodingBehavior.preferredEncoding)
        return directory.resolve(fileName)
    }

    protected open fun <T : IBaseResource, I : IIdType> potentialPathsForResource(
        resourceType: Class<T>, id: I, igRepositoryCompartment: IgStandardRepositoryCompartment
    ): List<Path> {
        val potentialDirectories = mutableListOf<Path>()
        val directory = directoryForResource(resourceType, igRepositoryCompartment)
        potentialDirectories.add(directory)

        if (IgStandardResourceCategory.forType(resourceType.simpleName) == IgStandardResourceCategory.TERMINOLOGY) {
            potentialDirectories.add(directory.resolve(EXTERNAL_DIRECTORY))
        }

        val potentialPaths = mutableListOf<Path>()
        for (dir in potentialDirectories) {
            for (encoding in FILE_EXTENSIONS.keys) {
                potentialPaths.add(dir.resolve(fileNameForResource(resourceType.simpleName, id.idPart, encoding)))
            }
        }

        return potentialPaths
    }

    protected open fun fileNameForResource(resourceType: String, resourceId: String, encoding: EncodingEnum): String {
        val name = "$resourceId.${FILE_EXTENSIONS[encoding]}"
        return if (IgStandardConventions.FilenameMode.ID_ONLY == conventions.filenameMode) name
        else "$resourceType-$name"
    }

    protected open fun <T : IBaseResource> directoryForCategory(
        resourceType: Class<T>, igStandardRepositoryCompartment: IgStandardRepositoryCompartment
    ): Path {
        if (conventions.categoryLayout == IgStandardConventions.CategoryLayout.FLAT) {
            return root
        }

        val category = IgStandardResourceCategory.forType(resourceType.simpleName)
        val directory = requireNotNull(CATEGORY_DIRECTORIES[category]) {
            "No directory configured for category: $category"
        }
        val categoryPath = root.resolve(directory)

        if (conventions.compartmentLayout == IgStandardConventions.CompartmentLayout.DIRECTORY_PER_COMPARTMENT &&
            !igStandardRepositoryCompartment.isEmpty()
        ) {
            if (category == IgStandardResourceCategory.DATA) {
                return categoryPath.resolve(pathForCompartment(igStandardRepositoryCompartment))
            }
        }

        return categoryPath
    }

    protected open fun <T : IBaseResource> directoryForResource(
        resourceType: Class<T>, igRepositoryCompartment: IgStandardRepositoryCompartment
    ): Path {
        val directory = directoryForCategory(resourceType, igRepositoryCompartment)
        if (conventions.typeLayout == IgStandardConventions.FhirTypeLayout.FLAT) {
            return directory
        }
        return directory.resolve(resourceType.simpleName.lowercase())
    }

    protected open fun readResource(path: Path): IBaseResource? {
        log.info("IgStandardRepository.readResource - Attempting to read resource from path: {}", path)
        val file = path.toFile()
        if (!file.exists()) {
            log.info("IgStandardRepository.readResource - Didn't find file")
            return null
        }

        val extension = fileExtension(path) ?: run {
            log.info("IgStandardRepository.readResource - Extension check failed")
            return null
        }

        val encoding = FILE_EXTENSIONS.inverse()[extension] ?: return null

        return try {
            val s = String(Files.readAllBytes(path), StandardCharsets.UTF_8)
            val resource = parserForEncoding(fhirContext, encoding).parseResource(s)
            resource.setUserData(SOURCE_PATH_TAG, path)
            IgStandardCqlContent.loadCqlContent(resource, path.parent)
            log.info("IgStandardRepository.readResource - Returning resource: {}", resource)
            resource
        } catch (e: FileNotFoundException) {
            null
        } catch (e: DataFormatException) {
            throw ResourceNotFoundException("Found empty or invalid content at path $path")
        } catch (e: IOException) {
            throw UnclassifiedServerFailureException(500, "Unable to read resource from path $path")
        }
    }

    protected open fun cachedReadResource(path: Path): IBaseResource? {
        val cached = resourceCache.getIfPresent(path)
        if (cached != null) {
            log.info("IgStandardRepository.cachedReadResource - Returning cached resource: {}", cached)
            return cached
        }
        val resource = readResource(path)
        if (resource != null) {
            resourceCache.put(path, resource)
        }
        log.info("IgStandardRepository.cachedReadResource - Returning freshly loaded resource: {}", resource)
        return resource
    }

    protected open fun encodingForPath(path: Path): EncodingEnum? = FILE_EXTENSIONS.inverse()[fileExtension(path)]

    protected open fun <T : IBaseResource> writeResource(resource: T, path: Path) {
        try {
            val encoding = encodingForPath(path) ?: return
            path.parent?.toFile()?.mkdirs()
            FileOutputStream(path.toFile()).use { stream ->
                val result = parserForEncoding(fhirContext, encoding)
                    .setPrettyPrint(true)
                    .encodeResourceToString(resource)
                stream.write(result.toByteArray())
                resource.setUserData(SOURCE_PATH_TAG, path)
                resourceCache.put(path, resource)
            }
        } catch (e: Exception) {
            when (e) {
                is IOException, is SecurityException ->
                    throw UnclassifiedServerFailureException(500, "Unable to write resource to path $path")
                else -> throw e
            }
        }
    }

    private fun fileExtension(path: Path): String? {
        val name = path.fileName.toString()
        val lastPeriod = name.lastIndexOf(".")
        if (lastPeriod == -1) return null
        return name.substring(lastPeriod + 1).lowercase()
    }

    private fun acceptByFileExtension(path: Path): Boolean {
        val extension = fileExtension(path) ?: return false
        return FILE_EXTENSIONS.containsValue(extension)
    }

    private fun acceptByFileExtensionAndPrefix(path: Path, prefix: String): Boolean {
        if (!acceptByFileExtension(path)) return false
        return path.fileName.toString().lowercase().startsWith(prefix.lowercase() + "-")
    }

    protected open fun <T : IBaseResource> readDirectoryForResourceType(
        resourceClass: Class<T>, igRepositoryCompartment: IgStandardRepositoryCompartment
    ): Map<IIdType, T> {
        val path = directoryForResource(resourceClass, igRepositoryCompartment)
        if (!path.toFile().exists()) return emptyMap()

        val resources = ConcurrentHashMap<IIdType, T>()
        val resourceFileFilter: (Path) -> Boolean = when (conventions.filenameMode) {
            IgStandardConventions.FilenameMode.ID_ONLY -> ::acceptByFileExtension
            else -> { p -> acceptByFileExtensionAndPrefix(p, resourceClass.simpleName) }
        }

        try {
            Files.walk(path).use { paths ->
                paths.filter(resourceFileFilter)
                    .parallel()
                    .map { cachedReadResource(it) }
                    .filter { it != null }
                    .map { checkNotNull(it) }
                    .forEach { r ->
                        if (r.fhirType() != resourceClass.simpleName) return@forEach
                        val validated = validateResource(resourceClass, r, r.idElement)
                        resources[r.idElement.toUnqualifiedVersionless()] = validated
                    }
            }
        } catch (e: IOException) {
            throw UnclassifiedServerFailureException(500, "Unable to read resources from path: $path")
        }

        return resources
    }

    override fun fhirContext(): FhirContext = fhirContext

    override fun <T : IBaseResource, I : IIdType> read(
        resourceType: Class<T>, id: I, headers: Map<String, String>?
    ): T {
        requireNotNull(resourceType) { "resourceType cannot be null" }
        requireNotNull(id) { "id cannot be null" }
        log.info("IgStandardRepository.read - Attempting to read resource [{}].", id)
        log.info("IgStandardRepository.read - headers: {}", headers)

        val compartment = compartmentFrom(headers)
        val paths = potentialPathsForResource(resourceType, id, compartment)
        for (path in paths) {
            log.info("IgStandardRepository.read - potentialPathsForResource path: {}", path)
            if (!Files.exists(path)) {
                log.info("IgStandardRepository.read - File doesn't exist at [{}]. Continuing loop.", path)
                continue
            }

            val resource = cachedReadResource(path)
            if (resource != null) {
                log.info("IgStandardRepository.read - Found resource [{}].", id)
                return validateResource(resourceType, resource, id)
            }
        }

        log.info("IgStandardRepository.read - Unable to find resource [{}]. Throwing Exception", id)
        throw ResourceNotFoundException(id)
    }

    override fun <T : IBaseResource> create(resource: T, headers: Map<String, String>?): MethodOutcome {
        requireNotNull(resource) { "resource cannot be null" }
        requireNotNull(resource.idElement.idPart) { "resource id cannot be null" }

        val compartment = compartmentFrom(headers)
        val path = preferredPathForResource(resource.javaClass, resource.idElement, compartment)
        writeResource(resource, path)

        return MethodOutcome(resource.idElement, true)
    }

    private fun <T : IBaseResource> validateResource(resourceType: Class<T>, resource: IBaseResource, id: IIdType): T {
        val path = resource.getUserData(SOURCE_PATH_TAG) as Path?

        if (resourceType.simpleName != resource.fhirType()) {
            throw ResourceNotFoundException(
                "Expected to find a resource with type: ${resourceType.simpleName} at path: $path. Found resource with type ${resource.fhirType()} instead."
            )
        }

        if (!resource.idElement.hasIdPart()) {
            throw ResourceNotFoundException(
                "Expected to find a resource with id: ${id.toUnqualifiedVersionless()} at path: $path. Found resource without an id instead."
            )
        }

        if (id.idPart != resource.idElement.idPart) {
            throw ResourceNotFoundException(
                "Expected to find a resource with id: ${id.idPart} at path: $path. Found resource with an id ${resource.idElement.idPart} instead."
            )
        }

        if (id.hasVersionIdPart() && id.versionIdPart != resource.idElement.versionIdPart) {
            throw ResourceNotFoundException(
                "Expected to find a resource with version: ${id.versionIdPart} at path: $path. Found resource with version ${resource.idElement.versionIdPart} instead."
            )
        }

        return resourceType.cast(resource)
    }

    override fun <T : IBaseResource> update(resource: T, headers: Map<String, String>?): MethodOutcome {
        requireNotNull(resource) { "resource cannot be null" }
        requireNotNull(resource.idElement.idPart) { "resource id cannot be null" }

        val compartment = compartmentFrom(headers)
        val preferred = preferredPathForResource(resource.javaClass, resource.idElement, compartment)
        var actual = resource.getUserData(SOURCE_PATH_TAG) as Path? ?: preferred

        if (isExternalPath(actual)) {
            throw ForbiddenOperationException(
                "Unable to create or update: ${resource.idElement.toUnqualifiedVersionless()}. Resource is marked as external, and external resources are read-only."
            )
        }

        if (preferred != actual &&
            encodingBehavior.preserveEncoding == IgStandardEncodingBehavior.PreserveEncoding.OVERWRITE_WITH_PREFERRED_ENCODING
        ) {
            try {
                Files.deleteIfExists(actual)
            } catch (e: IOException) {
                throw UnclassifiedServerFailureException(500, "Couldn't change encoding for $actual")
            }
            actual = preferred
        }

        writeResource(resource, actual)

        return MethodOutcome(resource.idElement, false)
    }

    override fun <T : IBaseResource, I : IIdType> delete(
        resourceType: Class<T>, id: I, headers: Map<String, String>?
    ): MethodOutcome {
        requireNotNull(resourceType) { "resourceType cannot be null" }
        requireNotNull(id) { "id cannot be null" }

        val compartment = compartmentFrom(headers)
        val paths = potentialPathsForResource(resourceType, id, compartment)
        var deleted = false
        for (path in paths) {
            try {
                deleted = Files.deleteIfExists(path)
                if (deleted) break
            } catch (e: IOException) {
                throw UnclassifiedServerFailureException(500, "Couldn't delete $path")
            }
        }

        if (!deleted) throw ResourceNotFoundException(id)

        return MethodOutcome(id)
    }

    @Suppress("UNCHECKED_CAST")
    override fun <B : IBaseBundle, T : IBaseResource> search(
        bundleType: Class<B>,
        resourceType: Class<T>,
        searchParameters: Multimap<String, List<IQueryParameterType>>,
        headers: Map<String, String>?
    ): B {
        val builder = BundleBuilder(fhirContext)
        builder.setType("searchset")

        val compartment = compartmentFrom(headers)
        val resourceIdMap = readDirectoryForResourceType(resourceType, compartment)

        if (searchParameters.isEmpty) {
            resourceIdMap.values.forEach { builder.addCollectionEntry(it) }
            return builder.bundle as B
        }

        val candidates: Collection<T>
        if (searchParameters.containsKey("_id")) {
            candidates = getIdCandidates(searchParameters["_id"], resourceIdMap, resourceType)
            searchParameters.removeAll("_id")
        } else {
            candidates = resourceIdMap.values
        }

        for (resource in candidates) {
            if (allParametersMatch(searchParameters, resource)) {
                builder.addCollectionEntry(resource)
            }
        }

        return builder.bundle as B
    }

    private fun <T : IBaseResource> getIdCandidates(
        idQueries: Collection<List<IQueryParameterType>>, resourceIdMap: Map<IIdType, T>, resourceType: Class<T>
    ): List<T> {
        val idResources = mutableListOf<T>()
        for (idQuery in idQueries) {
            for (query in idQuery) {
                if (query is TokenParam) {
                    val id = Ids.newId<IIdType>(fhirContext, resourceType.simpleName, query.value)
                    val resource = resourceIdMap[id]
                    if (resource != null) idResources.add(resource)
                }
            }
        }
        return idResources
    }

    private fun allParametersMatch(
        searchParameters: Multimap<String, List<IQueryParameterType>>, resource: IBaseResource
    ): Boolean {
        for (nextEntry in searchParameters.entries()) {
            if (!resourceMatcher.matches(nextEntry.key, nextEntry.value, resource)) return false
        }
        return true
    }

    override fun <R : IBaseResource, P : IBaseParameters, T : IBaseResource> invoke(
        resourceType: Class<T>, name: String, parameters: P, returnType: Class<R>, headers: Map<String, String>?
    ): R {
        return invokeOperation(null, resourceType.simpleName, name, parameters)
    }

    override fun <R : IBaseResource, P : IBaseParameters, I : IIdType> invoke(
        id: I, name: String, parameters: P, returnType: Class<R>, headers: Map<String, String>?
    ): R {
        return invokeOperation(id, id.resourceType, name, parameters)
    }

    protected open fun <R : IBaseResource> invokeOperation(
        id: IIdType?, resourceType: String, operationName: String, parameters: IBaseParameters
    ): R {
        checkNotNull(operationProvider) { "No operation provider found. Unable to invoke operations." }
        @Suppress("UNCHECKED_CAST")
        return operationProvider!!.invokeOperation<org.hl7.fhir.instance.model.api.IPrimitiveType<String>, R>(
            this, id, resourceType, operationName, parameters
        )
    }

    protected open fun compartmentFrom(headers: Map<String, String>?): IgStandardRepositoryCompartment {
        if (headers == null) return IgStandardRepositoryCompartment()
        val compartmentHeader = headers[FHIR_COMPARTMENT_HEADER]
        return if (compartmentHeader == null) IgStandardRepositoryCompartment()
        else IgStandardRepositoryCompartment(compartmentHeader)
    }

    protected open fun pathForCompartment(igStandardRepositoryCompartment: IgStandardRepositoryCompartment): String {
        if (igStandardRepositoryCompartment.isEmpty()) return ""
        return "${igStandardRepositoryCompartment.type}/${igStandardRepositoryCompartment.id}"
    }
}
