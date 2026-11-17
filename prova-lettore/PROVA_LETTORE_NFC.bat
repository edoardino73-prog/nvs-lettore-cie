@echo off
chcp 65001 >nul
echo Prova del lettore NFC per la CIE
echo (non scrive nulla e non invia dati: mostra solo un rapporto)
echo.
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0PROVA_LETTORE_NFC.ps1"
echo.
pause
