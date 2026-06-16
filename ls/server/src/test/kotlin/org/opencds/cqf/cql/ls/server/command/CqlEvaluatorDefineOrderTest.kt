package org.opencds.cqf.cql.ls.server.command

import org.hl7.elm.r1.ExpressionDef
import org.hl7.elm.r1.VersionedIdentifier
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.opencds.cqf.cql.engine.execution.trace.ExpressionDefTraceFrame
import org.opencds.cqf.cql.engine.execution.trace.TraceFrame

class CqlEvaluatorDefineOrderTest {
    private fun defFrame(
        name: String,
        vararg children: TraceFrame,
    ): ExpressionDefTraceFrame =
        ExpressionDefTraceFrame(
            VersionedIdentifier().withId("TestLib"),
            ExpressionDef().also { it.name = name },
            emptyList(),
            "Patient" to "test-patient",
            null,
            children.toList(),
        )

    @Test
    fun collectDefineOrder_singleFrame_returnsIt() {
        val frames = listOf(defFrame("A"))
        val result = mutableListOf<String>()
        CqlEvaluator.collectDefineOrder(frames, mutableSetOf(), result)
        assertEquals(listOf("A"), result)
    }

    @Test
    fun collectDefineOrder_linearDependency_dependencyFirst() {
        // B is a dependency of A: A.subframes = [B]
        // Post-order: B, A
        val frames = listOf(defFrame("A", defFrame("B")))
        val result = mutableListOf<String>()
        CqlEvaluator.collectDefineOrder(frames, mutableSetOf(), result)
        assertEquals(listOf("B", "A"), result)
    }

    @Test
    fun collectDefineOrder_sharedDependency_deduplicatedOnFirstOccurrence() {
        // Both A and B depend on C. Post-order: C, A, B (C appears only once)
        val frames =
            listOf(
                defFrame("A", defFrame("C")),
                defFrame("B", defFrame("C")),
            )
        val result = mutableListOf<String>()
        CqlEvaluator.collectDefineOrder(frames, mutableSetOf(), result)
        assertEquals(listOf("C", "A", "B"), result)
    }

    @Test
    fun collectDefineOrder_deepChain_leafFirst() {
        // C -> B -> A (A depends on B which depends on C)
        val frames = listOf(defFrame("A", defFrame("B", defFrame("C"))))
        val result = mutableListOf<String>()
        CqlEvaluator.collectDefineOrder(frames, mutableSetOf(), result)
        assertEquals(listOf("C", "B", "A"), result)
    }

    @Test
    fun collectDefineOrder_emptyFrames_returnsEmpty() {
        val result = mutableListOf<String>()
        CqlEvaluator.collectDefineOrder(emptyList(), mutableSetOf(), result)
        assertEquals(emptyList<String>(), result)
    }

    @Test
    fun collectDefineOrder_frameWithNullName_skipped() {
        val unnamed =
            ExpressionDefTraceFrame(
                VersionedIdentifier().withId("TestLib"),
                ExpressionDef(), // name not set -> null
                emptyList(),
                "Patient" to "test",
                null,
                emptyList(),
            )
        val frames = listOf(unnamed, defFrame("A"))
        val result = mutableListOf<String>()
        CqlEvaluator.collectDefineOrder(frames, mutableSetOf(), result)
        assertEquals(listOf("A"), result)
    }
}
