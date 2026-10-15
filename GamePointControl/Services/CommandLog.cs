using System.Text;

namespace GamePointControl.Services;

/// <summary>Направление/тип записи журнала (ТЗ 4.4.2).</summary>
public enum LogDirection { Tx, Rx, Info, Error }

/// <summary>Запись журнала обмена.</summary>
public sealed record LogEntry(DateTime Time, LogDirection Direction, string Text)
{
    public string Formatted =>
        $"{Time:HH:mm:ss.fff} {Tag(Direction)} {Text}";

    public static string Tag(LogDirection d) => d switch
    {
        LogDirection.Tx => ">>",
        LogDirection.Rx => "<<",
        LogDirection.Error => "!!",
        _ => "--",
    };
}

/// <summary>
/// Журнал обмена командами (ТЗ 4.4.2, 7). Хранится в Preferences,
/// все ошибки дублируются сюда.
/// </summary>
public sealed class CommandLog
{
    private const string PrefsKey = "gpc_log";
    private const int MaxEntries = 500;

    private static CommandLog? instance;
    public static CommandLog Current => instance ??= new CommandLog();

    private readonly List<LogEntry> entries = new();
    private bool loaded;
    private readonly object sync = new();

    public event EventHandler? Changed;

    private void EnsureLoaded()
    {
        if (loaded) return;
        loaded = true;
        var raw = Preferences.Default.Get(PrefsKey, "");
        if (string.IsNullOrEmpty(raw)) return;
        foreach (var line in raw.Split('\n'))
        {
            var parts = line.Split('|', 3);
            if (parts.Length == 3 &&
                long.TryParse(parts[0], out var ticks) &&
                int.TryParse(parts[1], out var dir))
            {
                entries.Add(new LogEntry(
                    new DateTime(ticks), (LogDirection)dir,
                    parts[2].Replace("\\n", "\n")));
            }
        }
    }

    public void Add(LogDirection dir, string text)
    {
        lock (sync)
        {
            EnsureLoaded();
            entries.Add(new LogEntry(DateTime.Now, dir, text));
            while (entries.Count > MaxEntries) entries.RemoveAt(0);
            Persist();
        }
        Changed?.Invoke(this, EventArgs.Empty);
    }

    public void Info(string t) => Add(LogDirection.Info, t);
    public void Error(string t) => Add(LogDirection.Error, t);
    public void Tx(string t) => Add(LogDirection.Tx, t);
    public void Rx(string t) => Add(LogDirection.Rx, t);

    public List<LogEntry> Snapshot()
    {
        lock (sync)
        {
            EnsureLoaded();
            return entries.ToList();
        }
    }

    public void Clear()
    {
        lock (sync)
        {
            entries.Clear();
            Persist();
        }
        Changed?.Invoke(this, EventArgs.Empty);
    }

    /// <summary>Лог текстом для копирования (ТЗ 5.5).</summary>
    public string AsText()
    {
        var sb = new StringBuilder();
        foreach (var e in Snapshot()) sb.AppendLine(e.Formatted);
        return sb.ToString();
    }

    private void Persist()
    {
        var sb = new StringBuilder();
        foreach (var e in entries)
            sb.Append(e.Time.Ticks).Append('|')
              .Append((int)e.Direction).Append('|')
              .Append(e.Text.Replace("\n", "\\n")).Append('\n');
        Preferences.Default.Set(PrefsKey, sb.ToString());
    }
}
