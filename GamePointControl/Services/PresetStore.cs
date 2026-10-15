using GamePointControl.Models;

namespace GamePointControl.Services;

/// <summary>
/// Хранилище пресетов (ТЗ 4.4.1). SharedPreferences-аналог — MAUI Preferences,
/// сериализация JSON разрешена разделом 8 ТЗ.
/// </summary>
public sealed class PresetStore
{
    private const string PrefsKey = "gpc_presets";

    private static PresetStore? instance;
    public static PresetStore Current => instance ??= new PresetStore();

    public List<Preset> All()
    {
        var raw = Preferences.Default.Get(PrefsKey, "");
        if (string.IsNullOrWhiteSpace(raw)) return new List<Preset>();
        var result = new List<Preset>();
        // Формат хранения: по одному JSON-объекту на строку.
        foreach (var line in raw.Split('\n', StringSplitOptions.RemoveEmptyEntries))
        {
            var p = Preset.FromJson(line);
            if (p != null) result.Add(p);
        }
        return result;
    }

    public void Save(Preset preset)
    {
        var list = All();
        var idx = list.FindIndex(p => p.Id == preset.Id);
        if (idx >= 0) list[idx] = preset; else list.Add(preset);
        Write(list);
    }

    public void Delete(string id)
    {
        Write(All().FindAll(p => p.Id != id));
    }

    private void Write(List<Preset> list)
    {
        var raw = string.Join("\n", list.ConvertAll(p => p.ToJson()));
        Preferences.Default.Set(PrefsKey, raw);
    }
}
