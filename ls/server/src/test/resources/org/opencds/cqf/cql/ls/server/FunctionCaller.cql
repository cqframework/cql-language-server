library FunctionCaller version '1.0.0'

include FunctionLib version '1.0.0' called FL

define "UseValue":
    FL."MyValue" + 1

define "UseFunction":
    FL."Double"(3)
