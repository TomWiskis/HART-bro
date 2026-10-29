# HART-бро 0.2.0 — тестовая сборка мониторинга

**Внимание: другой debug-сертификат, исходный ключ 0.1.1 недоступен. APK 0.2.0 нельзя установить поверх 0.1.1. Используйте отдельный телефон без прежней установки; не удаляйте 0.1.1 ради испытания.** Application ID остался `ru.hartbro.app`. Предыдущие APK ниже сохранены.

- [Скачать APK 0.2.0 для Android 10+](https://github.com/TomWiskis/HART-bro/raw/refs/heads/downloads/hart-bro-0.2.0-debug.apk?download=1), versionCode 3.
- [Результаты 106 тестов и Lint](https://github.com/TomWiskis/HART-bro/raw/refs/heads/downloads/hart-bro-0.2.0-test-results.zip?download=1): 0 падений/ошибок/пропусков; Lint 0 ошибок, 1 предупреждение.
- [Исходный коммит](https://github.com/TomWiskis/HART-bro/commit/8830502).
- [Полный отчёт и ограничения](https://github.com/TomWiskis/HART-bro/blob/feature/0.2.0-monitoring/docs/VALIDATION_0.2.0.md).
- [Один комплексный сеанс на Метран-150](https://github.com/TomWiskis/HART-bro/blob/feature/0.2.0-monitoring/docs/METRAN_TEST_0.2.0.md).

Ссылки на APK и отчёты запускают скачивание через GitHub. Для закрытого репозитория нужен вход в GitHub в том же браузере. Если встроенный браузер показывает 404, откройте ссылку во внешнем браузере с выполненным входом.

Только чтение 0/1/3/8/9/48/50; мониторинг, общий график, foreground service, ручной архив, восстановление до 60 секунд. На оборудовании 0.2.0 ещё не испытывалась. Контрольные суммы — в SHA256SUMS. Приватные ключи в репозиторий не включены.

---

# HART-бро: скачать приложение

[**Скачать APK 0.1.1 для Android 10+**](https://github.com/TomWiskis/HART-bro/raw/refs/heads/downloads/hart-bro-0.1.1-debug.apk) · около 27 МиБ.

[Скачать отчёты 58 автоматических тестов и дамп](https://github.com/TomWiskis/HART-bro/raw/refs/heads/downloads/hart-bro-0.1.1-test-results.zip).

## Исправление первого этапа

Реальный ответ Метрана с четырьмя FF принимается с предупреждением после проверки структуры, длины и XOR. Сохраняются неполные Bluetooth-фрагменты; исправлено отображение полного типа 0x9972/производителя 0x0099; добавлены названия единиц, расшифровка статусов и полные TX/RX-кадры в HEX.

**58 тестов прошли, ошибок и пропусков нет.** Проверены целый дамп, все разбиения, побайтовый приём и 250 вариантов фрагментации. Lint: 0 ошибок, одно уведомление о новой версии корутин. Подпись APK проверена и совпадает с 0.1.0 — можно обновить существующую установку. Скопируйте APK на телефон, откройте его и разрешите установку из выбранного приложения. Если открыта страница файла, используйте «Download raw file».

**Аппаратная проверка 0.1.1 ещё не выполнена.** Для одного сеанса на реальном Метране подготовлен [план испытания](https://github.com/TomWiskis/HART-bro/blob/66e33a9ef01e24eb9c13ea1f172d63802210a5a6/docs/METRAN_RETEST_0.1.1.md). Команды записи, калибровки, сброса статусов, USB OTG и непрерывный мониторинг не добавлялись.

- [Все исправления](https://github.com/TomWiskis/HART-bro/blob/66e33a9ef01e24eb9c13ea1f172d63802210a5a6/CHANGELOG.md).
- [Результаты проверок](https://github.com/TomWiskis/HART-bro/blob/66e33a9ef01e24eb9c13ea1f172d63802210a5a6/docs/VALIDATION.md).
- [Исходники 0.1.1](https://github.com/TomWiskis/HART-bro/tree/66e33a9ef01e24eb9c13ea1f172d63802210a5a6), коммит `66e33a9ef01e24eb9c13ea1f172d63802210a5a6`.
- [Предыдущая 0.1.0](https://github.com/TomWiskis/HART-bro/raw/refs/heads/downloads/hart-bro-0.1.0-debug.apk) сохранена для истории; повторное испытание выполняйте версией 0.1.1.

## SHA-256

```text
fadbe3c120bcf1b52dc2a48c45d11f4ab4db9ae194cbdf246b7006b34c6659eb  hart-bro-0.1.0-debug.apk
69bfca11fa1f354fa798ff6f358e8ff2ee0d4cd9a389db5b5da2aef60f90904b  hart-bro-0.1.1-debug.apk
7aab1fff2aa5e66b99c4cedc4dc32b36bd0c15c52026b9f53af031925d53d72d  hart-bro-0.1.1-test-results.zip
```
