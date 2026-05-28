library UnionNestedAliasRefTest

using FHIR version '4.0.1'

include FHIRHelpers version '4.0.1'

context Patient

define "Qualifying Visit During Measurement Period":
  ( [Encounter] E
      where exists ( ( E.type ) T
          where T is not null
      )
  )
    union ( [Encounter] E
        where exists ( ( E.type ) T
            where T is not null
        )
    )
