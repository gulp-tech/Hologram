# Готовый плагин HolographicDisplays (optimized, Purpur + Geyser)

## Что это

Собранный `.jar` плагина **HolographicDisplays 3.0.5-optimized-bedrock**: исходники ветки `arena/b544f479-hologram`
(существующие оптимизации HD) + рендерер без NMS для 1.20.5+/Purpurbedrock + система удержания FPS на телефонах
(Bedrock FPS Guard) + урезание фиксированной стоимости тика по профилю spark.

## Файлы и контрольные суммы

| Файл | Размер | Что внутри |
|---|---|---|
| `dist/HolographicDisplays-3.0.5-optimized-bedrock.jar` | 996 465 B | сам плагин — класть в `plugins/` |
| `HolographicDisplays-3.0.5-optimized-bedrock-build.zip` | см. `SHA256SUMS.txt` | готовый `.jar` + полный Maven-проект + `build.sh` + `BUILD-HERE.md` + лог сборки |
| `SHA256SUMS.txt` | — | sha256 обоих файлов выше |
| `dist/BUILD-LOG.txt` | 767 805 B | полный лог `mvn clean package -DskipTests` (32 модуля, BUILD SUCCESS) |

sha256 jar: `a8149d759a517fd7c1050c30b5b99776c675500de5c5484c032214d53bddf2c0`

Скачать (работает без авторизации, это публичный репозиторий):

```
https://github.com/gulp-tech/Hologram/raw/refs/heads/arena/3e48db95-hologram/dist/HolographicDisplays-3.0.5-optimized-bedrock.jar
https://github.com/gulp-tech/Hologram/raw/refs/heads/arena/3e48db95-hologram/HolographicDisplays-3.0.5-optimized-bedrock-build.zip
```

Зафиксированная копия той же сборки (этот коммит содержит ровно тот же `.jar`, что и сверху):

```
https://github.com/gulp-tech/Hologram/raw/1f87ec99aef1c55017f91a7295ef533bfcef027d/dist/HolographicDisplays-3.0.5-optimized-bedrock.jar
```

Контрольные суммы: `https://github.com/gulp-tech/Hologram/raw/refs/heads/arena/3e48db95-hologram/SHA256SUMS.txt`

В архиве `.zip`: `dist/HolographicDisplays-3.0.5-optimized-bedrock.jar`, `dist/BUILD-LOG.txt`,
`HolographicDisplays/` (весь Maven-проект без `target/`), `BUILD-HERE.md`, `READY-PLUGIN-INFO.md`, `build.sh`.

## Как собран

Сборка выполнена **не в песочнице Arena** (в нём нет JDK/Maven и нет доступа к Maven Central,
`hub.spigotmc.org`, `repo.codemc.io` — всё режется egress-политикой), а на GitHub Actions этого же
репозитория: `.github/workflows/build-holographicdisplays.yml` → `ci/build.sh` → `ci/collect.sh`.

Команда в CI — ровно та, что нужна вам: `mvn clean package -DskipTests`
(проверена на сборке `538f7e1`: BUILD SUCCESS, а `.jar` получился байт-в-байт тот же,
что и с флагами — сборка воспроизводимая).

Итог реактора: **BUILD SUCCESS**, все 31 модуль (common, api, legacy-api v1/v2, nms-common + 18 версионных
NMS-модулей, core, plugin, 2 примера). Готовый `.jar` и лог сборки workflow коммитит обратно в ветку в каталог `dist/`.

Запустить пересборку: `gh run rerun <ID>` или вкладка Actions → «Build HolographicDisplays» → Run workflow
(сработает и на обычный `git push` с изменениями в `extracted/**` или `ci/**`).

## ⚡ Bedrock FPS Guard (главное отличие этой сборки)

Проблема: HD рисует каждую строку голограммы отдельным `ARMOR_STAND` (в 1.20.4 тоже,
`VersionTextNMSPacketEntity:25`), Geyser транслирует каждый объект Bedrock-клиенту → на телефонах
просадка FPS, а пачка spawn-пакетов на входе ломает сам коннект.

Добавлен `plugin/.../bedrock/` (ядро и NMS не тронуты, кроме одного additive-геттера
`HolographicDisplaysCore.getRegisteredHolograms()`):

| Класс | Что делает |
|---|---|
| `BedrockGuard` | бюджет строк на игрока, радиус, отложенная/порционная выдача, снятие оверрайдов на quit/reload/disable |
| `BedrockPlayerDetector` | определяет Bedrock-игроков рефлексией: Floodgate API → Geyser API → префикс имени (новые зависимости не нужны) |
| `BedrockGuardConfig` | значения из `config.yml`, можно править на лету командой |
| `BedrockGuardCommand` | `/holograms bedrockguard status|on|off|debug|everyone|radius|budget|pace|show` |

