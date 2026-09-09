package org.opencds.cqf.cql.debug

import ca.uhn.fhir.context.BaseRuntimeElementCompositeDefinition
import ca.uhn.fhir.context.FhirContext
import org.hl7.fhir.instance.model.api.IBase
import org.hl7.fhir.instance.model.api.IBaseEnumeration
import org.hl7.fhir.instance.model.api.IBaseResource
import org.hl7.fhir.instance.model.api.IPrimitiveType
import org.hl7.fhir.r4.model.EnumFactory
import org.hl7.fhir.r4.model.Enumeration
import org.hl7.fhir.r4.model.Resource
import org.opencds.cqf.cql.engine.fhir.fhirModelNamespaceUri
import org.opencds.cqf.cql.engine.fhir.model.R4FhirModelResolver
import org.opencds.cqf.cql.engine.runtime.ClassInstance
import org.opencds.cqf.cql.engine.runtime.Value
import org.slf4j.LoggerFactory
import java.lang.reflect.Modifier
import java.util.concurrent.ConcurrentHashMap
import org.opencds.cqf.cql.engine.runtime.Boolean as CqlBoolean
import org.opencds.cqf.cql.engine.runtime.Date as CqlDate
import org.opencds.cqf.cql.engine.runtime.DateTime as CqlDateTime
import org.opencds.cqf.cql.engine.runtime.Decimal as CqlDecimal
import org.opencds.cqf.cql.engine.runtime.Integer as CqlInteger
import org.opencds.cqf.cql.engine.runtime.List as CqlList
import org.opencds.cqf.cql.engine.runtime.String as CqlString

/**
 * Converts a [ClassInstance] whose type namespace is the FHIR model namespace back into a real HAPI
 * R4 object. That is the shape the CQL engine produces when it evaluates a Retrieve of a FHIR
 * resource — or a FHIR composite element such as a `Period`, `CodeableConcept`, or `Reference` —
 * modelled as an instance of `FhirModelResolver.toCqlValue`.
 *
 * [ClassInstance] is a generic engine value (a QName `type` plus an element map) with no FHIR
 * meaning of its own; the FHIR-ness of a particular instance lives entirely on its `type` QName
 * namespace (`http://hl7.org/fhir`). [convert] only processes instances in that namespace and
 * returns null for anything else — including other models such as QDM, which the language server
 * does not support for debugging and for which there is no HAPI-style object model to convert to.
 *
 * This is a local fork of `CqlFhirParametersConverter.toFhirValue` whose type resolution also
 * understands resource-local types that the shared `FhirModelResolver.resolveType` cannot see:
 * FHIR enums (e.g. `MedicationAdministrationStatus`) and backbone elements
 * (e.g. `MedicationAdministrationPerformerComponent`) are nested classes of their enclosing
 * resource rather than top-level datatypes, so a real retrieved resource currently falls through
 * to a raw `ClassInstance { ... }` dump. Display-only: no behavior of the evaluation path changes.
 *
 * TODO(versions): R4-only for now. To support STU3/R5 later, parameterize by FHIR version: pick the
 * model resolver via `FhirModelResolverCache.resolverForVersion(fhirContext.version.version)`,
 * derive the HAPI model package from `resolver.packageNames.first()`, build the enum wrapper
 * reflectively from `$modelPackage.Enumeration`/`$modelPackage.EnumFactory`, and thread the session
 * version into [VariableResolver] through a volatile active context (see its `fhirContext` field).
 */
