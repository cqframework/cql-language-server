library BinaryOpQuery version '1.0.0'

define "AndExpr": 1 > 0 and 2 > 1
define "NotExpr": not (1 > 0)
define "IsNullExpr": 1 is null
define "IsNotNullExpr": 1 is not null
define "InExpr": 1 in {1, 2, 3}
