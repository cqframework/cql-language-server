library ChoicePropertyWithClause

using FHIR version '4.0.1'

include FHIRHelpers version '4.0.1'

context Patient

define "Qualifying Encounters":
  [Encounter] E

define "Depression Screening":
  [Observation] Screening
    with "Qualifying Encounters" QE
      such that Screening.value is not null
