library FunctionBody version '1.0.0'

define function "Identity"(x System.String):
    x

define function "Denied Reason"(boolean System.Boolean, string System.String):
    if boolean
        then ' - Individual does not have ' + string
    else ''

define "UseIdent":
    "Identity"('Hello')
