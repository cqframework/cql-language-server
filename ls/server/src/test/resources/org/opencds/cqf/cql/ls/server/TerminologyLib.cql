library TerminologyLib version '1.0.0'

codesystem "SNOMEDCT": 'http://snomed.info/sct'

code "Venous foot pain, left": '12345' from "SNOMEDCT"

concept "Left Foot Pain": { "Venous foot pain, left" }

valueset "Ambulatory Encounter": 'http://example.org/fhir/ValueSet/ambulatory-encounter'
