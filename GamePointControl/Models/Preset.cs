namespace GamePointControl.Models;

/// <summary>
/// Пресет — сохранённый набор параметров (ТЗ 4.4.1).
/// Сериализация в JSON выполняется вручную (без System.Text.Json source-gen),
/// чтобы быть устойчивым к trimming.
/// </summary>
public class Preset
{
    public string Id { get; set; } = Guid.NewGuid().ToString("N");
    public string Name { get; set; } = "";
    public DeviceType Type { get; set; } = DeviceType.CapturePoint;

    /// <summary>WIN_TIME, минут (точка захвата).</summary>
    public double WinTimeMin { get; set; } = 1.5;

    /// <summary>switchDelay, секунд (точка захвата).</summary>
    public double SwitchDelaySec { get; set; } = 45;

    /// <summary>Интервал воскрешения, минут (аптечка).</summary>
    public int MedkitIntervalMin { get; set; } = 10;

    public string ToJson() =>
        "{" +
        $"\"id\":\"{Escape(Id)}\"," +
        $"\"name\":\"{Escape(Name)}\"," +
        $"\"type\":{(int)Type}," +
        $"\"win\":{WinTimeMin.ToString(System.Globalization.CultureInfo.InvariantCulture)}," +
        $"\"delay\":{SwitchDelaySec.ToString(System.Globalization.CultureInfo.InvariantCulture)}," +
        $"\"interval\":{MedkitIntervalMin}" +
        "}";

    public static Preset? FromJson(string json)
    {
        try
        {
            var p = new Preset();
            p.Id = GetString(json, "id") ?? p.Id;
            p.Name = GetString(json, "name") ?? "";
            p.Type = (DeviceType)GetInt(json, "type", 0);
            p.WinTimeMin = GetDouble(json, "win", 1.5);
            p.SwitchDelaySec = GetDouble(json, "delay", 45);
            p.MedkitIntervalMin = GetInt(json, "interval", 10);
            return p;
        }
        catch
        {
            return null;
        }
    }

    private static string Escape(string s) => s.Replace("\\", "\\\\").Replace("\"", "\\\"");

    private static string? GetString(string json, string key)
    {
        var idx = json.IndexOf($"\"{key}\":\"", StringComparison.Ordinal);
        if (idx < 0) return null;
        var start = idx + key.Length + 4;
        var sb = new System.Text.StringBuilder();
        for (var i = start; i < json.Length; i++)
        {
            var c = json[i];
            if (c == '\\' && i + 1 < json.Length) { sb.Append(json[++i]); continue; }
            if (c == '"') break;
            sb.Append(c);
        }
        return sb.ToString();
    }

    private static string GetNumberRaw(string json, string key)
    {
        var idx = json.IndexOf($"\"{key}\":", StringComparison.Ordinal);
        if (idx < 0) return "";
        var start = idx + key.Length + 3;
        var end = start;
        while (end < json.Length && (char.IsDigit(json[end]) || json[end] == '-' || json[end] == '.'))
            end++;
        return json[start..end];
    }

    private static int GetInt(string json, string key, int def) =>
        int.TryParse(GetNumberRaw(json, key), out var v) ? v : def;

    private static double GetDouble(string json, string key, double def) =>
        double.TryParse(GetNumberRaw(json, key), System.Globalization.NumberStyles.Float,
            System.Globalization.CultureInfo.InvariantCulture, out var v) ? v : def;
}