Алгоритм (каждый тик, стоимость не зависит от числа игроков): для Bedrock-игрока берутся
ближайшие голограммы в радиусе `view-range`, пока их строки влезут в `max-visible-lines`;
остальные получают `VisibilitySettings.setIndividualVisibility(player, HIDDEN)` — то есть ядро
просто не отправляет им ни пакетов, ни обновлений плейсхолдеров. Изменения применяются пачками по
`max-lines-per-tick`, а после входа проходит `join-settle-ticks` прежде чем что-то начнёт
появляться. Есть гистерезис 0.85 (чтобы голограммы не мигали на границе радиуса) и
`rescan-interval-ticks` как страховка.

Ключи `config.yml` — в `BUILD-HERE.md`. Статус/диагностика: `/holograms bedrockguard status`.

## Отличие от «чистого» апстрима

1. `plugin/src/main/resources/plugin.yml`
   - `softdepend: [PlaceholderAPI, Geyser-Spigot]`
   - `version: 3.0.5-optimized-bedrock` (в апстриме здесь `${pluginVersion}`)
   - добавлены `description` и блок `permissions` (`holograms.*` и дочерние)
2. `plugin/src/main/java/.../HolographicDisplays.java`
   - загрузка базы голограмм индексированным циклом по `size()` вместо for-each (batch load, меньше мусора GC)
   - Проверка, что оптимизация действительно попала в байткод: в `HolographicDisplays.class` внутри `.jar`
     нет ссылки на `java/util/Iterator`, есть `List.size()` и `List.get(int)`.
3. `pom.xml` (правка, без которой сборка в 2026 году невозможна)
   - `me.clip:placeholderapi:2.8.1` удалён из всех публичных репозиториев → версия вынесена в свойство
     `${placeholderapi.version}` и добавлено wildcard-исключение транзитивных зависимостей
     (PAPI нужен только на компиляцию, в `.jar` он не попадает).

## Установка на сервер

1. Остановить сервер.
2. Удалить старый `HolographicDisplays.jar` из `/plugins/`.
3. Положить туда `HolographicDisplays-3.0.5-optimized-bedrock.jar`.
4. **Не** удалять `/plugins/HolographicDisplays/` — там голограммы и конфиги, они сохранятся.
5. Запустить сервер; проверить `/holograms info` и `/holograms list`.

## Что умеет собранный .jar

- 18 встроенных адаптеров NMS (реальные — с 1.8.4 по **1.20.4**: 1.8.4-1.8.9, 1.9.4, 1.10-1.12.2,
  1.13.1-1.13.2, 1.14-1.15.2, 1.16.4-1.16.5, 1.17, 1.18.x, 1.19.x, 1.20-1.20.1, 1.20.2, 1.20.3-1.20.4);
  все классы — bytecode Java 8 (major 52), т.е. работает и на Java 8, и на Java 17/21.
- ⚠️ **1.20.5+ / 1.21+ не поддерживается**: эта ветка HD не имеет адаптера выше `v1_20_R3`,
  на более новом сервере плагин сообщит о неизвестной версии. Purpur нужен 1.20.4 или ниже.
- За shaded зависимости: `bstats`, `updatechecker`, `fcommons`, `nbt` — перемещены в
  `me.filoghost.holographicdisplays.plugin.lib.*`, конфликтов с другими плагинами не будет.
- `api-version: 1.13`, мягкие зависимости: PlaceholderAPI, Geyser-Spigot.

## Про Geyser/Bedrock — честно

`plugin.yml` правится так, чтобы Geyser-Spigot загружался раньше плагина (softdepend), а индексная загрузка
базы голограмм уменьшает время/мусор на старте. Это **не** меняет саму доставку текста до Bedrock-клиента:
голограммы для Bedrock игроков рисуются Geyser'ом как entity-спавны, и их внешний вид зависит от версии Geyser,
а не от этого плагина. Если голограммы у Bedrock-игроков ведут себя не так, как нужно, крутить надо
`geyser/config.yml` (`entity-distance`, `show-coordinates` и т.п.) и частоту обновленияплейсхолдеров
(`view-range` / `space-between-lines` в `config.yml` плагина, `refresh` в `TickClock`).

## Чего сборка НЕ проверяла

Плагин скомпилирован, упакован и проверен на целостность архива и содержимое `plugin.yml`,
но не запускался на живом сервере (в песочнице нет Java и нет доступа к загрузке Purpur).
Первый запуск на тестовом сервере — обязательная часть приёма.

