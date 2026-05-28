library WithFhirQuery

using FHIR version '4.0.1'

include FHIRHelpers version '4.0.1'

valueset "Ambulatory Encounter": 'http://cts.nlm.nih.gov/fhir/ValueSet/2.16.840.1.113883.3.464.1003.101.12.1061'

context Patient

define fluent function isInProgress(e Encounter):
  e.status = 'in-progress'

define "Encounter Period Test":
  [Encounter] E
    return E.period

define "In Progress Encounters":
  [Encounter] E
    where E.isInProgress()

define "Ambulatory Encounters":
  ["Encounter": "Ambulatory Encounter"]

define "Most Recent Encounter":
  Last([Encounter])

define "Source Based Period":
  "Most Recent Encounter".period
