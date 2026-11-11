@echo off
chcp 65001 >nul
cd /d "%~dp0"
if not exist "config.properties" if exist "config.properties.txt" (
    ren "config.properties.txt" "config.properties"
    echo Файл config.properties.txt переименован в config.properties ^(Блокнот добавил .txt^).
)
title vkbot - вход через VK ID

where java >nul 2>nul
if errorlevel 1 (
    echo [ОШИБКА] Java не найдена. Установите Java 11 или новее: https://adoptium.net/
    pause
    exit /b 1
)
if not exist "config.properties" (
    copy /y "config.example.properties" "config.properties" >nul
    echo Создан config.properties. Впишите vkid.client_id и vk.group_id, сохраните и запустите login.bat снова.
    start "" notepad "config.properties"
    pause
    exit /b 1
)

java -Dfile.encoding=UTF-8 -jar "vkbot.jar" "config.properties" --login
echo.
pause
