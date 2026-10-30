# Проверка HART-бро 0.2.1 перед аппаратным испытанием

30 октября 2026, Asia/Chita. Основа: `main` с исправлением CI `6b1c6cc`. Работа ведётся в `fix/0.2.1-field-feedback`.

## Локальные результаты

- **122 JVM-теста: 0 падений, 0 ошибок, 0 пропусков.** Включены все прежние HART-регрессии, новые проверки следования/осей, периода и общего recovery deadline, таймаутов 9, чтения старого архива, сохранения начала полного HEX и ограничения объёма.
- `:app:assembleDebug` и `:app:assembleDebugAndroidTest` — успешны.
- `:app:lintDebug` — **0 ошибок, 1 предупреждение** NewerVersionAvailable для корутин 1.11.0. Оставлена проверенная зависимость 1.10.2; проверки/baseline не отключались.
- `git diff --check` — без ошибок.
- `apksigner verify` — подпись v2 проверена. `aapt dump badging` — `ru.hartbro.app`, versionName 0.2.1, versionCode 4, min 29, target 36. INTERNET/USB/команды записи не добавлены.
- Проверено отсутствие FieldRegressionApplication/FieldRegressionRunner и тестового источника значений в production DEX.

Команда из корня (полный JDK 21, SDK 36 / Build Tools 36.0.0, Gradle 8.13):

```sh
./gradlew :hart-core:test :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug \
  --offline --no-daemon -Pkotlin.compiler.execution.strategy=in-process
```

В этой среде сохранённый debug-ключ доступен сборке через локальную переменную `HARTBRO_DEBUG_KEYSTORE=/workspace/hartbro-signing/debug.keystore`. Приватный ключ не включён в Git/отчёты. TLS и проверки контрольных сумм не отключались.

| Набор | Тестов |
|---|---:|
| HartClientTest | 15 |
| HartCodecTest | 13 |
| HartDeviceTest | 8 |
| HartDescriptionsTest | 7 |
| MetranRegressionTest | 15 |
| MeasurementsTest | 21 |
| MonitoringEngineTest | 16 |
| SessionHistoryTest | 14 |
| ChartViewportTest | 7 |
| ChartAxesTest | 3 |
| DiagnosticJournalTest | 3 |
| **Итого** | **122** |

В legacy-тесте читаются заголовок version 1, два действительных измерения и события старого writer-формата, проверяются значения, разрыв и побайтовая неизменность файла. Это проверка формата, а не утверждение, что пользовательский архив уже проверен на его телефоне.

## Android runtime и GitHub Actions

Локальная попытка без KVM: эмулятор API 36 с программной эмуляцией CPU не завершил загрузку Android Framework за 10 минут. ADB работал, но instrumentation-тесты здесь не запускались. Эмулятор остановлен; эта попытка не считается успешным Android-тестированием.

Подготовлены два instrumentation-сценария Android 16/API 36: две реальные минуты после Home с продолжающимся опросом, блокировка экрана/возврат/пересоздание Activity, одна сессия и уведомление; архивная навигация после прокрутки через закреплённую кнопку и системный Back. Источник измерений только в androidTest, без VIATOR и без утверждений о реальном измерении.

**Исправленный запуск [41058553285](https://github.com/TomWiskis/HART-bro/actions/runs/41058553285), ревизия `0141554`: завершён успешно.**

- `build-and-test`: success — SDK, HART-тесты, сборка APK, Android Lint и загрузка результатов.
- `android-lifecycle-regression`: success — API 36 x86_64 Google APIs, KVM, фактический запуск `:app:connectedDebugAndroidTest` и проверка JUnit-результатов.
- Аннотация GitHub Checks: **`tests: 2, failures: 0, errors: 0, skipped: 0`**. Проверка CI требует наличия обоих ожидаемых сценариев по имени; нулевой/пропущенный прогон не проходит.

Исходный запуск [41053971525](https://github.com/TomWiskis/HART-bro/actions/runs/41053971525) имел успешный `build-and-test`, но эмулятор не создал userdata: доступно 6353.27 MB, требовалось 7372.80 MB. Исправление инфраструктуры затрагивает только `.github/workflows/android.yml`: удаляются неиспользуемые .NET/Haskell/Swift/CodeQL из одноразового runner, выводятся `df`/`du`, проверяется запас не менее 10 GiB перед установкой образа, задаётся поддерживаемый `disk-size: 2048M` и отключены snapshots. Java/Gradle/Android SDK не удаляются. После создания AVD выводится ещё одна диагностика места. Уменьшение диска не заменяет тесты — обе Android-проверки выполнены.

## APK, подпись и обновление

Файл: `hart-bro-0.2.1-debug.apk`, 28 779 468 байтов. [Скачать через GitHub](https://github.com/TomWiskis/HART-bro/raw/refs/heads/downloads/hart-bro-0.2.1-debug.apk?download=1) (для закрытого репозитория нужен вход в браузере). Локальная копия: `/workspace/hart-bro-0.2.1-debug.apk`.

SHA-256: `b4bc0fd22607b73dc4b46ba38c61335f2974c5dcf0de6a56923bc46c9f815918`.

Сертификат SHA-256: `263b9d04e15c4e42532df3b6a6534e6290890b981c1cefaec4f25622f2385c09` — совпадает с опубликованной 0.2.0. Для обновления с сохранением данных установить этот APK поверх 0.2.0 штатным установщиком или `adb install -r`; не удалять приложение и не очищать данные. Совместимость подписи с 0.1.1 не восстановлена: её исходный ключ по-прежнему недоступен. APK из CI подписывается ключом runner и не является заменой этой подписанной сборке для обновления.

## Изменённые файлы и ограничения

Основные изменения: MainActivity, MonitoringChart, HartApplication, SessionViewModel/SessionController, MonitoringService, AndroidManifest/file_paths, app build script; в hart-core — ChartViewport, ChartAxes, SessionHistory, MonitoringEngine, Measurements/MeasurementReader, HartClient и DiagnosticJournal. Добавлены JVM/Android-регрессии, CI-задача эмулятора и документация. BluetoothSppTransport, кодек адресов/кадров и allowlist команд чтения не переписаны. Подробные причины — [CHANGES_0.2.1.md](CHANGES_0.2.1.md).

Установка/обновление на пользовательском телефоне и новый физический сеанс с VIATOR/Метраном/ТСПУ не проводились. На эмуляторе проверялась логика с инструментальным транспортом, не физическая совместимость Bluetooth. Единственную аппаратную причину останова 0.2.0 в фоне по описанию установить нельзя; добавлены наблюдаемость и устранение выявленных lifecycle/notification-рисков, но нужна повторная проверка конкретного телефона. [Единый сценарий](METRAN_TEST_0.2.1.md) включает обновление/старый архив, 2–5 минут другого приложения, экран, график/оси, совпадающие линии, резервные команды, восстановление и полный TXT.

Копирование большого лога ограничено 500 кБ из-за Android Clipboard; полный доступный TXT до 16 МиБ сохраняется/передаётся по запросу. При лимите есть явное предупреждение, первые команды не перезаписываются. Нормативного определения производителя 0x602A не добавлено. USB, запись, калибровка, PDF/CSV и 0.3.0 не реализовывались. Новые архивы со специальными событиями не обещают корректного отображения разрывов старой версией 0.2.0; чтение старых архивов новой версией проверено.