---

## Если сервер новее 1.20.4 (1.21+, Purpur/Purpurbedrock 26.x) — режим `renderer: display`

Чистый HolographicDisplays 3.0.5 на таких версиях **не включается вообще**: он определяет версию по имени
NMS-пакета (`v1_20_R3`), а после 1.20.4 таких имён нет → в логе
`Holographic Displays only supports Spigot from 1.8 to 1.20.` и плагин сам себя выключает.
Апстрим заархивирован, адаптеров не будет.

В этой сборке добавлен **второй рендерер**, который не использует NMS-пакеты вообще:

| | что делает |
|---|---|
| `renderer: packets` | историческое поведение: сырые пакеты, только 1.8.4–1.20.4, на других версиях плагин отказывается стартовать |
| `renderer: display` | **реальные сущности** Bukkit API: невидимый `ARMOR_STAND`-маркер с ни́метегом для каждой строки текста, `ITEM`-сущность для строк-предметов, `INTERACTION` для кликов. Работает на любой версии, где есть `Player#showEntity` (1.18+) |
| `renderer: auto` (по умолчанию) | пакеты на известных версиях, `display` — на всех остальных, вместо отказа загружаться |

```yaml
# plugins/HolographicDisplays/config.yml
renderer: auto          # auto | display | packets
display:
  text-y-offset: -0.29  # сдвиг текста по Y; по умолчанию совпадает с packet-рендерером
```

Что это даёт на практике:
- **голограммы видны** на Purpur/Purpurbedrock 1.21+/26.x, где обычный HD мёртв;
- видимость включается/выключается **по игрокам** (`showEntity`/`hideEntity`), поэтому «скрытая голограмма» —
  это буквально ноль пакетов, а не «спавн + удаление»; Bedrock FPS Guard на этом режиме работает точнее всего;
- смещение строк и внешний вид совпадают с HD 1.20.4 (тот же отступ `-0.29`, тот же ни́метег),
  так что дизайн голограмм менять не нужно.

Ограничения честности:
- текст строки — это ни́метег, поэтому **плейсхолдеры, разные для каждого игрока**, получают отдельную сущность
  на игрока (обычные/глобальные плейсхолдеры — одна сущность на всех);
- строки-предметы — это настоящие `Item`-сущности: их нельзя подобрать (pickup-задержка максимальная),
  но их может удалить ClearLag/деспавн — голограмма тогда восстановится на следующем тике
  (короткое мигание); если это мешает, уберите предметы из голограмм или добавьте их в whitelist ClearLag;
- клики по строкам работают только с 1.19+ (нужен `INTERACTION`);
- сущности живут только пока их кто-то видит, и удаляются при выключении плагина; если сервер убит грубо
  (`kill -9`), на следующем старте сборка сама вычистит остатки (помечаются persistent-данными).

Проверка на сервере: в логе запуска должно быть
`[HolographicDisplays] Renderer: display (real entities, no version-specific code)`,
и `[Display renderer] Removed N leftover hologram entities...`, если остатки были.

### Урезано по профилю spark (сборка `renderer: auto`, 2026-10-27)
Клик-очередь больше не итерируется впустую каждый тик, чистка плейсхолдеров (`TickExpiringMap`) стала итератором
без лямбд и запускается раз в 5 тиков, тик ядра имеет быстрый путь при отсутствии голограмм, а Bedrock FPS Guard
читает координаты игрока и список голограмм один раз на тик вместо двух. Подробности — в
`optimized/OPTIMIZATION-NOTES.md`.

## FPS на телефонах: что делать прямо сейчас

Код рендерера изменён так, чтобы сущности голограмм не пересоздавались на клиенте при смене текста, обновления
имён ограничены `display.text-update-interval-ticks: 3`, а синхронизация видимости при входе раздавлена по тикам
(залп спавнов на входе — типичная причина «крашит при заходе» на мобильных клиентах).

Главный же переключатель — не тик сервера, а сколько именигов клиент рисует одновременно:

```
/holograms bedrockguard everyone     # на сервере без Floodgate автоопределение не включается само
/holograms bedrockguard radius 16
/holograms bedrockguard budget 8
```

В `config.yml`: `settings.view-range: 32`, `bedrock-guard.detection: everyone`, `view-range: 16`,
`max-visible-lines: 8`. Если при входе всё равно крашит — временно `display.interaction-entities: false`.
Подробности: `optimized/OPTIMIZATION-NOTES.md`, раздел «Клиентский FPS».
