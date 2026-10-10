package nhcm.capitalmc.mixin;

import com.sun.management.OperatingSystemMXBean;
import net.minecraft.SystemReport;
import nhcm.capitalmc.WindowsDiagnostics;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import oshi.software.os.OperatingSystem;

import java.lang.management.ManagementFactory;
import java.util.Locale;

@Mixin(SystemReport.class)
public abstract class SystemReportMixin
{
    @Inject(method = "lambda$putSoftware$0", at = @At("HEAD"), cancellable = true)
    private void capitalmc$processMetrics(OperatingSystem system, CallbackInfo ci)
    {
        if (!WindowsDiagnostics.useJvmProcessMetrics)
        {
            return;
        }
        var report = (SystemReport) (Object) this;
        var bean = ManagementFactory.getOperatingSystemMXBean();
        if (bean instanceof OperatingSystemMXBean process)
        {
            double load = process.getProcessCpuLoad();
            long memory = process.getCommittedVirtualMemorySize();
            report.setDetail("Process Loads", load < 0 ? "Unavailable (JVM)" : String.format(Locale.ROOT, "%.2f%% (JVM)", load * 100));
            report.setDetail("Process Committed Virtual Size (MiB)", memory < 0 ? "Unavailable (JVM)" : String.format(Locale.ROOT, "%.2f (JVM)", SystemReport.sizeInMiB(memory)));
        }
        else
        {
            report.setDetail("Process Loads", "Unavailable (Windows performance counters)");
            report.setDetail("Process Committed Virtual Size (MiB)", "Unavailable (Windows performance counters)");
        }
        report.setDetail("Process Resident Size (MiB)", "Unavailable (Windows performance counters)");
        ci.cancel();
    }
}
