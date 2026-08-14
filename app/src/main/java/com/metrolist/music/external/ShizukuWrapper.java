package com.metrolist.music.external;

import rikka.shizuku.Shizuku;

public class ShizukuWrapper {
    public static Process executeCommand(String[] command) {
        // The Java compiler handles the package-private return type gracefully
        return Shizuku.newProcess(command, null, null);
    }
    
    public static boolean pingBinder() {
        return Shizuku.pingBinder();
    }
    
    public static int checkSelfPermission() {
        return Shizuku.checkSelfPermission();
    }
    
    public static void requestPermission(int requestCode) {
        Shizuku.requestPermission(requestCode);
    }
}
