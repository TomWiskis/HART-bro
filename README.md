# HART-бро 0.5.1 — повторная идентификация датчика

- [Скачать APK 0.5.1](https://github.com/TomWiskis/HART-bro/raw/refs/heads/downloads/hart-bro-0.5.1-debug.apk?download=1), Android 10+, versionCode 9.
- [Проверки и контрольные суммы](https://github.com/TomWiskis/HART-bro/raw/refs/heads/downloads/hart-bro-0.5.1-test-results.zip?download=1).
- [Успешный CI: 198 JVM / 22 Android, без ошибок и пропусков](https://github.com/TomWiskis/HART-bro/actions/runs/41276156899).
- [Изменения](https://github.com/TomWiskis/HART-bro/blob/main/docs/CHANGES_0.5.1.md), [подпись и проверка](https://github.com/TomWiskis/HART-bro/blob/main/docs/VALIDATION_0.5.1.md), [проверка на VIATOR](https://github.com/TomWiskis/HART-bro/blob/main/docs/RETEST_0.5.1.md).

После HART-таймаута исправный RFCOMM остаётся открытым. «Повторить опрос датчика» использует его или переподключается к прежнему VIATOR; пересоздание Activity не блокирует повтор. Неполный кадр без XOR не принимается, поздние фрагменты очищаются. Команда 35 по-прежнему отправляется один раз с проверкой результата. Возможности 0.5.0 и форматы данных сохранены.

Подпись совпадает с опубликованной 0.5.0: SHA-256 сертификата `263b9d04e15c4e42532df3b6a6534e6290890b981c1cefaec4f25622f2385c09`. Устанавливайте поверх 0.5.0 без удаления/очистки данных, предварительно сохранив важную текущую сессию/ведомость. Для закрытого репозитория нужен вход в GitHub. Архив проверок содержит локальные JUnit/Lint и подтверждение CI со ссылкой на полные Android JUnit/HTML/PNG.

---

# HART-бро 0.5.0 — настройка PV-диапазона

- [Скачать APK 0.5.0 для Android 10+](https://github.com/TomWiskis/HART-bro/raw/refs/heads/downloads/hart-bro-0.5.0-debug.apk?download=1), versionCode 8.
- [Отчёты и подтверждение проверок](https://github.com/TomWiskis/HART-bro/raw/refs/heads/downloads/hart-bro-0.5.0-test-results.zip?download=1).
- [Успешный CI: 193 JVM и 21 фактически выполненный Android-тест](https://github.com/TomWiskis/HART-bro/actions/runs/41228629906).
- [Компоненты и ограничения](https://github.com/TomWiskis/HART-bro/blob/main/docs/CHANGES_0.5.0.md), [формат 35 и таблица кодов](https://github.com/TomWiskis/HART-bro/blob/main/docs/HART35_0.5.0.md), [проверки](https://github.com/TomWiskis/HART-bro/blob/main/docs/VALIDATION_0.5.0.md), [единый аппаратный сценарий](https://github.com/TomWiskis/HART-bro/blob/main/docs/HARDWARE_TEST_0.5.0.md).

Устанавливайте поверх официальной 0.4.0 **без удаления приложения/очистки данных**, предварительно сохранив важную текущую сессию/ведомость. Application ID и сертификат сохранены: SHA-256 `263b9d04e15c4e42532df3b6a6534e6290890b981c1cefaec4f25622f2385c09`. Произвольный CI APK может иметь другой debug-ключ; используйте опубликованный файл. Для закрытого репозитория нужен вход в GitHub в том же браузере.

Команда 35 требует отдельного подтверждения, отправляется однократно и проверяется чтениями 0/15. Устойчивый журнал сохраняет TX/RX, коды, запрошенный и фактический диапазоны; после сбоя доступна проверка без повторной записи. Мониторинг/обход и данные сохраняются, диалоги обхода исправлены. Отдельного лабораторного режима нет. Другие команды записи не добавлены.

Все автоматические тесты прошли без ошибок/пропусков; APK собран; Android Lint — 0 ошибок / 1 прежнее предупреждение. Физическая запись на конкретном приборе ещё требует испытания. После выпуска работа остановлена до обратной связи. Предыдущие APK и отчёты ниже сохранены. Контрольные суммы — в SHA256SUMS.

---

# HART-бро 0.4.0 — обход датчиков

- [Скачать APK 0.4.0 для Android 10+](https://github.com/TomWiskis/HART-bro/raw/refs/heads/downloads/hart-bro-0.4.0-debug.apk?download=1), versionCode 7.
- [Тестовый XLSX на 100 искусственных приборов](https://github.com/TomWiskis/HART-bro/raw/refs/heads/downloads/TEST-ONLY-hart-bro-0.4.0-walk-100-devices.xlsx?download=1).
- [Реальная контрольная сессия: новый полный Excel](https://github.com/TomWiskis/HART-bro/raw/refs/heads/downloads/hart-control-148d023e-0.4.0.xlsx?download=1), 269 исходных значений в 68 временных строках.
- [Отчёты и контрольные суммы проверки](https://github.com/TomWiskis/HART-bro/raw/refs/heads/downloads/hart-bro-0.4.0-test-results.zip?download=1).
- [CI окончательной ревизии](https://github.com/TomWiskis/HART-bro/actions/runs/41204161337).
- [Изменения и ограничения](https://github.com/TomWiskis/HART-bro/blob/main/docs/CHANGES_0.4.0.md), [проверки](https://github.com/TomWiskis/HART-bro/blob/main/docs/VALIDATION_0.4.0.md), [один аппаратный сеанс](https://github.com/TomWiskis/HART-bro/blob/main/docs/HARDWARE_TEST_0.4.0.md).

Подпись и application ID прежние: устанавливайте поверх 0.3.0 **без удаления/очистки данных**, предварительно вручную сохранив важную текущую сессию. Для закрытого репозитория нужен вход в GitHub в том же браузере. Опубликованный файл использует прежний сертификат SHA-256 `263b9d04e15c4e42532df3b6a6534e6290890b981c1cefaec4f25622f2385c09`; произвольный CI APK может иметь другой debug-ключ.

172 JVM-теста и 16 фактически выполненных Android-тестов прошли без ошибок и пропусков. APK собран; Android Lint — 0 ошибок / 1 прежнее предупреждение. Контрольные суммы — в SHA256SUMS.

Обход, команды 14/16, локальные паспортные поля, отдельная пятиколоночная ведомость и исправления полного Excel/PDF/CSV. Только чтение; заводские номера и даты без документации не выдуманы. Аппаратный сеанс 0.4.0 ещё предстоит. После выпуска работа остановлена до его результатов. Предыдущие сборки ниже сохранены.

---

# HART-бро 0.3.0 — сессии, журнал и отчёты

- [Скачать APK 0.3.0 для Android 10+](https://github.com/TomWiskis/HART-bro/raw/refs/heads/downloads/hart-bro-0.3.0-debug.apk?download=1), versionCode 5.
- [Результаты проверок](https://github.com/TomWiskis/HART-bro/raw/refs/heads/downloads/hart-bro-0.3.0-test-results.zip?download=1).
- [CI окончательной ревизии](https://github.com/TomWiskis/HART-bro/actions/runs/41074800362).
- [Изменения, конфигурация и ограничения](https://github.com/TomWiskis/HART-bro/blob/main/docs/CHANGES_0.3.0.md), [подробный отчёт](https://github.com/TomWiskis/HART-bro/blob/main/docs/VALIDATION_0.3.0.md), [единый аппаратный сценарий](https://github.com/TomWiskis/HART-bro/blob/main/docs/METRAN_TEST_0.3.0.md).

Устанавливайте **поверх 0.2.1 без удаления приложения/очистки данных**, предварительно сохранив важную текущую сессию. Application ID и сертификат опубликованных 0.2.0/0.2.1 сохранены: SHA-256 сертификата `263b9d04e15c4e42532df3b6a6534e6290890b981c1cefaec4f25622f2385c09`. APK из произвольного Actions-run может иметь другой debug-ключ; для обновления используйте опубликованный файл здесь. Для закрытого репозитория войдите в GitHub в том же браузере.

139 JVM-тестов и 11 Android-тестов прошли без ошибок/пропусков; APK собран, Lint — 0 ошибок/1 предупреждение. Android-проверка включает новые измерения при 5 минутах видимого экрана, 5 минутах блокировки, 5 минутах другого приложения, а также сессии/журнал/восстановление/PDF/CSV. Программный транспорт входит только в androidTest; новая версия ещё требует аппаратного испытания на VIATOR/Метране. Команд записи нет. Контрольные суммы — в SHA256SUMS.

Предыдущие сборки и их исторические описания ниже сохранены.

---

# HART-бро 0.2.1 — исправления по аппаратному испытанию 0.2.0

- [Скачать APK 0.2.1 для Android 10+](https://github.com/TomWiskis/HART-bro/raw/refs/heads/downloads/hart-bro-0.2.1-debug.apk?download=1), versionCode 4.
- [Скачать результаты проверок](https://github.com/TomWiskis/HART-bro/raw/refs/heads/downloads/hart-bro-0.2.1-test-results.zip?download=1).
- [Успешный CI: обе задачи и два Android-теста](https://github.com/TomWiskis/HART-bro/actions/runs/41058553285).
- [Изменения и причины](https://github.com/TomWiskis/HART-bro/blob/main/docs/CHANGES_0.2.1.md), [отчёт](https://github.com/TomWiskis/HART-bro/blob/main/docs/VALIDATION_0.2.1.md), [один повторный сеанс на Метран-150](https://github.com/TomWiskis/HART-bro/blob/main/docs/METRAN_TEST_0.2.1.md).

**Обновление поверх 0.2.0:** подпись сохранена, application ID прежний. Устанавливайте APK без удаления приложения/очистки данных, старые архивы сохраняются. Сертификат SHA-256: `263b9d04e15c4e42532df3b6a6534e6290890b981c1cefaec4f25622f2385c09`. APK из GitHub Actions может иметь другую debug-подпись — для обновления используйте эту ссылку. Для закрытого репозитория нужен вход в GitHub в том же браузере.

122 JVM-теста и 2 instrumentation-теста Android 16/API 36 прошли без ошибок и пропусков; сборка APK успешна, Lint 0 ошибок/1 предупреждение. Контрольные суммы в SHA256SUMS. На физическом VIATOR новая 0.2.1 ещё требует повторной проверки; программный тестовый транспорт не входит в APK. Предыдущие APK и отчёты ниже сохранены. Команд записи нет.

---

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
