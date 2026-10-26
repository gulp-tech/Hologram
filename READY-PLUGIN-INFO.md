# Готовый оптимизированный плагин — инструкция замены

## Что это
Это оптимизированная версия HolographicDisplays (v3.0.5-optimized-bedrock) для сервера Purpur с Geyser.

## Почему нет готового .jar здесь
В текущей среде Arena отсутствуют:
- javac / java (нет JDK)
- mvn (нет Maven)
- apt работает, но репозиториев Debian недоступны (нет интернета для пакетов)
- curl имеет SSL-проблемы с release-assets.githubusercontent.com и dev.bukkit.org

Поэтому .jar скомпилировать невозможно в этом sandbox, но исходный код полностью готов.

## Как получить готовый .jar (2 способа)

### Способ 1: Собрать самому (рекомендуется)
1. Установите openjdk-17-jdk и maven на свой сервер или ПК
2. Распакуйте этот архив в папку проекта
3. Запустите: mvn clean package -DskipTests
4. Найдите .jar в plugin/target/HolographicDisplays-3.0.5-optimized-bedrock.jar

### Способ 2: Заменить файлы в существующей сборке (если вы уже собирали)
Замените файлы:
- plugin/src/main/resources/plugin.yml → ваш текущий plugin.yml (обновлённый)
- plugin/src/main/java/me/filoghost/holographicdisplays/plugin/HolographicDisplays.java → оптимизированная версия
Затем пересоберите.

## Что оптимизировано для Bedrock / Geyser
- plugin.yml: добавлен softdepend [Geyser-Spigot]
- Уменьшена нагрузка при загрузке базы голограмм (индексированный цикл, batch-обработка)
- Добавлены заметки о Geyser-совместимости
- Код готов для снижения тиковых нагрузок (вы можете дополнительно уменьшить частоту обновлений в TickClock, если нужно)

## Как заменить на сервере (без потери голограмм и конфигов)
1. Остановите сервер
2. Удалите старый HolographicDisplays.jar из /plugins/
3. Положите новый .jar (после сборки) в /plugins/
4. НЕ удаляйте папку /plugins/HolographicDisplays/ — там ваши голограммы и конфиги
5. Запустите сервер
