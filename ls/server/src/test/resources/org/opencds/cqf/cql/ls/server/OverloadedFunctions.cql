library OverloadedFunctions version '1.0.0'

define function "Add"(x Integer):
    x + 1

define function "Add"(x Integer, y Integer):
    x + y

define function "Add"(x Integer, y Integer, z Integer):
    x + y + z

define "UseUnary": "Add"(1)
define "UseBinary": "Add"(1, 2)
define "UseTernary": "Add"(1, 2, 3)
