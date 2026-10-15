using Microsoft.Maui.Controls;

namespace GamePointControl;

public partial class MauiProgramApp : Application
{
    public MauiProgramApp()
    {
        MainPage = new NavigationPage(new Pages.MainPage());
    }
}
