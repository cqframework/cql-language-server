library AdHocTerminology

codesystem "LOINC": 'http://loinc.org'

valueset "Systolic Blood Pressure": 'http://cts.nlm.nih.gov/fhir/ValueSet/2.16.840.1.113883.3.600.1.1525'

code "Systolic BP Code": '8480-6' from "LOINC" display 'Systolic blood pressure'

concept "Systolic Concept": { "Systolic BP Code" } display 'Systolic blood pressure'

// Defines are declared in sorted-by-name order (as the ELM CLI emits libraries). The engine's
// Libraries.resolveExpressionRef binary-searches statements.def assuming sorted order, while the
// in-memory LS compiler preserves source order — so a define referenced via ExpressionRef must sit
// at a binary-search-discoverable position (see AD_HOC_EVAL_COVERAGE.md known gaps).
define "Choice Shaped":
  if "Use Code" then Code { code: '8480-6', system: 'http://loinc.org' }
  else Quantity { value: 5, unit: 'mg' }

define "Code Literal": Code { code: '8480-6', system: 'http://loinc.org' }

define "Code Selector": Code '8480-6' from "LOINC"

define "Concept Literal":
  Concept { codes: { Code { code: '8480-6', system: 'http://loinc.org' } }, display: 'Systolic blood pressure' }

define "Nested Tuples":
  { Tuple { id: 1, payload: Tuple { code: Code { code: '8480-6', system: 'http://loinc.org' }, value: 120 } },
    Tuple { id: 2, payload: Tuple { code: Code { code: '8462-4', system: 'http://loinc.org' }, value: 80 } } }

define "Quantity Alias":
  from (List{ 5 'mg', 10 'mg' }) Q
    return Q

define "Quantity Literal": 5 'mg'

define "Ratio Literal":
  Ratio { numerator: 5 'mg', denominator: 1 'dL' }

define "Use Code": true

define "Use VS": "Systolic Blood Pressure"