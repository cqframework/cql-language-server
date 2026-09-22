library AdHocOperandTypes

define "Quantity Host":
  from (List{ 5 'mg', 10 'mg' }) Q
    return Q

define "Code Host":
  from (List{ Code { code: '8480-6', system: 'http://loinc.org' } }) C
    return C

define "Concept Host":
  from (List{ Concept { codes: { Code { code: '8480-6', system: 'http://loinc.org' } }, display: 'X' } }) CP
    return CP

define "Ratio Host":
  from (List{ Ratio { numerator: 5 'mg', denominator: 1 'dL' } }) R
    return R

define "Interval Host":
  from (List{ Interval[1, 5], Interval[2, 6] }) IV
    return IV

define "Decimal List Host":
  from (List{ { 1.5, 2.5 }, { 3.5, 4.5 } }) DL
    return DL

define "Date Host":
  from (List{ @2024-01-15, @2024-02-15 }) D
    return D

define "DateTime Host":
  from (List{ @2024-01-15T10:30:00.000 }) DT
    return DT

define "Time Host":
  from (List{ @T10:30:00.000 }) T
    return T