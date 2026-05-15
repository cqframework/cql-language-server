library WithQuery

define "Items": {1, 2, 3}

define "Items Alias Test":
  "Items" Item
    return Item

define "With Clause Test":
  "Items" Item
    with "Items" Extra such that Extra > Item
    return Item

define "Tuples":
  { { name: 'a', value: 1 }, { name: 'b', value: 2 } }

define "Alias Property Scope Test":
  "Tuples" T
    return T.name