class FhirClassInstanceConverter(
    private val fhirContext: FhirContext = FhirContext.forR4Cached(),
) {
    private companion object {
        val log = LoggerFactory.getLogger(FhirClassInstanceConverter::class.java)

        // TODO(versions): R4-only. Derive from the session resolver instead:
        // `FhirModelResolverCache.resolverForVersion(fhirContext.version.version).packageNames.first()`.
        val modelPackage: String = Resource::class.java.packageName
    }

    // TODO(versions): R4-only. Select per session version via `FhirModelResolverCache.resolverForVersion`.
    private val modelResolver = R4FhirModelResolver(fhirContext)

    /**
     * simpleName -> class index built from every resource definition the context knows, plus the
     * shared `Enumerations` container. Resource-local classes (enums, backbone components,
     * their EnumFactory counterparts) live here as declared classes.
     */
    private val simpleNameIndex: Map<String, Class<*>> by lazy { buildSimpleNameIndex() }

    private val typeCache: ConcurrentHashMap<String, Class<*>?> = ConcurrentHashMap()

    /** Converts [value] to a HAPI R4 object, or null if it is not FHIR-namespace or conversion fails. */
    fun convert(value: ClassInstance): IBase? {
        // Deliberate boundary: only FHIR-namespace instances convert. Other models (e.g. QDM) have
        // no HAPI object model to convert to, so they pass through and render as structured values.
        if (value.type.namespaceURI != fhirModelNamespaceUri) return null
        return try {
            toFhirValue(value, parentName = null, rootName = value.type.localPart) as IBase
        } catch (e: Exception) {
            log.debug("Could not convert {} to FHIR: {}", value.type.localPart, e.message)
            null
        }
    }

    private fun toFhirValue(
        valueToConvert: Value?,
        parentName: String?,
        rootName: String,
    ): IBase {
        val classInstance =
            valueToConvert as? ClassInstance
                ?: throw IllegalArgumentException(
                    "Expected a FHIR ClassInstance but found ${valueToConvert?.javaClass?.name}",
                )
        val typeName = classInstance.type.localPart
        val clazz =
            resolveType(typeName, parentName, rootName)
                ?: throw IllegalArgumentException("Could not resolve FHIR type: $typeName")

        val instance: IBase =
            try {
                if (clazz.isEnum) {
                    createEnumInstance(clazz)
                } else {
                    clazz.getDeclaredConstructor().newInstance() as? IBase
                        ?: throw IllegalStateException("Not an IBase: ${clazz.name}")
                }
            } catch (e: Exception) {
                throw IllegalArgumentException("Could not create instance of $typeName", e)
            }

        if (instance is IBaseEnumeration<*>) {
            val enumValue = classInstance["value"]
            if (enumValue is CqlString) {
                instance.valueAsString = enumValue.value
            }
            return instance
        }

        if (instance is IPrimitiveType<*>) {
            setPrimitiveValue(classInstance["value"], instance)
            return instance
        }

        val definition = compositeDefinition(clazz)

        for (child in definition.children) {
            val elementValue = classInstance[child.elementName]
            if (elementValue == null) {
                continue
            }
            if (elementValue is CqlList) {
                for (item in elementValue) {
                    if (item != null) {
                        child.mutator.addValue(instance, toFhirValue(item, typeName, rootName))
                    }
                }
            } else {
                child.mutator.addValue(instance, toFhirValue(elementValue, typeName, rootName))
            }
        }
        return instance
    }

    private fun resolveType(
        typeName: String,
        parentName: String?,
        rootName: String,
    ): Class<*>? {
        val key = "$rootName|$typeName"
        typeCache[key]?.let { return it }

        // Top-level datatypes and resources, as the shared resolver sees them.
        fhirContext.getElementDefinition(typeName)?.let { clazz ->
            typeCache[key] = clazz.implementingClass
            return clazz.implementingClass
        }
        try {
            return fhirContext.getResourceDefinition(typeName).implementingClass
        } catch (_: Exception) {
            // Fall through to the resource-local candidates.
        }

        // Resource-local types: `Class.forName` first, in case the class loaded earlier resolved
        // to a cached result; then exact simple-name index hit; then case-insensitive index hit.
        val nested =
            buildList {
                if (parentName != null) add("$modelPackage.$parentName$$typeName")
                if (rootName != parentName) add("$modelPackage.$rootName$$typeName")
            }
        for (candidate in nested) {
            try {
                return Class.forName(candidate).also { typeCache[key] = it }
            } catch (_: ClassNotFoundException) {
                // Try the next candidate.
            }
        }
        for (candidate in listOf("$modelPackage.Enumerations$$typeName", "$modelPackage.$typeName")) {
            try {
                return Class.forName(candidate).also { typeCache[key] = it }
            } catch (_: ClassNotFoundException) {
                // Try the next candidate.
            }
        }

        simpleNameIndex[typeName]?.let {
            typeCache[key] = it
            return it
        }
        simpleNameIndex.entries.firstOrNull { it.key.equals(typeName, ignoreCase = true) }?.let {
            typeCache[key] = it.value
            return it.value
        }

        typeCache[key] = null
        return null
    }

    private fun compositeDefinition(
        clazz: Class<*>,
    ): BaseRuntimeElementCompositeDefinition<*> =
        (fhirContext.getElementDefinition(clazz as Class<out IBase>) as? BaseRuntimeElementCompositeDefinition<*>)
            ?: fhirContext.getResourceDefinition(clazz as Class<out IBaseResource>)

    private fun setPrimitiveValue(
        primitiveValue: Value?,
        instance: IPrimitiveType<*>,
    ) {
        when (primitiveValue) {
            is CqlDateTime -> modelResolver.setPrimitiveValue(primitiveValue, instance)
            is CqlDate -> modelResolver.setPrimitiveValue(primitiveValue, instance)
            is CqlBoolean -> modelResolver.setPrimitiveValue(primitiveValue.value, instance)
            is CqlInteger -> modelResolver.setPrimitiveValue(primitiveValue.value, instance)
            is CqlDecimal -> modelResolver.setPrimitiveValue(primitiveValue.value, instance)
            is CqlString -> modelResolver.setPrimitiveValue(primitiveValue.value, instance)
            else ->
                if (primitiveValue != null) {
                    modelResolver.setPrimitiveValue(primitiveValue.toString(), instance)
                }
        }
    }

    private fun <T : Enum<T>> createEnumInstance(clazz: Class<*>): IBase {
        // TODO(versions): R4-only. The R5/DSTU3 model packages define the same `Enumeration`/`EnumFactory`
        // shapes, so this can be `Class.forName("$modelPackage.Enumeration")` built with the matching
        // `$modelPackage.EnumFactory` once the converter is version-parameterized.
        val factoryClass = Class.forName(clazz.name + "EnumFactory")

        @Suppress("UNCHECKED_CAST")
        val factory: EnumFactory<T> = factoryClass.getDeclaredConstructor().newInstance() as EnumFactory<T>
        return Enumeration(factory)
    }

    private fun buildSimpleNameIndex(): Map<String, Class<*>> {
        val index = HashMap<String, Class<*>>()
        val anchors = mutableSetOf<Class<*>>()
        fhirContext.getResourceTypes().forEach { name ->
            try {
                anchors.add(fhirContext.getResourceDefinition(name).implementingClass)
            } catch (_: Exception) {
                // Skip definitions the context can't resolve for this model.
            }
        }
        try {
            anchors.add(Class.forName("$modelPackage.Enumerations"))
        } catch (_: ClassNotFoundException) {
            // No shared enums container in this model — resource-local types still covered.
        }
        for (anchor in anchors) {
            index.putIfAbsent(anchor.simpleName, anchor)
            anchor.declaredClasses.filter { cls ->
                val modifiers = cls.modifiers
                cls.isEnum ||
                    (
                        modifiers and Modifier.PUBLIC != 0 &&
                            !cls.isSynthetic &&
                            cls.simpleName.endsWith("Component")
                    )
            }.forEach { cls -> index.putIfAbsent(cls.simpleName, cls) }
        }
        return index
    }
}
