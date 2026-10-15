package com.gamepoint.control

/**
 * Тип игрового устройства.
 */
enum class DeviceType {
    /** Точка захвата (команды T, D, R). */
    CAPTURE_POINT,

    /** Аптечка (команды I, R). */
    MEDKIT;

    companion object {
        fun fromName(name: String?): DeviceType {
            val n = name?.lowercase() ?: return CAPTURE_POINT
            return if (n.contains("аптеч") || n.contains("med") || n.contains("kit") || n.contains("resurrect")) {
                MEDKIT
            } else {
                CAPTURE_POINT
            }
        }
    }
}
