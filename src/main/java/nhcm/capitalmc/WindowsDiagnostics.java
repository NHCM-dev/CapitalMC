package nhcm.capitalmc;

import net.fabricmc.loader.api.entrypoint.PreLaunchEntrypoint;
import com.sun.jna.platform.win32.Advapi32Util;
import com.sun.jna.platform.win32.WinReg;
import org.slf4j.LoggerFactory;
import oshi.util.GlobalConfig;

import java.util.Locale;

/**
 * Configure Windows diagnostics before Minecraft builds its system report.
 */
public class WindowsDiagnostics implements PreLaunchEntrypoint
{
    public static boolean useJvmProcessMetrics;

    @Override
    public void onPreLaunch()
    {
        if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows")
            && System.getProperty(GlobalConfig.OSHI_OS_WINDOWS_HKEYPERFDATA) == null)
        {
            GlobalConfig.set(GlobalConfig.OSHI_OS_WINDOWS_HKEYPERFDATA, false);
            try
            {
                Advapi32Util.registryGetStringArray(WinReg.HKEY_LOCAL_MACHINE,
                                                    "SOFTWARE\\Microsoft\\Windows NT\\CurrentVersion\\Perflib\\009", "Counter");
            } catch (RuntimeException unavailable)
            {
                useJvmProcessMetrics = true;
                LoggerFactory.getLogger(WindowsDiagnostics.class).info("Windows performance counters are unavailable; system reports will use JVM process metrics. Resident memory is unavailable.");
            }
        }
    }
}
