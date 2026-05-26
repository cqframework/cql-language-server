library AliasInExistsQuery

using FHIR version '4.0.1'
include FHIRHelpers version '4.0.1'

context Patient

define "Qualifying Encounter During Measurement Period":
  [Encounter] E
    where E.status = 'finished'

define "Has Adolescent Depression Screening":
  exists ( [Encounter] AdolescentScreening
      with "Qualifying Encounter During Measurement Period" QualifyingEncounter
        such that QualifyingEncounter.status is not null
  )
