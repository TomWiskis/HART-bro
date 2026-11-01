package ru.hartbro.core

/** Entries are keyed by manufacturer AND full device type. See docs/HART_PROFILES_0.5.3.md. */
data class DeviceModel(val manufacturer: Int, val deviceType: Int, val name: String, val source: String)
object HartDeviceCatalog {
    val models = listOf(
        DeviceModel(0x32, 0x3201, "970", "44520f08-1fb2-e811-8153-e0071b66aea1"),
        DeviceModel(0x99, 0x9979, "Метран-150", "20530f08-1fb2-e811-8153-e0071b66aea1"),
        DeviceModel(0x99, 0x997F, "Метран-100", "0b0f1702-1fb2-e811-8153-e0071b66aea1"),
        DeviceModel(0x99, 0x9977, "Метран-303", "83530f08-1fb2-e811-8153-e0071b66aea1"),
        DeviceModel(0x99, 0x997E, "Метран-280-2", "0e0f1702-1fb2-e811-8153-e0071b66aea1"),
        DeviceModel(0x26, 0x268F, "Rosemount 3051", "67735c83-c181-eb11-b1ac-000d3a593cd0"),
        DeviceModel(0x26, 0x2606, "Rosemount 3051", "320d1702-1fb2-e811-8153-e0071b66aea1"),
    )
    fun model(identity: DeviceIdentity): String? = models.firstOrNull { it.manufacturer == identity.manufacturerCode && it.deviceType == identity.deviceType }?.name
}
