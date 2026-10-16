package com.nnazuna.nnaclicker;

// Dijalankan Shizuku sebagai proses shell (uid 2000): boleh membaca /dev/input dan menyuntik sentuhan.
interface IInputService {
    void destroy() = 16777114;

    String probe() = 1;

    boolean startRecord(int natW, int natH, int rotation) = 2;
    int recordCount() = 3;
    int stopRecord() = 4;
    String readResult(int offset, int length) = 5;

    void beginUpload() = 6;
    void appendUpload(String chunk) = 7;
    boolean play(int loops, float speed, long intervalMs, long startDelayMs, float jitterPx, float jitterPct) = 8;
    void stopPlay() = 9;
    int playLoop() = 10;

    String lastError() = 11;

    // Full-resolution PNG of the screen, base64; read it with readResult(). Returns its length.
    int snap() = 12;

    // Index of the step being played, or -1.
    int playStep() = 13;

    // Pause or resume playback (waits and delays stop counting while paused).
    void setPaused(boolean paused) = 14;
}
