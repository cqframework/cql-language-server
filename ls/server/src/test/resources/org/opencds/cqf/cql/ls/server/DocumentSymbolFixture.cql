library DocumentSymbolFixture version '1.0.0'

using FHIR version '4.0.1'

parameter "TestParam" String default 'hello'

codesystem "TestCS": 'http://example.org/test/codesystem'

valueset "TestVS": 'http://example.org/test/valueset'

code "TestCode": '12345' from "TestCS" display 'Test Code'

concept "TestConcept": { "TestCode" }

context Patient

define "TestDefine": 1