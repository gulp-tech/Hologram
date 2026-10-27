# Готовый плагин HolographicDisplays (optimized, Purpur + Geyser)

## Что это

Собранный `.jar` плагина **HolographicDisplays 3.0.5-optimized-bedrock** — ветка `arena/b544f479-hologram`
(исходники с оптимизациями) + полная сборка Maven.

## Где лежит готовый .jar

**Одним архивом** (готовый `.jar` + полный проект + скрипт пересборки):
`HolographicDisplays-3.0.5-optimized-bedrock-build.zip` — 1 437 967 B, 878 файлов,
sha256 `f3e9ef2be1b3bc777f580d5d73118a0aea1aea0a0bd51cb4678b76b74b4a8177`.
Внутри: `dist/*.jar`, `dist/BUILD-LOG.txt`, `project/` (весь Maven-проект без `target/`),
`build.sh` (сам выберет `mvn` или Docker) и `BUILD-HERE.md`.

## Где лежит готовый .jar

| Файл | Путь |
|---|---|
| **Плагин для сервера** | `dist/HolographicDisplays-3.0.5-optimized-bedrock.jar` |
| Тот же файл, по «ожидаемому» пути сборки | `extracted/HolographicDisplays-master/plugin/target/HolographicDisplays-3.0.5-optimized-bedrock.jar` |
| Полный лог сборки (Maven, 31 модуль) | `dist/BUILD-LOG.txt` |

- размер: **961 921 байт** (≈ 0,92 MiB), 828 записей, `.jar` от 2026-10-27 (сборка `1f58b9a`, уже с Bedrock FPS Guard)
- sha256: `cdff07b9bbe7c89c8d010d2ec97982144763c54116f3da98be5b0c5716f9a62b`
- предыдущая сборка (без guard'а): `7a1e99d3f14fb4a84c845a3245d414bb53770ce565afd30ca0e445d5616cd029`, 934 596 B
- CRC-тест архива: `No errors detected`

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
