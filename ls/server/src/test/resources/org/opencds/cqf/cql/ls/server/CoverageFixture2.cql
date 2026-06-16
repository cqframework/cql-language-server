library CoverageFixture2 version '1.0.0'

codesystem "SNOMEDCT": 'http://snomed.info/sct'
code "ABC": '123' from "SNOMEDCT"
concepts "MyConcept": { "ABC" }
valueset "Test VS": 'http://example.org/fhir/ValueSet/Test'

parameter "MyParam" default 42

define "CodeRefVal": "ABC"
define "CodeSystemRefVal": "SNOMEDCT"
define "ConceptRefVal": "MyConcept"
define "ParamRefVal": "MyParam"

define "AliasRefVal": {1, 2, 3} X where X > 1

define "ExistsVal": exists ({1, 2, 3}) X where X = 1

define "ContainsVal": {1, 2, 3} contains 1

define "CollapseVal": collapse ({Interval[1, 5], Interval[6, 10]})
