package com.dadatu.roothelper;

import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.Locale;

public class RootProbeMain {
    public static void main(String[] args) throws Exception {
        String ts = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date());
        String line = "RootProbeMain OK ts=" + ts
            + " uid=" + android.os.Process.myUid()
            + " pid=" + android.os.Process.myPid()
            + " args=" + Arrays.toString(args)
            + "\n";
        try (FileOutputStream fos = new FileOutputStream("/data/local/tmp/root-probe.log", true)) {
            fos.write(line.getBytes(StandardCharsets.UTF_8));
            fos.flush();
        }
        System.out.println(line);
        System.out.flush();
    }
}
