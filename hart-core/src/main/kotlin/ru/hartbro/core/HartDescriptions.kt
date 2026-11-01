package ru.hartbro.core

/** Small explicit dictionary, not a guess at the model from its device type. */
object HartManufacturers {
    fun name(code: Int): String? = when (code) {
        0x0099 -> "Метран"
        0x0026 -> "Rosemount"
        0x0032 -> "Viatran"
        else -> null
    }
}

data class HartUnit(val symbol: String, val name: String)

/** Standard pressure/temperature units used by the first supported device classes. */
object HartUnits {
    fun find(code: Int): HartUnit? = when (code) {
        1 -> HartUnit("inH₂O (68 °F)", "дюйм водяного столба при 68 °F")
        2 -> HartUnit("inHg (0 °C)", "дюйм ртутного столба при 0 °C")
        3 -> HartUnit("ftH₂O (68 °F)", "фут водяного столба при 68 °F")
        4 -> HartUnit("mmH₂O (68 °F)", "миллиметр водяного столба при 68 °F")
        5 -> HartUnit("mmHg (0 °C)", "миллиметр ртутного столба при 0 °C")
        6 -> HartUnit("psi", "фунт-сила на квадратный дюйм")
        7 -> HartUnit("bar", "бар")
        8 -> HartUnit("mbar", "миллибар")
        9 -> HartUnit("g/cm²", "грамм-сила на квадратный сантиметр")
        10 -> HartUnit("kg/cm²", "килограмм-сила на квадратный сантиметр")
        11 -> HartUnit("Pa", "паскаль")
        12 -> HartUnit("kPa", "килопаскаль")
        13 -> HartUnit("torr", "торр")
        14 -> HartUnit("atm", "физическая атмосфера")
        32 -> HartUnit("°C", "градус Цельсия")
        33 -> HartUnit("°F", "градус Фаренгейта")
        34 -> HartUnit("°R", "градус Ранкина")
        35 -> HartUnit("K", "кельвин")
        57 -> HartUnit("%", "процент")
        237 -> HartUnit("MPa", "мегапаскаль")
        else -> null
    }
}

object HartStatus {
    /** The second response byte, independent of the first byte's response/communication code. */
    fun deviceFlags(status: Int): List<String> {
        require(status in 0..255)
        return listOf(
            0x80 to "неисправность прибора", 0x40 to "конфигурация изменена", 0x20 to "холодный запуск",
            0x10 to "доступен дополнительный статус", 0x08 to "токовый выход зафиксирован",
            0x04 to "насыщение токового выхода", 0x02 to "дополнительная переменная вне пределов",
            0x01 to "основная переменная вне пределов",
        ).filter { status and it.first != 0 }.map { it.second }
    }

    fun deviceDescription(status: Int): String = deviceFlags(status).ifEmpty {
        listOf("флаги состояния устройства не установлены")
    }.joinToString("; ")

    fun responseDescription(code: Int): String {
        require(code in 0..255)
        if (code and 0x80 != 0) return "ошибка связи 0x%02X".format(code)
        return when (code) {
            0 -> "успешный ответ"
            2 -> "недопустимый выбор параметра"
            3 -> "параметр выше допустимого предела"
            4 -> "параметр ниже допустимого предела"
            5 -> "недостаточно данных в запросе"
            6 -> "ошибка команды, специфичная для устройства"
            7 -> "защита от записи"
            8 -> "ошибка обновления параметра"
            32 -> "прибор занят"
            64 -> "команда не реализована прибором"
            else -> "код ответа $code (см. спецификацию команды)"
        }
    }
}
