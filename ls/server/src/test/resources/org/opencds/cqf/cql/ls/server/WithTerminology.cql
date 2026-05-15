library WithTerminology version '1.0.0'

codesystem "SNOMEDCT": 'http://snomed.info/sct'

valueset "Beta Blocker Therapy": 'http://cts.nlm.nih.gov/fhir/ValueSet/2.16.840.1.113883.3.526.3.1174'

define "UseVS":
    "Beta Blocker Therapy"

define "UseCS":
    Code '12345' from "SNOMEDCT"
