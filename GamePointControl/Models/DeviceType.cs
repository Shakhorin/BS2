namespace GamePointControl.Models;

/// <summary>Тип управляемого устройства (ТЗ 4.2 / 4.3).</summary>
public enum DeviceType
{
    CapturePoint, // Точка захвата
    Medkit        // Аптечка
}

public static class DeviceTypeExtensions
{
    public static string ToRussian(this DeviceType type) => type switch
    {
        DeviceType.Medkit => "Аптечка",
        _ => "Точка захвата"
    };

    /// <summary>Эвристика автоопределения типа по имени устройства (ТЗ 4.4.3).</summary>
    public static DeviceType FromName(string? name)
    {
        var n = (name ?? "").ToLowerInvariant();
        if (n.Contains("med") || n.Contains("апт") || n.Contains("heal") || n.Contains("revive"))
            return DeviceType.Medkit;
        return DeviceType.CapturePoint;
    }
}
