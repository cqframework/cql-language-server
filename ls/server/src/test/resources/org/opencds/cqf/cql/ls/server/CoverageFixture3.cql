library CoverageFixture3 version '1.0.0'

codesystem "SNOMEDCT": 'http://snomed.info/sct'
valueset "Test VS": 'http://example.org/fhir/ValueSet/Test'
code "ABC": '123' from "SNOMEDCT"

define "ExpandVal": expand { Interval[1, 3], Interval[5, 7] }

define "AggregateSum": Sum({ 1, 2, 3 })

define "AggregateCount": Count({ 1, 2, 3 })

define "AggregateMin": Min({ 1, 2, 3 })

define "AggregateMax": Max({ 1, 2, 3 })