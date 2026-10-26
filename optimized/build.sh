#!/bin/bash
# Сборка оптимизированного плагина HolographicDisplays
# Требуется: openjdk-17-jdk, maven

set -e
if command -v mvn &> /dev/null && command -v javac &> /dev/null; then
    echo "[OK] Найдены mvn и javac"
else
    echo "[ERROR] Не найдены mvn или javac. Установите openjdk-17-jdk и maven."
    exit 1
fi

# Копируем исходники на место проекта
cp -r src/main/java . 2>/dev/null || true
cp plugin.yml . 2>/dev/null || true

echo "[BUILD] Запуск mvn clean package..."
# Для сборки всего проекта нужно перейти в папку с основным pom.xml
# Здесь предполагается, что вы запускаете из корня извлечённого архива
mvn clean package -DskipTests

echo "[BUILD] Готовый .jar можно найти в plugin/target/ или core/target/"
