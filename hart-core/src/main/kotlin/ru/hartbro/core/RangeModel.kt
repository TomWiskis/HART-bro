package ru.hartbro.core

import java.math.BigDecimal
import java.nio.ByteBuffer
import java.time.Instant

/** Command 35 is never a ReadCommand. Wire order is units, URV, LRV. */
data class WriteRange(val range:ConfiguredRange) {
    init { require(validUnit(range.unitCode));require(range.lower.isFinite() && range.upper.isFinite() && range.upper>range.lower) }
    fun bytes():ByteArray = ByteBuffer.allocate(9).put(range.unitCode.toByte()).putFloat(range.upper).putFloat(range.lower).array()
    companion object { fun validUnit(code:Int)=code in 1..249 || code==253 }
}
data class RangeSnapshot(val identity:DeviceIdentity,val range:ConfiguredRange,val sensor:SensorInformation?,val tag:String?,
                         val writeProtected:Boolean?,val configuration:ConfigurationSnapshot,val readAt:Instant=Instant.now(),val warnings:List<String> = emptyList())
data class RangeValidation(val range:ConfiguredRange?=null,val errors:List<String> = emptyList(),val warnings:List<String> = emptyList(),val unchanged:Boolean=false) {
    val allowed get()=range!=null && errors.isEmpty() && !unchanged
}
object RangeRules {
    fun parse(text:String):Float {
        val value=text.trim().replace(',','.')
        require(value.length in 1..80 && value.matches(Regex("[+-]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?"))) { "Введите конечное число с точкой или запятой" }
        val decimal=BigDecimal(value);val number=decimal.toFloat()
        require(number.isFinite() && (number!=0f || decimal.signum()==0)) { "Число не представимо конечным ненулевым Float32" }
        return number
    }
    fun validate(snapshot:RangeSnapshot,lower:String,upper:String):RangeValidation {
        val errors=mutableListOf<String>();val warnings=snapshot.warnings.toMutableList()
        val lo=runCatching{parse(lower)}.getOrElse { errors+="Нижняя граница: ${it.message}";null }
        val hi=runCatching{parse(upper)}.getOrElse { errors+="Верхняя граница: ${it.message}";null }
        if(lo==null || hi==null) return RangeValidation(errors=errors,warnings=warnings)
        if(hi<=lo) errors+="URV должна быть больше LRV, в том числе после преобразования в Float32"
        if(!WriteRange.validUnit(snapshot.range.unitCode)) errors+="Код единицы ${snapshot.range.unitCode} недопустим для команды 35"
        if(snapshot.writeProtected==true) errors+="В приборе включена защита записи"
        if(snapshot.writeProtected==null) warnings+="Состояние защиты записи неизвестно; результат определит прибор"
        if(HartUnits.find(snapshot.range.unitCode)==null) warnings+="Единица HART ${snapshot.range.unitCode}: исходный код сохраняется без подмены"
        val sensor=snapshot.sensor
        val conversion=sensor?.let{UnitConversion.between(it.unitCode,snapshot.range.unitCode)}
        if(sensor==null || conversion==null || sensor.lowerLimit==null || sensor.upperLimit==null || sensor.upperLimit<=sensor.lowerLimit) {
            warnings+="Пределы сенсора не получены или единицы несопоставимы. Проверка физических ограничений недоступна."
        } else {
            val low=conversion.value(sensor.lowerLimit);val high=conversion.value(sensor.upperLimit)
            if(lo.toDouble()<low-conversion.tolerance(sensor.lowerLimit,lo)) errors+="LRV ниже физического предела сенсора"
            if(hi.toDouble()>high+conversion.tolerance(sensor.upperLimit,hi)) errors+="URV выше физического предела сенсора"
        }
        val credibleSpan=sensor?.minimumSpan?.takeIf { span -> span>0 && (sensor.lowerLimit==null || sensor.upperLimit==null || sensor.upperLimit<=sensor.lowerLimit || span.toDouble()<=sensor.upperLimit.toDouble()-sensor.lowerLimit.toDouble()) }
        if(credibleSpan==null || conversion==null) warnings+="Минимальная ширина диапазона не проверена: нет достоверного сопоставимого Minimum Span"
        else {
            val span=hi.toDouble()-lo.toDouble();val minimum=conversion.span(credibleSpan)
            val uncertainty=conversion.tolerance(credibleSpan,hi)+Math.ulp(lo).toDouble()/2
            if(span<minimum-uncertainty) errors+="Ширина диапазона меньше минимально допустимой"
        }
        val range=ConfiguredRange(lo,hi,snapshot.range.unitCode)
        return RangeValidation(range,errors,warnings.distinct(),sameRange(range,snapshot.range))
    }
}
/** SI pressure scale factors are exact: Pa/kPa/MPa; bar=100000 Pa, mbar=100 Pa.
 * Temperature: K=C+273.15=(F+459.67)*5/9=R*5/9. Span never receives an offset.
 * Comparisons account only for half-ULP uncertainty of Float32 input and output. */
