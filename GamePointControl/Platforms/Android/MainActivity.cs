using Android.App;
using Android.Content.PM;
using Android.OS;

namespace GamePointControl;

[Activity(Theme = "@style/Maui.SplashTheme", MainLauncher = true,
          LaunchMode = LaunchMode.SingleTop,
          ConfigurationChanges = ConfigChanges.ScreenSize | ConfigChanges.Orientation |
                                 ConfigChanges.UiMode | ConfigChanges.ScreenLayout |
                                 ConfigChanges.SmallestScreenSize | ConfigChanges.Density)]
public class MainActivity : MauiAppCompatActivity
{
    protected override void OnDestroy()
    {
        // Освобождение Bluetooth-ресурсов при уничтожении активности.
        Services.BtManager.Current.Shutdown();
        base.OnDestroy();
    }
}
