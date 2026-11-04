library FunctionParams version '1.0.0'

using FHIR version '4.0.1'

define function "WithList"(conditions List<FHIR.Condition>):
    conditions.Count()

define function "ScalarOnly"(val System.Integer):
    val + 1

define function "MultiParam"(a System.Integer, b List<FHIR.Condition>):
    a + b.Count()

define "UseWithList":
    "WithList"({ FHIR.Condition { id: FHIR.id('1') } })
