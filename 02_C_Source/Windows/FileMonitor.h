/*
 * FileMonitor.h - Header file for native file monitoring library
 * JNI interface for Windows file system monitoring using ReadDirectoryChangesW API
 */

#ifndef FILE_MONITOR_H
#define FILE_MONITOR_H

#include <jni.h>
#include <windows.h>

/* JNI Method: Start monitoring a directory */
JNIEXPORT void JNICALL Java_com_neurasys_service_NativeFileMonitor_startMonitoring
  (JNIEnv *env, jobject obj, jstring path, jobject callback);

#endif // FILE_MONITOR_H
