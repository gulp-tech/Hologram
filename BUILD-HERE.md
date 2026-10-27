# HolographicDisplays 3.0.5 — optimized build (Purpur + Geyser)

Архив из трёх частей:

```
dist/HolographicDisplays-3.0.5-optimized-bedrock.jar   ← уже собранный плагин, можно сразу класть в /plugins/
dist/BUILD-LOG.txt                                      ← лог той сборки (все 31 модуль: BUILD SUCCESS)
project/                                                ← полный Maven-проект
build.sh                                                ← скрипт пересборки одной командой
```

## ⚡ В этой сборке есть Bedrock FPS Guard

Каждая строка голограммы = отдельный `ARMOR_STAND`, и Geyser обязан транслировать каждый
такой объект каждому Bedrock-клиенту. На телефонах это роняет FPS, а всплеск пакетов при
входе — причина «не могу зайти». Guard даёт каждому Bedrock-игроку жёсткий бюджет строк и
радиус, а пакеты раздаёт порциями. Настраивается в `plugins/HolographicDisplays/config.yml`:

```yaml
bedrock-guard:
  enabled: true
  view-range: 16              # блоки для Bedrock-игроков (глобальный holograms-view-range = 48)
  max-visible-lines: 12       # ЖЁСТКИЙ потолок сущностей-голограмм на игрока
  max-lines-per-tick: 6       # темп выдачи/снятия: 6 строк за тик
  join-settle-ticks: 20       # 1 секунду после входа не показываем ничего
  detection: auto             # auto | floodgate | geyser | prefix | everyone | none
  bypass-permission: holographicdisplays.bedrockguard.bypass
  debug: false
```

Горячая подстройка прямо на сервере: `/holograms bedrockguard status|budget 20|radius 24|pace 10|debug|everyone|off`.
`everyone` полезен для теста: все игроки считаются Bedrock, эффект видно даже с ПК.

Java-игроков guard не трогает вообще: он только ставит/снимает individual-видимость через штатный API.


## Вариант 1 — не собирать ничего

Взять `dist/HolographicDisplays-3.0.5-optimized-bedrock.jar` и положить в `/plugins/`.
Папку `/plugins/HolographicDisplays/` (голограммы и конфиги) не удалять.

## Вариант 2 — собрать самому

```bash
unzip HolographicDisplays-3.0.5-optimized-bedrock-build.zip
cd HolographicDisplays-optimized-bedrock
./build.sh
```

`build.sh` сам решит, чем собирать: локальным `mvn` (нужны JDK 17 + Maven 3.6+), а если их нет —
контейнером `maven:3.9-eclipse-temurin-17` через Docker. Результат скопируется в `dist/`.

Эквивалент вручную (то, что ожидает CI):

```bash
cd project
mvn clean package -DskipTests
# .jar:  project/plugin/target/holographicdisplays-plugin-3.0.5-SNAPSHOT.jar
```

Никаких `-D` флагов и правок pom не требуется: `checkstyle` в этом проекте выключён по умолчанию
(`-Dcheckstyle.skip=false`, если хотите включить), а `me.clip:placeholderapi:2.8.1` давно удалён из всех
публичных репозиториев — вместо него в `pom.xml` стоит `${placeholderapi.version}` = `2.12.3`
(`provided`, в .jar не попадает, на рантайм не влияет).

## Windows

```powershell
cd project
mvn clean package -DskipTests
```

## Требования к серверу

- Minecraft 1.8.4 … **1.20.4** (адаптеров для 1.20.5+/1.21 в этой ветке нет).
- Java 8+ (все классы — bytecode Java 8), для 1.20.x всё равно нужна Java 17+.
- Spigot/Paper/Purpur; Geyser-Spigot — мягкая зависимость (плагин будет загружен после него).

## Отличия от чистого апстрима

1. `project/plugin/src/main/resources/plugin.yml`: `softdepend: [PlaceholderAPI, Geyser-Spigot]`,
   `version: 3.0.5-optimized-bedrock`, добавлены `description` и блок `permissions` (`holograms.*`).
2. `project/plugin/src/main/java/.../HolographicDisplays.java`: загрузка базы голограмм индексированным
   циклом по `size()` вместо for-each (меньше мусора GC на старте).
3. `project/pom.xml`: версия PlaceholderAPI вынесена в свойство + wildcard-исключение его транзитивных
   зависимостей; checkstyle — опционально.
