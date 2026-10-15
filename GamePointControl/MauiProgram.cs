using Microsoft.Extensions.Logging;

namespace GamePointControl;

public static class MauiProgram
{
    public static MauiApp CreateMauiApp()
    {
        var builder = MauiApp.CreateBuilder();
        builder.UseMauiApp<MauiProgramApp>();

#if DEBUG
        builder.Logging.AddDebug();
#endif

        return builder.Build();
    }
}
