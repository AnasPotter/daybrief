package com.anas.secretary.edge;

import android.app.PendingIntent;
import android.view.MotionEvent;
import android.view.Surface;

/** Runs as the shell user through Shizuku. See PanelShell. */
interface IPanelService {
    /** Shizuku calls this to stop the service. */
    void destroy() = 16777114;

    int create(in Surface surface, int w, int h, int dpi) = 1;
    boolean launch(int display, in PendingIntent pi, String component, int user) = 2;
    oneway void touch(int display, in MotionEvent ev) = 3;
    oneway void key(int display, int code) = 4;
    void release(int display) = 5;
    void resize(int display, int w, int h, int dpi) = 6;
}