data class UnitConversion(private val scale:Double,private val offset:Double) {
    fun value(value:Float)=value.toDouble()*scale+offset
    fun span(value:Float)=value.toDouble()*kotlin.math.abs(scale)
    fun tolerance(source:Float,target:Float)=Math.ulp(source).toDouble()/2*kotlin.math.abs(scale)+Math.ulp(target).toDouble()/2
    companion object {
        fun between(from:Int,to:Int):UnitConversion? {
            if(from==to && WriteRange.validUnit(from)) return UnitConversion(1.0,0.0)
            val pressure=mapOf(11 to 1.0,12 to 1000.0,237 to 1_000_000.0,7 to 100_000.0,8 to 100.0)
            if(from in pressure && to in pressure) return UnitConversion(pressure.getValue(from)/pressure.getValue(to),0.0)
            val temperatures=mapOf(32 to (1.0 to 273.15),33 to (5.0/9 to 459.67*5/9),34 to (5.0/9 to 0.0),35 to (1.0 to 0.0))
            val a=temperatures[from] ?: return null;val b=temperatures[to] ?: return null
            return UnitConversion(a.first/b.first,(a.second-b.second)/b.first)
        }
    }
}
fun sameRange(a:ConfiguredRange,b:ConfiguredRange)=a.unitCode==b.unitCode && a.lower==b.lower && a.upper==b.upper
fun rangeReply(data:ByteArray):ConfiguredRange {
    require(data.size==9){"Команда 35: ожидается 9 байтов, получено ${data.size}"}
    val buffer=ByteBuffer.wrap(data);val unit=buffer.get().u8();val upper=buffer.float;val lower=buffer.float
    require(lower.isFinite() && upper.isFinite() && WriteRange.validUnit(unit)){"Некорректные значения ответа 35"}
    return ConfiguredRange(lower,upper,unit)
}
enum class RangeOutcome { PENDING, SUCCEEDED, APPLIED_CHANGED, REJECTED, UNCHANGED, UNKNOWN, DIFFERENT_DEVICE, ABORTED }
data class RangeVerification(val identity:DeviceIdentity,val configuration:ConfigurationSnapshot,val readAt:Instant=Instant.now()) {
    val range get()=configuration.range!!
}
data class RangeAttempt(val reply:HartExchange?=null,val verification:RangeVerification?=null,val errors:List<String> = emptyList(),val sent:Boolean=false,val aborted:Boolean=false)
fun rangeOutcome(requested:ConfiguredRange,original:ConfiguredRange,attempt:RangeAttempt):RangeOutcome {
    if(attempt.aborted) return RangeOutcome.ABORTED
    val actual=attempt.verification?.range ?: return RangeOutcome.UNKNOWN
    if(sameRange(actual,requested)) return RangeOutcome.SUCCEEDED
    if(!sameRange(actual,original)) return RangeOutcome.APPLIED_CHANGED
    val reply=attempt.reply
    if(reply!=null && RangeResponseCodes.classify(reply.responseCode)==ResponseClass.ERROR) return RangeOutcome.REJECTED
    if(reply==null) return RangeOutcome.UNCHANGED
    val echoed=reply?.let { runCatching{rangeReply(it.data)}.getOrNull() }
    return if(reply!=null && RangeResponseCodes.classify(reply.responseCode) in setOf(ResponseClass.SUCCESS,ResponseClass.WARNING) && echoed!=null && sameRange(echoed,actual)) RangeOutcome.APPLIED_CHANGED else RangeOutcome.UNKNOWN
}
