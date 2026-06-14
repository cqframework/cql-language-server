library CoverageFixture3 version '1.0.0'

using FHIR version '4.0.1'
include FHIRHelpers version '4.0.1'
valueset "Test VS": 'http://example.org/fhir/ValueSet/Test'
context Patient

define "RetrieveWithToList":
  [CommunicationRequest: "Test VS"]

define "PropertyAccessWithScope":
  [Encounter] E where E.status = 'finished'
