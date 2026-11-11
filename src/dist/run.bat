@echo off
chcp 65001 >nul
cd /d "%~dp0"
if not exist "config.properties" if exist "config.properties.txt" (
    ren "config.properties.txt" "config.properties"
    echo Файл config.properties.txt переименован в config.properties ^(Блокнот добавил .txt^).
)
title vkbot - статистика обсуждений VK

where java >nul 2>nul
if errorlevel 1 (
    echo [ОШИБКА] Java не найдена. Установите Java 11 или новее: https://adoptium.net/
    echo.
    pause
    exit /b 1
)

echo Запускаю интерфейс - он откроется в браузере. Не закрывайте это окно, пока работаете.
echo.
java -Dfile.encoding=UTF-8 -jar "vkbot.jar" "config.properties" %*
set RC=%ERRORLEVEL%
echo.
echo Программа остановлена ^(код %RC%^). Ошибки - выше и в logs\vkbot.log
pause
exit /b %RC%
