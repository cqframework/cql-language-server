library WithLastQuery

using FHIR version '4.0.1'

include FHIRHelpers version '4.0.1'

context Patient

define "Most Recent Encounter":
  Last([Encounter] E
    where E.status = 'finished'
  )
