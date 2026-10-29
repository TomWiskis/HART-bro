# Команды чтения 0.2.0

Источник форматов — открытая реализация **FieldComm Group hipflowapp**, коммит `1c5b22112b95f4e7e14cb62539c3806f98040c1e`, и HART pass-through диссектор Wireshark. Исходники изучены, в production не копировались. Это проверка по реализации разработчиков протокола, не заявление о сертификации/полном доступе к нормативной спецификации. Нормативный сайт https://www.fieldcommgroup.org/hart-specifications и developer.android.com возвращали сетевой 403; настройки среды не расширялись.

Ссылки на проверенные поля:

- [FCG команды 3](https://github.com/FieldCommGroup/hipflowapp/blob/1c5b22112b95f4e7e14cb62539c3806f98040c1e/hipflowapp/Hip_Native/Commands/cmd_03.h), [8](https://github.com/FieldCommGroup/hipflowapp/blob/1c5b22112b95f4e7e14cb62539c3806f98040c1e/hipflowapp/Hip_Native/Commands/cmd_08.h), [9](https://github.com/FieldCommGroup/hipflowapp/blob/1c5b22112b95f4e7e14cb62539c3806f98040c1e/hipflowapp/Hip_Native/Commands/cmd_09.h), [48](https://github.com/FieldCommGroup/hipflowapp/blob/1c5b22112b95f4e7e14cb62539c3806f98040c1e/hipflowapp/Hip_Native/Commands/cmd_48.h), [50](https://github.com/FieldCommGroup/hipflowapp/blob/1c5b22112b95f4e7e14cb62539c3806f98040c1e/hipflowapp/Hip_Native/Commands/cmd_50.h).
- [FCG PV.cpp](https://github.com/FieldCommGroup/hipflowapp/blob/1c5b22112b95f4e7e14cb62539c3806f98040c1e/hipflowapp/Hip_Native/PV.cpp): коды 246–249, восьмибайтовые записи и timestamp команды 9.
- [FCG PhysicalAlarms.h](https://github.com/FieldCommGroup/hipflowapp/blob/1c5b22112b95f4e7e14cb62539c3806f98040c1e/hipflowapp/Hip_Native/PhysicalAlarms.h): качество `00` bad, `40` poor, `80` fixed, `C0` good в двух старших битах. Нижние биты сохраняются как raw status.
- [Wireshark packet-hartip.c](https://github.com/wireshark/wireshark/blob/master/epan/dissectors/packet-hartip.c), SHA-256 прочитанного файла `167f8c2efc66267471e8d8b994a45ba0a78eb1ef9cad499a16b27349caab6e49`: дополнительные структуры ответов 3/8/9/48.

После response code и device status:

| № | Запрос | Данные успешного ответа, используемые здесь |
|---|---|---|
| 0 | пустой, short 0 | прежний парсер идентификации 5–7 |
| 1 | пустой, long | unit u8 + float32 BE, ровно 5 байтов |
| 3 | пустой | float32 BE loop mA + 1–4 блока `(unit u8, float32 BE)`; принимаются 9/14/19/24 байта |
| 8 | пустой, HART 6+ | четыре кода классификаций |
| 9 | 1–4 запрошенных кода в текущем мониторе | extended status + для каждого кода `(echo u8, class u8, unit u8, float32 BE, status u8)`, HART 7 также timestamp u32 BE; timestamp остаётся техническим полем, ось использует время получения |
| 48 | **пустой** | минимум 6 байтов; все байты диагностики сохраняются в технической записи без догадок о manufacturer-specific флагах |
| 50 | пустой | четыре кода назначений PV/SV/TV/QV |

Команда 9 HART 7 запрашивает `F6 F7 F8 F9`; HART 6 использует четыре валидных назначения из 50, иначе 3. HART 5 не опрашивается командами 8/9. Ответ 64 отключает способ для всей сессии, в том числе после восстановления RFCOMM. Ответ 2 на 9 отключает неподдержанный набор кодов и переключает на 3. Прочие ошибки не считаются «нет поддержки»; они проходят через обычный контроль ошибок и восстановление. Никаких повторов команд записи: в allowlist их нет.

Классификация не подменяет назначение модели. Интерфейс показывает PV/SV/TV/QV, codes/classes и единицы; SV не объявляется температурой, TV — статическим давлением. Неизвестные единицы остаются кодами. NaN/Infinity, unit 250, bad/poor/fixed не рисуются как достоверные измерения. Для 1/3 индивидуальное качество не передаётся: подпись «не сообщено», глобальные fault/out-of-limits учитываются консервативно. Cold Start/Configuration Changed показываются и не сбрасываются. Команда 38 отсутствует.

Кодек и очередь 0.1.1 сохранены. Новые тесты используют реальные кадры пользователя с Cold Start и PV `−0.01330386 кПа`, а синтетические наборы остальных команд существуют только в test source set. Данные команд 3/9 на реальном Метране пока неизвестны.
