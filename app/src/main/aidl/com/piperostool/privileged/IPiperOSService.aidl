package com.piperostool.privileged;

import android.os.Bundle;
import android.os.ParcelFileDescriptor;

interface IPiperOSService {
    int getProtocolVersion();
    Bundle getStatus();
    String[] getRecentLogs(long sinceTimestamp);
    Bundle getCapabilities();
    ParcelFileDescriptor openDirectory(String path, boolean showHidden);
    Bundle stat(String path);
    ParcelFileDescriptor openRead(String path);
    boolean mkdir(String path);
    boolean rename(String source, String destination);
    boolean delete(String path, boolean recursive);
    boolean chmod(String path, int mode);
    boolean chown(String path, int uid, int gid);
    void refreshCapabilities();
    void reconnectAdb();
    boolean isAdbEnabled();
    void setAdbEnabled(boolean enabled);
    Bundle runAppAction(String action, String packageName, String activityName);
    void shutdown();
}
