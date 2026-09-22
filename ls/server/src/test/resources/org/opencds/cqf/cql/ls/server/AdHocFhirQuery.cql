library AdHocFhirQuery

using FHIR version '4.0.1'

include FHIRHelpers version '4.0.1'

valueset "Ambulatory Encounter": 'http://cts.nlm.nih.gov/fhir/ValueSet/2.16.840.1.113883.3.464.1003.101.12.1061'

context Patient

define "Encounter Period Test":
  [Encounter] E
    return E.period

define "Ambulatory Encounters":
  ["Encounter": "Ambulatory Encounter"]

define "Most Recent Encounter":
  Last([Encounter])

define "Joined Encounters And Observations":
  from [Encounter] E, [Observation] O
    where E.status = 'finished'
      and O.status = 'final'
      and O.issued during E.period
    return Tuple { encounter: E, observation: O }