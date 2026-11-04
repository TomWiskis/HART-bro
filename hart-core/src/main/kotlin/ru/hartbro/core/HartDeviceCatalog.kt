package ru.hartbro.core

/** Entries are keyed by manufacturer AND full device type. See docs/HART_PROFILES_0.5.3.md. */
data class DeviceModel(val manufacturer: Int, val deviceType: Int, val name: String, val source: String, val deviceRevisions: Set<Int>? = null, val registeredManufacturer: String? = null) {
    val sourceUrl get() = if(source.startsWith("https://")) source else "https://www.fieldcommgroup.org/old-registered-products/$source"
}
object HartDeviceCatalog {
    val models = listOf(
        DeviceModel(0x26, 0x2662, "Метран-150R", "4a540f08-1fb2-e811-8153-e0071b66aea1", setOf(9), "Emerson Electric Co."),
        DeviceModel(0x32, 0x3201, "970", "44520f08-1fb2-e811-8153-e0071b66aea1"),
        DeviceModel(0x99, 0x9979, "Метран-150", "20530f08-1fb2-e811-8153-e0071b66aea1"),
        DeviceModel(0x99, 0x997F, "Метран-100", "0b0f1702-1fb2-e811-8153-e0071b66aea1"),
        DeviceModel(0x99, 0x9977, "Метран-303", "83530f08-1fb2-e811-8153-e0071b66aea1"),
        DeviceModel(0x99, 0x997E, "Метран-280-2", "0e0f1702-1fb2-e811-8153-e0071b66aea1"),
        DeviceModel(0x26, 0x268F, "Rosemount 3051", "67735c83-c181-eb11-b1ac-000d3a593cd0"),
        DeviceModel(0x26, 0x2606, "Rosemount 3051", "320d1702-1fb2-e811-8153-e0071b66aea1"),
    )
    fun entry(identity:DeviceIdentity):DeviceModel? = models.firstOrNull {
        it.manufacturer==identity.manufacturerCode && it.deviceType==identity.deviceType &&
            (it.deviceRevisions==null || identity.deviceRevision in it.deviceRevisions)
    }
    fun model(identity: DeviceIdentity): String? = entry(identity)?.name
    fun title(identity:DeviceIdentity):String = model(identity)?.let { if(it=="970")"Viatran 970" else it }
        ?: "${identity.manufacturerName ?: identity.manufacturerDescription} · тип 0x%04X".format(identity.deviceType)
}
