library PrivateLib version '1.0.0'

private define "PrivateDef":
    42

define "PublicDef":
    1

define private function "PrivateFn"(x Integer):
    x * 2

define function "PublicFn"(x Integer):
    x
