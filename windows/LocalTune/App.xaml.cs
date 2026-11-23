using LocalTune.Core;
using Microsoft.UI.Dispatching;
using Microsoft.UI.Xaml;

namespace LocalTune;

public partial class App : Application
{
    private Window? window;

    public App()
    {
        InitializeComponent();
        // Anything that goes wrong is written to %APPDATA%\LocalTune\crash.log, to send in with a report.
        UnhandledException += (_, e) => { CrashLog.Write(e.Exception); e.Handled = true; };
        AppDomain.CurrentDomain.UnhandledException += (_, e) => CrashLog.Write(e.ExceptionObject as Exception);
        TaskScheduler.UnobservedTaskException += (_, e) => { CrashLog.Write(e.Exception); e.SetObserved(); };
    }

    protected override void OnLaunched(LaunchActivatedEventArgs args)
    {
        try
        {
            var ui = DispatcherQueue.GetForCurrentThread();
            // The library first, from its cache, so the last session's queue can be found in it.
            MusicLibrary.Start(ui);
            History.Start(ui);
            Player.Start(ui);
            window = new MainWindow();
            window.Activate();
        }
        catch (Exception e)
        {
            CrashLog.Write(e);
            throw;
        }
    }
}

public static class CrashLog
{
    public static void Write(Exception? e)
    {
        if (e == null) return;
        try { File.AppendAllText(Path.Combine(AppDir.Root, "crash.log"), $"{DateTime.Now:yyyy-MM-dd HH:mm:ss}\n{e}\n\n"); }
        catch { }
    }
}
