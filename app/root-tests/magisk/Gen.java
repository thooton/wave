// Writes the root scripts the Wave app sends, exactly as it builds them, for sim.py.
// The module id is the placeholder wave_module_id.
import com.wave.xp3.core.root.*;
import java.nio.file.*;
public class Gen {
    public static void main(String[] a) throws Exception {
        Path o = Paths.get(a[0]);
        Files.createDirectories(o);
        Files.write(o.resolve("install-watchdog.sh"), Watchdog.install().getBytes());
        Files.write(o.resolve("install-module.sh"), Scripts.installModule("/data/local/tmp/module.zip", "wave_module_id").getBytes());
        Files.write(o.resolve("remove-module.sh"), Scripts.removeModule("wave_module_id").getBytes());
        Files.write(o.resolve("enable-module.sh"), Scripts.enableModule("wave_module_id").getBytes());
        Files.write(o.resolve("read-watchdog.sh"), Watchdog.read().getBytes());
        Files.write(o.resolve("list-modules.sh"), Scripts.listModules().getBytes());
    }
}
