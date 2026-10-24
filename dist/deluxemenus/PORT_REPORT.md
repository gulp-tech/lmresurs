# Порт меню DeluxeMenus под Bedrock

| Меню | size | Иконок заменено | hex→legacy | Повторов цвета убрано | Рамка +слотов |
| --- | --- | --- | --- | --- | --- |
| `donate.yml` | 54 → 54 | 0 | 14 | 1 | 0 |
| `events.yml` | 45 → 54 | 0 | 24 | 0 | 9 |
| `keys.yml` | 54 → 54 | 3 | 72 | 10 | 0 |
| `menu.yml` | 54 → 54 | 2 | 67 | 48 | 0 |
| `podryvnik.yml` | 27 → 27 | 0 | 51 | 10 | 0 |
| `privat_shop.yml` | 27 → 27 | 0 | 67 | 11 | 0 |
| `shop.yml` | 45 → 54 | 1 | 8 | 0 | 9 |
| `shop_enchants.yml` | 45 → 54 | 0 | 20 | 0 | 9 |
| `shop_items.yml` | 45 → 54 | 1 | 26 | 0 | 9 |
| `shop_pickaxes.yml` | 45 → 54 | 0 | 20 | 0 | 9 |
| `shop_potions.yml` | 45 → 54 | 4 | 26 | 0 | 9 |
| `shop_spheres.yml` | 45 → 54 | 0 | 29 | 0 | 9 |
| `shop_talismans.yml` | 54 → 54 | 12 | 74 | 1 | 0 |
| `titles.yml` | 27 → 27 | 0 | 42 | 1 | 0 |

## Заменённые иконки

### `keys.yml`

| Предмет | Материал | model_data_component |
| --- | --- | --- |
| Ключ Талисманов | `TRIAL_KEY` | `floats: [14]` |
| Ключ Ресурсов | `TRIAL_KEY` | `floats: [3]` |
| Ключ Сфер | `TRIAL_KEY` | `floats: [13]` |

### `menu.yml`

| Предмет | Материал | model_data_component |
| --- | --- | --- |
| Информация | `PAPER` | `floats: [22]` |
| Донат-магазин /shop | `PAPER` | `floats: [29]` |

### `shop.yml`

| Предмет | Материал | model_data_component |
| --- | --- | --- |
| Раздел талисманы | `PAPER` | `floats: [42]` |

### `shop_items.yml`

| Предмет | Материал | model_data_component |
| --- | --- | --- |
| Капкан | `PAPER` | `floats: [40]` |

### `shop_potions.yml`

| Предмет | Материал | model_data_component |
| --- | --- | --- |
| Зелье ярости | `POTION` | `floats: [1]` |
| Боевое зелье | `POTION` | `floats: [2]` |
| Зелье скорости | `POTION` | `floats: [3]` |
| Зелье защиты | `POTION` | `floats: [4]` |

### `shop_talismans.yml`

| Предмет | Материал | model_data_component |
| --- | --- | --- |
| Талисман воина | `TOTEM_OF_UNDYING` | `floats: [6]` |
| Талисман берсерка | `TOTEM_OF_UNDYING` | `floats: [8]` |
| Талисман убийцы | `TOTEM_OF_UNDYING` | `floats: [7]` |
| Талисман ярости | `TOTEM_OF_UNDYING` | `floats: [2]` |
| Талисман бегуна | `TOTEM_OF_UNDYING` | `floats: [10]` |
| Талисман охотника | `TOTEM_OF_UNDYING` | `floats: [9]` |
| Талисман странника | `TOTEM_OF_UNDYING` | `floats: [4]` |
| Талисман жизни | `TOTEM_OF_UNDYING` | `floats: [1]` |
| Талисман танка | `TOTEM_OF_UNDYING` | `floats: [5]` |
| Талисман стража | `TOTEM_OF_UNDYING` | `floats: [3]` |
| Талисман демона | `TOTEM_OF_UNDYING` | `floats: [11]` |
| Талисман героя | `TOTEM_OF_UNDYING` | `floats: [12]` |

## Предупреждения

- `shop.yml`: item_model voidspheres:sphere1 — модели нет в FunTime-паке, на Bedrock будет бумага
- `shop_spheres.yml`: item_model voidspheres:sphere2 — модели нет в FunTime-паке, на Bedrock будет бумага
- `shop_spheres.yml`: item_model voidspheres:sphere4 — модели нет в FunTime-паке, на Bedrock будет бумага
- `shop_spheres.yml`: item_model voidspheres:sphere3 — модели нет в FunTime-паке, на Bedrock будет бумага
- `shop_spheres.yml`: item_model voidspheres:sphere1 — модели нет в FunTime-паке, на Bedrock будет бумага
