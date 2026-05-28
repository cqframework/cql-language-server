library WithoutQuery

define "Items": {1, 2, 3}

define "Without Clause Test":
  "Items" Item
    without "Items" Extra such that Extra = Item
    return Item
