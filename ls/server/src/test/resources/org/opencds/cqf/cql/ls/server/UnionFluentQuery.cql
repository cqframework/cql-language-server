library UnionFluentQuery

using FHIR version '4.0.1'

include FHIRHelpers version '4.0.1'

context Patient

define function wrapper(e List<Encounter>):
  e

define "Test Expression":
  wrapper(
    ( [Encounter] E
        return E.status
    )
      union ( [Encounter] E
          return E.status
      )
  )
