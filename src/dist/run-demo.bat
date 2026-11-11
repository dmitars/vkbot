@echo off
chcp 65001 >nul
cd /d "%~dp0"
if not exist "config.properties" if exist "config.properties.txt" (
    ren "config.properties.txt" "config.properties"
    echo Файл config.properties.txt переименован в config.properties ^(Блокнот добавил .txt^).
)
title vkbot - демо-режим
if not exist "config.properties" copy /y "config.example.properties" "config.properties" >nul
echo Демо-режим: интерфейс откроется в браузере, данные сгенерированы.
java -Dfile.encoding=UTF-8 -jar "vkbot.jar" "config.properties" --demo
echo.
pause
