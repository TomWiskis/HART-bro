package ru.hartbro.core

/** A standard optional command is offered independently of the local model catalogue. */
enum class PressureKind(val label:String,val warning:String) {
    UNKNOWN("Тип давления неизвестен","Создайте нулевое воздействие по процедуре конкретного прибора. Атмосферное давление нельзя автоматически считать физическим нулём."),
    GAUGE("Избыточное","Подайте правильное нулевое избыточное давление по руководству прибора."),
    DIFFERENTIAL("Дифференциальное","Обеспечьте равное давление на обоих входах по процедуре прибора."),
    ABSOLUTE("Абсолютное","Нужен эталонный вакуум / установленная изготовителем процедура. Атмосферное давление не является абсолютным нулём.")
}
enum class ZeroHardwareStatus { UNTESTED, DOCUMENTED, TESTED_ON_DEVICE }
sealed interface ZeroProcedure {
    data object Primary43:ZeroProcedure
    data class DeviceVariable52(val variableCode:Int):ZeroProcedure { init{require(variableCode in 0..255)} }
    data class Manufacturer(val command:Int,val data:List<Byte>):ZeroProcedure {init{require(command in 128..253 && data.size<=255)}}
}
data class ZeroMethodProfile(val id:String,val manufacturer:Int,val deviceType:Int,val deviceRevisions:Set<Int>,
    val procedure:ZeroProcedure,val purpose:String,val expectedResponses:Set<Int>,val conditions:String,
    val source:String,val status:ZeroHardwareStatus) {
    init {require(id.isNotBlank() && deviceRevisions.isNotEmpty() && source.isNotBlank() && conditions.isNotBlank() && purpose.isNotBlank())}
    fun matches(i:DeviceIdentity)=i.manufacturerCode==manufacturer && i.deviceType==deviceType && i.deviceRevision in deviceRevisions
}
data class ZeroMethod(val id:String,val label:String,val procedure:ZeroProcedure,val source:String,
    val conditions:String,val hardwareStatus:ZeroHardwareStatus) {
    val command get()=when(val p=procedure){ZeroProcedure.Primary43->43;is ZeroProcedure.DeviceVariable52->52;is ZeroProcedure.Manufacturer->p.command}
}
class ZeroMethods(private val profiles:List<ZeroMethodProfile> = confirmedProfiles) {
    companion object {val confirmedProfiles=listOf(
        ZeroMethodProfile("viatran970-43",0x32,0x3201,setOf(2),ZeroProcedure.Primary43,"Trim PV Zero",setOf(0,7,9,10,16,32,64),
            "Правильное нулевое давление по руководству Viatran 970","98MANAL970 Rev.D, p.27; HART 5 / Device Revision 2",ZeroHardwareStatus.DOCUMENTED)
    )}
    fun available(identity:DeviceIdentity)=listOf(ZeroMethod("hart43","Стандартная установка нуля PV",ZeroProcedure.Primary43,
        "HCF_SPEC-151 rev.8, §6.8, Command 43 — Set Primary Variable Zero",
        "Подготовленный датчик давления, правильное нулевое воздействие, отдельное подтверждение пользователя",ZeroHardwareStatus.UNTESTED))+
        profiles.filter{it.matches(identity)&&it.status!=ZeroHardwareStatus.UNTESTED&&it.procedure!=ZeroProcedure.Primary43}.map{
            ZeroMethod(it.id,it.purpose,it.procedure,it.source,it.conditions,it.status)
        }
    fun select(identity:DeviceIdentity,id:String)=available(identity).firstOrNull{it.id==id}
        ?: throw IllegalArgumentException("Нет подтверждённого метода для этой модели и ревизии")
}
data class ZeroOptions(val methodId:String="hart43",val pressureKind:PressureKind=PressureKind.UNKNOWN,
    val conditionsConfirmed:Boolean=false,val pressureConfirmed:Boolean=false,val tolerance:Float?=null,
    val resolution:Float?=null,val accuracySource:String="",val unitCode:Int?=null) {
    init {
        require(tolerance==null || tolerance.isFinite()&&tolerance>=0)
        require(resolution==null || resolution.isFinite()&&resolution>0)
        require((tolerance==null&&resolution==null)||accuracySource.isNotBlank()) {"Укажите источник допуска/разрешения датчика"}
    }
}
data class ZeroValue(val value:Float,val unitCode:Int,val at:java.time.Instant) {
    init{require(value.isFinite() && unitCode in 0..255)}
    val text get()="$value ${HartUnits.find(unitCode)?.symbol ?: "ед. $unitCode"}"
}
data class ZeroPreparation(val identity:DeviceIdentity,val before:ZeroValue?,val unitCode:Int?,val writeProtected:Boolean?,
    val methods:List<ZeroMethod>,val errors:List<String> = emptyList())
fun isPressureUnit(code:Int?) = code in 1..14 || code in setOf(237,239,171)
fun knownNonPressureUnit(code:Int?) = code in 32..35 || code==57
enum class SensorOutcome(val label:String) {
    SUCCEEDED("Результат подтверждён измерением"), ACCEPTED_UNVERIFIED("Команда принята, результат измерением не подтверждён"),
    NOT_SUPPORTED("Команда не поддерживается этим прибором"), WRITE_PROTECTED("Запись защищена"),
    CONDITIONS_REJECTED("Недопустимые условия установки нуля"), BUSY("Устройство занято"), DEVICE_ERROR("Прибор вернул ошибку"),
    NO_ACK("Ответ не получен; результат записи неизвестен"), INVALID_RESPONSE("Ответ повреждён; результат записи неизвестен"),
    NOT_VERIFIED("Измерение не подтвердило установку нуля"), ABORTED("Команда не отправлена"), UNKNOWN("Результат не подтверждён")
}

object ZeroResponses {
    fun description(command:Int,code:Int):String = if(command in setOf(43,52))when(code){
        9->"Приложенное воздействие выше допустимого для установки нуля"
        10->"Приложенное воздействие ниже допустимого для установки нуля"
        else->HartStatus.responseDescription(code)
    }else HartStatus.responseDescription(code)
}
