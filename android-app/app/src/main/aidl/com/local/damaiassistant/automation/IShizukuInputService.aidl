package com.local.damaiassistant.automation;

interface IShizukuInputService {
    boolean warmUp();
    int tap(int x, int y, int downUpDelayMillis);
    int currentMode();
}
