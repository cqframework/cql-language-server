# Ad-Hoc Evaluate (`Debug Console`) Coverage Matrix

Systematic coverage for the CQL debugger's ad-hoc evaluate path — the Debug Console /
watch expression evaluation implemented in `CqlDebugServer.evaluate(...)` →
`buildAdHocAliases` / `evaluateAdHocExpression` / `submitAdHocEval`.

- **Tier A** — JUnit tests against on-disk fixtures: `StreamingCqlDebugServerAdHocFixtureTest.kt`
  (same package). Run: `./mvnw test -Dtest=StreamingCqlDebugServerAdHocFixtureTest` from
  `_repo/cql-language-server/`.
- **Tier B** — live end-to-end check via `mcp-cql-debug`: `adHocEvalCoverage.integration.test.ts`
  driving a real launched debug session against duplicated fixtures under
  `mcp-cql-debug/test/fixtures/ad-hoc-eval/`. Run per the runbook in
  `mcp-cql-debug/docs/ad-hoc-eval-coverage.md`.
- **Fixtures** — `ls/server/src/test/resources/org/opencds/cqf/cql/ls/server/AdHoc*.cql`
  (duplicated to Tier B's fixture dir). Each fixture is precompiled fail-fast by the test harness,
  so a fixture regression surfaces at setup, not in a later assertion.

## How "what's uncovered" is grep-able

Every matrix row below has a non-blank **Test reference** column. Rows that Tier B can never reach
carry an explicit `n/a` and are flagged in **Known gaps** with a reason. The Tier B step IDs
(`TB-…`) are the `it(...)` titles in `adHocEvalCoverage.integration.test.ts`.

## Matrix

Legend — **(A)** expression category; **(B)** context; **(C)** paused-in library; **(D)** alias
operand runtime value type.

| Row | (A) category | (B) | (C) | (D) | Expression (fixture) | Expected / current behavior | Test reference |
|---|---|---|---|---|---|---|---|
| 1 | literal / arithmetic | watch | primary | — | `1 + 2` (QueryBasics) | `3` | `G1 repl constructs also evaluate under watch context` |
| 2 | define ref | watch | primary | — | `Count("Items")` (QueryBasics) | `3` | `G1 …` |
| 3 | bare alias ref + dotted | watch | primary | allow-listed String | `S + ' world'` (QueryBasics, `S` in registry) | `'hello world'` | `G1 …` |
| 4 | define ref | repl | primary | — | `Count("Items")`, `Count("Sorted By Score")`, `Count("Let and Where")` (QueryBasics) | `3`, `2`, `2` | `G2 query constructs evaluate against the compiled fixture library` |
| 5 | query-as-expression (where) | repl | primary | — | `Count(from "Items" I where I.score >= 90)` | `2` | `G2 …` |
| 6 | query-as-expression (let) | repl | primary | — | `Count(from "Items" I let Doubled: I.score * 2 where Doubled > 180)` | `2` | `G2 …` |
| 7 | query-as-expression (exists) | repl | primary | — | `exists (from "Items" I where I.score >= 95)` | `true` | `G2 …` |
| 8 | query-as-expression (sort by) | repl | primary | — | `First(from "Items" I sort by score desc).name` | `'charlie'` | `G2 …` |
| 9 | system literal types | repl | primary | — | `Interval[1, 10]`; `Tuple { a: 1, b: 'two' }` | `[1, 10]`; `Tuple { a: 1, b: 'two' }` | `G2 …` |
| 10 | terminology literal (code ref, value) | repl | primary | — | `First({ "Systolic BP Code" }).code` (Terminology) | `'8480-6'` | `G2 terminology literals evaluate against the compiled fixture library` |
| 11 | terminology literal (quantity alias define) | repl | primary | — | `Count("Quantity Alias")` (Terminology) | `2` | `G2 terminology …` |
| 12 | terminology literal (nested List\<Tuple\> define) | repl | primary | — | `Count("Nested Tuples")` (Terminology) | `2` | `G2 terminology …` |
| 13 | definition-rooted identifier shortcut | repl | primary | — | `"Code Selector".code`; `"Ratio Literal".numerator.value` (Terminology) | `Identifier doesn't exist (CQL is case-sensitive)` — **known gap #2** | `G2 terminology …` |
| 14 | dotted property on alias (allow-listed) | repl | primary | Quantity | `Q.value = 5` (OperandTypes) | `true` | `G3 operand allow-list pass-through evaluates property chains` |
| 15 | dotted property on alias (allow-listed) | repl | primary | Code | `C.code = '8480-6'` | `true` | `G3 …` |
| 16 | dotted property on alias (allow-listed) | repl | primary | Concept | `CP.display = 'X'` | `true` | `G3 …` |
| 17 | dotted property on alias (allow-listed) | repl | primary | Ratio | `R.numerator.value = 5` | `true` | `G3 …` |
| 18 | dotted property on alias (allow-listed) | repl | primary | Interval | `IV.low = 1` | `true` | `G3 …` |
| 19 | function call over nested list alias | repl | primary | list\<list\<Decimal\>\> | `Count(DL) = 2` | `true` | `G3 …` |
| 20 | bare alias ref (temporal, registry hit) | repl | primary | Date/DateTime | `D`; `DT` | `@2024-01-15`; contains `2024-01-15`/`10:30` | `G3 temporal aliases bind as real typed values` |
| 21 | typed alias in expression | repl | primary | Date | `Coalesce(D, @2024-01-16)` (`D`→`System.Date`) | `@2024-01-15` (real Date passthrough; also `year from D = 2024` → `true`, `D + 1 days` → `@2024-01-16`, `D between …` → `true`) | `G3 temporal …` |
| 22 | out-of-allow-list coercion throw | repl | primary | String→Quantity/Code | `Q.value`; `C.code` (raw String bound to Quantity/Code alias) | `Runtime error:` containing path, **not** `Cannot bind alias` | `G3 coerced-string operand hits PropertyEvaluator runtime throw not converter rejection` |
| 23 | included-library alias, qualified key | repl | **included** | Tuple | `T.id > 0` qualified key (`AdHocIncludedLib.T`) | `true` (fixed; JUnit-verified — live verification blocked by known-gap (a): breakpoints in an included library's own file never pause) | `G4 included-library alias resolves qualified translator type` |
| 24 | included-library alias, positive control | repl | **included** | Tuple | `T.id > 0` bare key (`T`) | `true` | `G4 …` |
| 25 | timeout (no parked engine thread) | repl | primary | — | `1 + 1` (QueryBasics) | `not available` after ~`AD_HOC_EVAL_TIMEOUT_SECONDS` (≥ 9 s elapsed) | `G5 ad-hoc eval times out with no parked engine thread` |
| 26 | literal / arithmetic | repl | primary (FHIR) | — | `1 + 2` (FhirQuery) | `3` | `G6 FHIR retrieves degrade at runtime but compile cleanly` |
| 27 | retrieve degrade | repl | primary (FHIR) | — | `exists (from [Encounter] E where E.status = 'finished')` | `Runtime error:` (no data provider) | `G6 …` |
| 28 | define ref over retrieve, degrade | repl | primary (FHIR) | — | `Count("Ambulatory Encounters")` | `Runtime error:` | `G6 …` |
| 29 | system-qualified struct literal | repl | primary (FHIR) | — | `System.Quantity { value: 5.0, unit: 'mg' }` | evaluates; content contains `5` and `mg` (**known gap #4**) | `G6 …` |
| 30 | bare quantity literal (FHIR scope) | repl | primary (FHIR) | — | `5 'mg'` | FHIR.Quantity ClassInstance JSON, content contains `5`/`mg` (**known gap #7**) | `G6 …` |
| 31 | dotted property chain on FHIR ClassInstance alias | repl | primary (FHIR) | FHIR.Encounter | `Enc.id.value = 'e1'` | `true` | `G6 FHIR class-instance alias resolves property chain` |
| 32 | extractIdentifiers lexical edge cases | repl | primary | — | 9 adversarial expressions (strings/comments/keywords/underscore) | pinned token lists | `G7 extractIdentifiers tokens match lexical edge cases` |
| 33 | idiom-fragment compile (5 VitalSigns fragments) | repl | primary (FHIR) | — | VitalSigns fixture: status filter, `(effective as dateTime).value`, temporal phrase, component/singleton-from/`C.code ~`, threshold quantity | fixture compiles | `G8 vital signs fixture compiles all idiom fragments and code equivalence evaluates` |
| 34 | code equivalence (`~` on System.Code) | repl | primary (FHIR) | — | `exists (({ "Systolic blood pressure", "Diastolic blood pressure" }) C where C ~ …)`; negative variant | `true` / `false` | `G8 …` |
| 35 | retrieval degrade (VitalSigns) | repl | primary (FHIR) | — | `Count("Blood Pressure Reading")`; `Count("BP Retrieve With Status")` | `Runtime error:` | `G8 vital signs retrieval degrades at runtime` |

## Tier B reachability

Tier B (mcp-cql-debug) pins the **deterministic, data-free repl rows** through real `cql_debug_*`
sessions (`adHocEvalCoverage.integration.test.ts`, step IDs TB-01…18 in the `it(...)` titles):
TB-01…09 (rows 1/4–9), TB-10…13 (rows 10–13), TB-14…15 (rows 33–34), TB-16 (row 26) + TB-17
(live data-capture for the retrieve degrade), TB-18a/b/c (rows 23–24 reachable subset). The
**watch-context rows (1–3)** are never reachable from Tier B: `dapSession.ts` always sends
`context: 'repl'` when `frameId` is set. They are JUnit-only by design.

Verified live divergence on top of the JUnit pins: TB-17 returns `false` for
`Exists(from [Encounter] E)` — a live session binds an (empty) data provider, so retrieves come
back *empty* instead of the JUnit-harness `Runtime error:` (rows 27–28, 35). The two are not
equal; the runbook tells you which you're seeing.

## Known gaps (current behavior pinned above, not fixed here)

1. **`watch` is JUnit-only** — `dapSession.ts` sends `context: 'repl'` only, so Tier B cannot cross
   the watch half of the matrix. Matches rows 1–3.
2. **Definition-rooted identifier-shaped expressions short-circuit before compilation** —
   `EvaluateHelper.resolveMissingIdentifier` (`EvaluateHelper.kt:355`) fires before the ad-hoc
   compile for any `identifier[.property]*` expression whose root is not a registry stack variable,
   returning `Identifier doesn't exist (CQL is case-sensitive)`. A paused library's *defines* are
   not registry variables, so `"SomeDefine".prop` never reaches the synthetic compile. Function /
   call-wrapped forms (`Count("SomeDefine")`, `First({...}).code`) evaluate fine. Row 13, TB-13.
   Delimited forms are exempt: a *qualified* delimited root (`AdHocIncludedLib."Tuple Items"`) is
   not matched by `identifier[.property]*` (`parseIdentifier` returns null on the quoted segment),
   so it compiles and evaluates — TB-18c pins that contrast.
3. **Engine `resolveExpressionRef` assumes sorted `statements.def`** —
   `Libraries.resolveExpressionRef` binary-searches the def list
   (`engine …/execution/Libraries.kt:58`); the LS in-memory compiler preserves *source* order. An
   ExpressionRef to a define at a non-discoverable position throws
   `Could not resolve expression reference '…' in library '…'`. Fixtures whose defines are
   ExpressionRef targets therefore declare them in sorted-by-name order (the order the ELM CLI
   emits to disk) — `AdHocTerminology.cql` documents this inline. Rows 4, 10–12.
4. **FHIR-scope bare `Quantity{…}` struct literal does not compile** — in `using FHIR` scope,
   `Quantity { value: 5.0, unit: 'mg' }` resolves to `FHIR.Quantity` whose `value` element resists
   even a `System.Decimal` literal (`Expected an expression of type 'decimal', but found
   'System.Decimal'`). The qualified `System.Quantity { value: 5.0, unit: 'mg' }` form compiles and
   is the pinned row (content-asserted). Row 29.
5. **`sort by` resolves against the query result element, not the from-alias** — cql-to-elm rejects
   `sort by I.score` (`Could not resolve identifier I …`); the unqualified `sort by score` is the
   working form (verified against `cql.g4` `querySource`/`sortClause`). Row 8; the fixture's
   "Sorted By Score" define uses the same shape.
6. **`Coalesce` has no single-tuple signature** — `Coalesce(D)` is a compile error; the two-arg
   `Coalesce(D, @2024-01-16)` form is pinned. Row 21.
7. **Bare quantity literals resolve to FHIR.Quantity in FHIR scope** — `5 'mg'` becomes a
   ClassInstance re-encoded as FHIR JSON (`Quantity { unit: 'mg', value: 5 }`), confirming default
   model wins for bare types. Rows 29–30.
8. **Qualified included-library alias types now resolve; the exact tuple-alias frame stays
   JUnit-only** — `resolveAdHocType` adopts the qualified-then-bare lookup (`$frameLibId.$name`
   first), so the `AdHocIncludedLib.T` key resolves and rows 23–24 are both `true` (JUnit-verified).
   Two live constraints still keep the *exact* tuple-alias frame JUnit-only (G4 pins it
   deterministically): (a) **breakpoints on the included library's own file do not pause** — the
   `StreamingBreakpointHandler` logs `libBreakpoints=null` for included libraries in CONTINUE mode
   (observed in the TB-18 trace), so a paused `T` frame cannot be manufactured live; (b) only
   primary-library pauses are reachable, where the reachable subset across the include is
   TB-18a/b/c (qualified define refs evaluate; the bare qualified-delimited root is not
   identifier-shaped, per gap 2).
9. **Query-source list selectors need parentheses** — `cql.g4` `querySource` accepts
   `retrieve | qualifiedIdentifierExpression | '(' expression ')'`, so an inline
   `{ code1, code2 }` list source must be wrapped: `(( { … } ) C where …)`. Row 34.

## Out of scope (explicit, not accidental)

- **Hover context**: ad-hoc `hover` is deliberately not part of this matrix (the Debug Console path
  only exercises `repl`/`watch`).
- **Data-provider-backed retrieves**: fixtures never bind a data provider; retrieve rows pin the
  documented "Runtime error" degradation. Row 27–28, 35.