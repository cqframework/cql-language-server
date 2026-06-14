library CoverageFixture5 version '1.0.0'

codesystem "SNOMEDCT": 'http://snomed.info/sct'
valueset "Test VS": 'http://example.org/fhir/ValueSet/Test'
code "ABC": '123' from "SNOMEDCT"

// Covers: If branch in childrenOf, ifChildren()
define "IfVal":
  if 1 > 0 then 'yes' else 'no'

// Covers: TernaryExpression (Between -> And in compiled output)
define "BetweenVal":
  1 between 0 and 5

// Covers: FunctionDef rendering (renderFunctionDef)
define function "MyFunc"(x Integer):
  x + 1