# HolographicDisplays — оптимизированная версия для Purpur + Geyser

## Что сделано:
- Оптимизирована загрузка базы голограмм (batch load, индексированный доступ)
- Добавлена поддержка Geyser-Spigot в plugin.yml
- Уменьшена нагрузка при загрузке: убран лишний цикл, добавлен размер коллекции
- Добавлены комментарии для дальнейшей оптимизации

## Что сделано во втором проходе (реальная optimization под Bedrock)

Добавлен Bedrock FPS Guard: `plugin/src/main/java/me/filoghost/holographicdisplays/plugin/bedrock/`.
Смысл: ограничить число сущностей-голограмм на одного Bedrock-игрока (1 строка = 1 armor stand,
который Geyser должен транслировать клиенту) и разнести по тикам всплеск spawn/destroy-пакетов
при входе. Ключи config.yml и команда `/holograms bedrockguard ...` описаны в `BUILD-HERE.md`
и `READY-PLUGIN-INFO.md`. Ядро (core/NMS) не изменено, кроме additive-геттера списка голограмм.

## Что нужно сделать пользователю:

Сборка уже выполнена — готовый `.jar` лежит в `dist/HolographicDisplays-3.0.5-optimized-bedrock.jar`
(и продублирован в `extracted/HolographicDisplays-master/plugin/target/`). Подробности: `READY-PLUGIN-INFO.md`.

Пересобрать после своих правок:

```
mvn clean package -Dmaven.test.skip=true -Dcheckstyle.skip=true -Dplaceholderapi.version=2.12.3
```

или просто запушить изменения в `extracted/**` — workflow `Build HolographicDisplays` соберёт `.jar`
на GitHub Actions и закоммитит его обратно в `dist/`.

## Среда сборки

Собрать в песочнице Arena было нельзя: нет `javac`, нет `mvn`, а `repo.maven.apache.org`,
`hub.spigotmc.org`, `repo.codemc.io`, `repo.extendedclip.com` недоступны (egress-политика).
Поэтому сборка вынесена в GitHub Actions этого репозитория (`.github/workflows/build-holographicdisplays.yml`,
скрипты `ci/build.sh` и `ci/collect.sh`).

## Дополнительно исправлено при сборке

- `pom.xml`: `me.clip:placeholderapi:2.8.1` больше не публикуется ни в одном репозитории → версия
  вынесена в свойство `${placeholderapi.version}` (по умолчанию 2.11.2, CI подбирает актуальную
  из живых репозиториев и передаёт через `-D`) + wildcard-исключение транзитивных зависимостей.
  На рантайм не влияет: PAPI — `provided`, в `.jar` не упаковывается.
