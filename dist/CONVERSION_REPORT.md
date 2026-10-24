# Отчёт конвертации Java → Bedrock

- Пар (предмет, custom_model_data): **137**
- Уникальных иконок: **136**
- Java-предметов в mappings: **28**
- Проблемных текстур: **0**
- Ошибок самопроверки: **0**
- Отличий в покрытии legacy ↔ 1.21.4+: **13** (реальных конфликтов: 0)
- Заготовок DeluxeMenus: **137** (`deluxemenus_items.yml`)

## Предметы

| Java-предмет | CMD | Bedrock identifier | Иконка | handheld |
| --- | --- | --- | --- | --- |
| `minecraft:apple` | 1 | `funtime:item_funtime_halloween_caramel_apple` | `item_funtime_halloween_caramel_apple` | — |
| `minecraft:beetroot_soup` | 1 | `funtime:item_funtime_halloween_mulled_wine` | `item_funtime_halloween_mulled_wine` | — |
| `minecraft:bow` | 1 | `funtime:item_funtime_new_year_elf_bow` | `item_funtime_new_year_elf_bow` | да |
| `minecraft:bow` | 2 | `funtime:item_funtime_new_year_moon_bow` | `item_funtime_new_year_moon_bow` | да |
| `minecraft:cookie` | 1 | `funtime:item_funtime_halloween_crumble_cookie` | `item_funtime_halloween_crumble_cookie` | — |
| `minecraft:cookie` | 2 | `funtime:item_funtime_new_year_gingerbread1` | `item_funtime_new_year_gingerbread1` | — |
| `minecraft:cookie` | 3 | `funtime:item_funtime_new_year_gingerbread2` | `item_funtime_new_year_gingerbread2` | — |
| `minecraft:cookie` | 4 | `funtime:item_funtime_new_year_gingerbread3` | `item_funtime_new_year_gingerbread3` | — |
| `minecraft:firework_rocket` | 1 | `funtime:item_funtime_new_year_petard` | `item_funtime_new_year_petard` | — |
| `minecraft:firework_rocket` | 2 | `funtime:item_funtime_new_year_spruce_firework` | `item_funtime_new_year_spruce_firework` | — |
| `minecraft:firework_rocket` | 3 | `funtime:item_funtime_new_year_sugar_firework` | `item_funtime_new_year_sugar_firework` | — |
| `minecraft:firework_rocket` | 4 | `funtime:item_funtime_new_year_ice_firework` | `item_funtime_new_year_ice_firework` | — |
| `minecraft:firework_rocket` | 5 | `funtime:item_funtime_new_year_xmas_firework` | `item_funtime_new_year_xmas_firework` | — |
| `minecraft:firework_rocket` | 6 | `funtime:item_funtime_new_year_magic_firework` | `item_funtime_new_year_magic_firework` | — |
| `minecraft:firework_rocket` | 7 | `funtime:item_funtime_new_year_harmony_firework` | `item_funtime_new_year_harmony_firework` | — |
| `minecraft:firework_rocket` | 8 | `funtime:item_funtime_new_year_tangerine_firework` | `item_funtime_new_year_tangerine_firework` | — |
| `minecraft:glow_berries` | 1 | `funtime:item_funtime_new_year_tangerine` | `item_funtime_new_year_tangerine` | — |
| `minecraft:leather_boots` | 1 | `funtime:item_funtime_new_year_grinch_boots` | `item_funtime_new_year_grinch_boots` | — |
| `minecraft:leather_chestplate` | 1 | `funtime:item_funtime_new_year_grinch_chestplate` | `item_funtime_new_year_grinch_chestplate` | — |
| `minecraft:leather_helmet` | 1 | `funtime:item_funtime_new_year_grinch_helmet` | `item_funtime_new_year_grinch_helmet` | — |
| `minecraft:leather_helmet` | 2 | `funtime:item_funtime_new_year_santa_hat` | `item_funtime_new_year_santa_hat` | да |
| `minecraft:leather_leggings` | 1 | `funtime:item_funtime_new_year_grinch_leggings` | `item_funtime_new_year_grinch_leggings` | — |
| `minecraft:mace` | 1 | `funtime:item_funtime_new_year_ice_mace` | `item_funtime_new_year_ice_mace` | да |
| `minecraft:nautilus_shell` | 1 | `funtime:item_funtime_new_year_christmas_candy1` | `item_funtime_new_year_christmas_candy1` | — |
| `minecraft:nautilus_shell` | 2 | `funtime:item_funtime_new_year_christmas_candy2` | `item_funtime_new_year_christmas_candy2` | — |
| `minecraft:nautilus_shell` | 3 | `funtime:item_funtime_new_year_christmas_candy3` | `item_funtime_new_year_christmas_candy3` | — |
| `minecraft:nautilus_shell` | 4 | `funtime:item_funtime_new_year_christmas_candy4` | `item_funtime_new_year_christmas_candy4` | — |
| `minecraft:netherite_axe` | 1 | `funtime:item_funtime_new_year_elf_axe` | `item_funtime_new_year_elf_axe` | да |
| `minecraft:netherite_boots` | 1 | `funtime:item_funtime_new_year_moon_boots` | `item_funtime_new_year_moon_boots` | — |
| `minecraft:netherite_chestplate` | 1 | `funtime:item_funtime_new_year_moon_chestplate` | `item_funtime_new_year_moon_chestplate` | — |
| `minecraft:netherite_helmet` | 1 | `funtime:item_funtime_new_year_moon_helmet` | `item_funtime_new_year_moon_helmet` | — |
| `minecraft:netherite_leggings` | 1 | `funtime:item_funtime_new_year_moon_leggings` | `item_funtime_new_year_moon_leggings` | — |
| `minecraft:netherite_pickaxe` | 1 | `funtime:item_funtime_new_year_elf_pickaxe` | `item_funtime_new_year_elf_pickaxe` | да |
| `minecraft:netherite_sword` | 1 | `funtime:item_funtime_new_year_moon_sword` | `item_funtime_new_year_moon_sword` | да |
| `minecraft:paper` | 1 | `funtime:item_funtime_empty` | `item_funtime_empty` | да |
| `minecraft:paper` | 2 | `funtime:item_funtime_cases_usual` | `item_funtime_cases_usual` | да |
| `minecraft:paper` | 3 | `funtime:item_funtime_cases_free` | `item_funtime_cases_free` | да |
| `minecraft:paper` | 4 | `funtime:item_funtime_cases_resource` | `item_funtime_cases_resource` | да |
| `minecraft:paper` | 5 | `funtime:item_funtime_cases_token` | `item_funtime_cases_token` | да |
| `minecraft:paper` | 6 | `funtime:item_funtime_cases_rune` | `item_funtime_cases_rune` | да |
| `minecraft:paper` | 7 | `funtime:item_funtime_cases_ranks` | `item_funtime_cases_ranks` | да |
| `minecraft:paper` | 8 | `funtime:item_funtime_cases_enchant` | `item_funtime_cases_enchant` | да |
| `minecraft:paper` | 9 | `funtime:item_funtime_cases_coins` | `item_funtime_cases_coins` | да |
| `minecraft:paper` | 10 | `funtime:item_funtime_cases_weapon` | `item_funtime_cases_weapon` | да |
| `minecraft:paper` | 11 | `funtime:item_funtime_cases_armor` | `item_funtime_cases_armor` | да |
| `minecraft:paper` | 12 | `funtime:item_funtime_cases_instrument` | `item_funtime_cases_instrument` | да |
| `minecraft:paper` | 13 | `funtime:item_funtime_buttons_auction_back` | `item_funtime_buttons_auction_back` | — |
| `minecraft:paper` | 14 | `funtime:item_funtime_buttons_auction_categories` | `item_funtime_buttons_auction_categories` | — |
| `minecraft:paper` | 15 | `funtime:item_funtime_buttons_auction_refresh` | `item_funtime_buttons_auction_refresh` | — |
| `minecraft:paper` | 16 | `funtime:item_funtime_buttons_auction_update` | `item_funtime_buttons_auction_update` | — |
| `minecraft:paper` | 17 | `funtime:item_funtime_buttons_auction_vault` | `item_funtime_buttons_auction_vault` | — |
| `minecraft:paper` | 18 | `funtime:item_funtime_buttons_coins` | `item_funtime_buttons_coins` | — |
| `minecraft:paper` | 19 | `funtime:item_funtime_buttons_emerald` | `item_funtime_buttons_emerald` | — |
| `minecraft:paper` | 20 | `funtime:item_funtime_buttons_auction_arrow_left` | `item_funtime_buttons_auction_arrow_left` | — |
| `minecraft:paper` | 21 | `funtime:item_funtime_buttons_auction_arrow_right` | `item_funtime_buttons_auction_arrow_right` | — |
| `minecraft:paper` | 22 | `funtime:item_funtime_buttons_info_book` | `item_funtime_buttons_info_book` | да |
| `minecraft:paper` | 23 | `funtime:item_funtime_buttons_back_shadow` | `item_funtime_buttons_back_shadow` | — |
| `minecraft:paper` | 24 | `funtime:item_funtime_buttons_back_shadow_24` | `item_funtime_buttons_back_shadow` | — |
| `minecraft:paper` | 25 | `funtime:item_funtime_buttons_info_shadow` | `item_funtime_buttons_info_shadow` | — |
| `minecraft:paper` | 26 | `funtime:item_funtime_buttons_craft_back` | `item_funtime_buttons_craft_back` | — |
| `minecraft:paper` | 27 | `funtime:item_funtime_buttons_info_small` | `item_funtime_buttons_info_small` | — |
| `minecraft:paper` | 28 | `funtime:item_funtime_buttons_donate_book` | `item_funtime_buttons_donate_book` | — |
| `minecraft:paper` | 29 | `funtime:item_funtime_buttons_donate_chest` | `item_funtime_buttons_donate_chest` | — |
| `minecraft:paper` | 30 | `funtime:item_funtime_buttons_donate_coins` | `item_funtime_buttons_donate_coins` | — |
| `minecraft:paper` | 31 | `funtime:item_funtime_buttons_time_morning` | `item_funtime_buttons_time_morning` | — |
| `minecraft:paper` | 32 | `funtime:item_funtime_buttons_time_day` | `item_funtime_buttons_time_day` | — |
| `minecraft:paper` | 33 | `funtime:item_funtime_buttons_time_sunset` | `item_funtime_buttons_time_sunset` | — |
| `minecraft:paper` | 34 | `funtime:item_funtime_buttons_time_night` | `item_funtime_buttons_time_night` | — |
| `minecraft:paper` | 35 | `funtime:item_funtime_buttons_weather_sun` | `item_funtime_buttons_weather_sun` | — |
| `minecraft:paper` | 36 | `funtime:item_funtime_buttons_weather_rain` | `item_funtime_buttons_weather_rain` | — |
| `minecraft:paper` | 37 | `funtime:item_funtime_buttons_weather_thunder` | `item_funtime_buttons_weather_thunder` | — |
| `minecraft:paper` | 38 | `funtime:item_funtime_buttons_settings_reset` | `item_funtime_buttons_settings_reset` | — |
| `minecraft:paper` | 39 | `funtime:item_funtime_cases_eggs` | `item_funtime_cases_eggs` | да |
| `minecraft:paper` | 40 | `funtime:item_funtime_cases_traps` | `item_funtime_cases_traps` | да |
| `minecraft:paper` | 41 | `funtime:item_funtime_cases_sphere` | `item_funtime_cases_sphere` | да |
| `minecraft:paper` | 42 | `funtime:item_funtime_cases_talisman` | `item_funtime_cases_talisman` | да |
| `minecraft:paper` | 43 | `funtime:item_funtime_cases_trim` | `item_funtime_cases_trim` | да |
| `minecraft:paper` | 44 | `funtime:item_funtime_tp_home1` | `item_funtime_tp_home1` | да |
| `minecraft:paper` | 45 | `funtime:item_funtime_tp_home2` | `item_funtime_tp_home2` | да |
| `minecraft:paper` | 46 | `funtime:item_funtime_tp_home3` | `item_funtime_tp_home3` | да |
| `minecraft:paper` | 47 | `funtime:item_funtime_tp_home4` | `item_funtime_tp_home4` | да |
| `minecraft:paper` | 48 | `funtime:item_funtime_tp_home5` | `item_funtime_tp_home5` | да |
| `minecraft:paper` | 49 | `funtime:item_funtime_tp_home6` | `item_funtime_tp_home6` | да |
| `minecraft:paper` | 50 | `funtime:item_funtime_tp_home7` | `item_funtime_tp_home7` | да |
| `minecraft:paper` | 51 | `funtime:item_funtime_tp_home8` | `item_funtime_tp_home8` | да |
| `minecraft:paper` | 52 | `funtime:item_funtime_tp_home9` | `item_funtime_tp_home9` | да |
| `minecraft:paper` | 53 | `funtime:item_funtime_tp_home10` | `item_funtime_tp_home10` | да |
| `minecraft:paper` | 54 | `funtime:item_funtime_tp_clan1` | `item_funtime_tp_clan1` | да |
| `minecraft:paper` | 55 | `funtime:item_funtime_tp_clan2` | `item_funtime_tp_clan2` | да |
| `minecraft:paper` | 56 | `funtime:item_funtime_tp_clan3` | `item_funtime_tp_clan3` | да |
| `minecraft:potion` | 1 | `funtime:item_funtime_new_year_tangerine_potion` | `item_funtime_new_year_tangerine_potion` | — |
| `minecraft:potion` | 2 | `funtime:item_funtime_new_year_wine_potion` | `item_funtime_new_year_wine_potion` | — |
| `minecraft:potion` | 3 | `funtime:item_funtime_new_year_spruce_potion` | `item_funtime_new_year_spruce_potion` | — |
| `minecraft:potion` | 4 | `funtime:item_funtime_new_year_elf_potion` | `item_funtime_new_year_elf_potion` | — |
| `minecraft:spider_eye` | 1 | `funtime:item_funtime_halloween_gummy_eye` | `item_funtime_halloween_gummy_eye` | — |
| `minecraft:spider_eye` | 2 | `funtime:item_funtime_halloween_candy_1` | `item_funtime_halloween_candy_1` | — |
| `minecraft:spider_eye` | 3 | `funtime:item_funtime_halloween_candy_2` | `item_funtime_halloween_candy_2` | — |
| `minecraft:spider_eye` | 4 | `funtime:item_funtime_halloween_candy_3` | `item_funtime_halloween_candy_3` | — |
| `minecraft:spider_eye` | 5 | `funtime:item_funtime_halloween_candy_4` | `item_funtime_halloween_candy_4` | — |
| `minecraft:spider_eye` | 6 | `funtime:item_funtime_halloween_candy_5` | `item_funtime_halloween_candy_5` | — |
| `minecraft:splash_potion` | 1 | `funtime:item_funtime_new_year_ice_splash_potion` | `item_funtime_new_year_ice_splash_potion` | — |
| `minecraft:splash_potion` | 2 | `funtime:item_funtime_new_year_alcohol_splash_potion` | `item_funtime_new_year_alcohol_splash_potion` | — |
| `minecraft:splash_potion` | 3 | `funtime:item_funtime_new_year_tangerine_splash_potion` | `item_funtime_new_year_tangerine_splash_potion` | — |
| `minecraft:tipped_arrow` | 1 | `funtime:item_funtime_new_year_spruce_arrow` | `item_funtime_new_year_spruce_arrow` | — |
| `minecraft:tipped_arrow` | 2 | `funtime:item_funtime_new_year_firework_arrow` | `item_funtime_new_year_firework_arrow` | — |
| `minecraft:tipped_arrow` | 3 | `funtime:item_funtime_new_year_ice_arrow` | `item_funtime_new_year_ice_arrow` | — |
| `minecraft:tipped_arrow` | 4 | `funtime:item_funtime_new_year_elf_arrow` | `item_funtime_new_year_elf_arrow` | — |
| `minecraft:tipped_arrow` | 5 | `funtime:item_funtime_new_year_alcohol_arrow` | `item_funtime_new_year_alcohol_arrow` | — |
| `minecraft:totem_of_undying` | 1 | `funtime:minecraft_item_funtime_items_talisman_harmony` | `minecraft_item_funtime_items_talisman_harmony` | да |
| `minecraft:totem_of_undying` | 2 | `funtime:minecraft_item_funtime_items_talisman_daedalus` | `minecraft_item_funtime_items_talisman_daedalus` | да |
| `minecraft:totem_of_undying` | 3 | `funtime:minecraft_item_funtime_items_talisman_triton` | `minecraft_item_funtime_items_talisman_triton` | да |
| `minecraft:totem_of_undying` | 4 | `funtime:minecraft_item_funtime_items_talisman_discord` | `minecraft_item_funtime_items_talisman_discord` | да |
| `minecraft:totem_of_undying` | 5 | `funtime:minecraft_item_funtime_items_talisman_echidna` | `minecraft_item_funtime_items_talisman_echidna` | да |
| `minecraft:totem_of_undying` | 6 | `funtime:minecraft_item_funtime_items_talisman_crusher` | `minecraft_item_funtime_items_talisman_crusher` | да |
| `minecraft:totem_of_undying` | 7 | `funtime:minecraft_item_funtime_items_talisman_punisher` | `minecraft_item_funtime_items_talisman_punisher` | да |
| `minecraft:totem_of_undying` | 8 | `funtime:minecraft_item_funtime_items_talisman_tyrant` | `minecraft_item_funtime_items_talisman_tyrant` | да |
| `minecraft:totem_of_undying` | 9 | `funtime:minecraft_item_funtime_items_talisman_edge` | `minecraft_item_funtime_items_talisman_edge` | да |
| `minecraft:totem_of_undying` | 10 | `funtime:minecraft_item_funtime_items_talisman_phoenix` | `minecraft_item_funtime_items_talisman_phoenix` | да |
| `minecraft:totem_of_undying` | 11 | `funtime:minecraft_item_funtime_new_year_talisman_grinch` | `minecraft_item_funtime_new_year_talisman_grinch` | да |
| `minecraft:totem_of_undying` | 12 | `funtime:minecraft_item_funtime_items_talisman_hero` | `minecraft_item_funtime_items_talisman_hero` | да |
| `minecraft:trial_key` | 1 | `funtime:item_funtime_cases_usual_key` | `item_funtime_cases_usual_key` | — |
| `minecraft:trial_key` | 2 | `funtime:item_funtime_cases_free_key` | `item_funtime_cases_free_key` | — |
| `minecraft:trial_key` | 3 | `funtime:item_funtime_cases_resource_key` | `item_funtime_cases_resource_key` | — |
| `minecraft:trial_key` | 4 | `funtime:item_funtime_cases_token_key` | `item_funtime_cases_token_key` | — |
| `minecraft:trial_key` | 5 | `funtime:item_funtime_cases_ranks_key` | `item_funtime_cases_ranks_key` | — |
| `minecraft:trial_key` | 6 | `funtime:item_funtime_cases_enchant_key` | `item_funtime_cases_enchant_key` | — |
| `minecraft:trial_key` | 7 | `funtime:item_funtime_cases_coins_key` | `item_funtime_cases_coins_key` | — |
| `minecraft:trial_key` | 8 | `funtime:item_funtime_cases_weapon_key` | `item_funtime_cases_weapon_key` | — |
| `minecraft:trial_key` | 9 | `funtime:item_funtime_cases_armor_key` | `item_funtime_cases_armor_key` | — |
| `minecraft:trial_key` | 10 | `funtime:item_funtime_cases_instrument_key` | `item_funtime_cases_instrument_key` | — |
| `minecraft:trial_key` | 11 | `funtime:item_funtime_cases_eggs_key` | `item_funtime_cases_eggs_key` | — |
| `minecraft:trial_key` | 12 | `funtime:item_funtime_cases_traps_key` | `item_funtime_cases_traps_key` | — |
| `minecraft:trial_key` | 13 | `funtime:item_funtime_cases_sphere_key` | `item_funtime_cases_sphere_key` | — |
| `minecraft:trial_key` | 14 | `funtime:item_funtime_cases_talisman_key` | `item_funtime_cases_talisman_key` | — |
| `minecraft:trial_key` | 15 | `funtime:item_funtime_cases_trim_key` | `item_funtime_cases_trim_key` | — |
| `minecraft:trident` | 1 | `funtime:item_funtime_new_year_santa_staff` | `item_funtime_new_year_santa_staff` | — |
| `minecraft:wither_skeleton_skull` | 1 | `funtime:item_funtime_halloween_draculas_skull` | `item_funtime_halloween_draculas_skull` | — |

