// src/test/java/com/neurasys/bridge/JniHarness.java
package com.neurasys.bridge;

public class JniHarness {
    public static void main(String[] args) {
        NativeFileMonitor nfm = new NativeFileMonitor();
        nfm.triggerTestCallback(42, "C:\\Temp", (id, fullPath, fileName, action, size, ts) -> {
            System.out.println("JAVA: id=" + id + ", action=" + action + ", file=" + fileName +
                    ", size=" + size + ", ts=" + ts);
        });
        System.out.println("JAVA: ✓ Harness completed.");
    }
}