## Проверка DeluxeMenus

Предметов с `model_data_component`: **23**, покрыто mappings: **23**, не покрыто: **0**

| Меню | Строка | Предмет | CMD | Статус |
| --- | --- | --- | --- | --- |
| `keys.yml` | 33 | `minecraft:trial_key` | 14 | ok |
| `keys.yml` | 69 | `minecraft:trial_key` | 3 | ok |
| `keys.yml` | 105 | `minecraft:trial_key` | 13 | ok |
| `menu.yml` | 14 | `minecraft:paper` | 22 | ok |
| `menu.yml` | 96 | `minecraft:paper` | 29 | ok |
| `shop.yml` | 111 | `minecraft:paper` | 42 | ok |
| `shop.yml` | 128 | `minecraft:paper` | 9001 | item-model |
| `shop_items.yml` | 66 | `minecraft:paper` | 40 | ok |
| `shop_potions.yml` | 66 | `minecraft:potion` | 1 | ok |
| `shop_potions.yml` | 96 | `minecraft:potion` | 2 | ok |
| `shop_potions.yml` | 126 | `minecraft:potion` | 3 | ok |
| `shop_potions.yml` | 156 | `minecraft:potion` | 4 | ok |
| `shop_spheres.yml` | 66 | `minecraft:paper` | 9001 | item-model |
| `shop_spheres.yml` | 101 | `minecraft:paper` | 9002 | item-model |
| `shop_spheres.yml` | 137 | `minecraft:paper` | 9003 | item-model |
| `shop_spheres.yml` | 173 | `minecraft:paper` | 9004 | item-model |
| `shop_talismans.yml` | 59 | `minecraft:totem_of_undying` | 6 | ok |
| `shop_talismans.yml` | 93 | `minecraft:totem_of_undying` | 8 | ok |
| `shop_talismans.yml` | 126 | `minecraft:totem_of_undying` | 7 | ok |
| `shop_talismans.yml` | 160 | `minecraft:totem_of_undying` | 2 | ok |
| `shop_talismans.yml` | 194 | `minecraft:totem_of_undying` | 10 | ok |
| `shop_talismans.yml` | 228 | `minecraft:totem_of_undying` | 9 | ok |
| `shop_talismans.yml` | 262 | `minecraft:totem_of_undying` | 4 | ok |
| `shop_talismans.yml` | 296 | `minecraft:totem_of_undying` | 1 | ok |
| `shop_talismans.yml` | 328 | `minecraft:totem_of_undying` | 5 | ok |
| `shop_talismans.yml` | 362 | `minecraft:totem_of_undying` | 3 | ok |
| `shop_talismans.yml` | 396 | `minecraft:totem_of_undying` | 11 | ok |
| `shop_talismans.yml` | 430 | `minecraft:totem_of_undying` | 12 | ok |

## Покрытие оверлеев

CMD, который есть только в одном формате (нормально: оверлеи патчат не все предметы).

- `minecraft:bow` CMD 1: only_legacy (legacy = `funtime/new_year/elf_bow`, definition = `None`)
- `minecraft:bow` CMD 2: only_legacy (legacy = `funtime/new_year/moon_bow`, definition = `None`)
- `minecraft:leather_boots` CMD 1: only_legacy (legacy = `funtime/new_year/grinch_boots`, definition = `None`)
- `minecraft:leather_chestplate` CMD 1: only_legacy (legacy = `funtime/new_year/grinch_chestplate`, definition = `None`)
- `minecraft:leather_helmet` CMD 1: only_legacy (legacy = `funtime/new_year/grinch_helmet`, definition = `None`)
- `minecraft:leather_helmet` CMD 2: only_legacy (legacy = `funtime/new_year/santa_hat`, definition = `None`)
- `minecraft:leather_leggings` CMD 1: only_legacy (legacy = `funtime/new_year/grinch_leggings`, definition = `None`)
- `minecraft:netherite_boots` CMD 1: only_legacy (legacy = `funtime/new_year/moon_boots`, definition = `None`)
- `minecraft:netherite_chestplate` CMD 1: only_legacy (legacy = `funtime/new_year/moon_chestplate`, definition = `None`)
- `minecraft:netherite_helmet` CMD 1: only_legacy (legacy = `funtime/new_year/moon_helmet`, definition = `None`)
- `minecraft:netherite_leggings` CMD 1: only_legacy (legacy = `funtime/new_year/moon_leggings`, definition = `None`)
- `minecraft:trial_key` CMD 15: only_definition (legacy = `None`, definition = `item/keys/trim_key`)
- `minecraft:trident` CMD 1: only_legacy (legacy = `funtime/new_year/santa_staff`, definition = `None`)
