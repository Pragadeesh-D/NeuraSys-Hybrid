/*
 * FileMonitor.c - Native file monitoring implementation for Windows
 * Uses ReadDirectoryChangesW API for real-time file system event detection
 * Calls back to Java via JNI for event notification
 */

#include "FileMonitor.h"
#include <windows.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

#define BUFFER_SIZE 32768
#define MAX_PATH_LEN 1024

// Cache JavaVM for thread attachment
static JavaVM* gJvm = NULL;

// Simple state for a single monitor (extend to map by id if needed)
typedef struct MonitorState {
    jint           monitorPathId;
    HANDLE         dirHandle;
    jobject        callbackGlobal;  // Global ref to Java callback
    jclass         callbackClass;   // Global ref to callback class
    jmethodID      onEventMethod;   // MethodID for onNativeFileEvent
    wchar_t        dirPathW[MAX_PATH_LEN];
    volatile LONG  running;
} MonitorState;

static MonitorState gState = {0};

// Helper: build ISO timestamp
static void build_iso_timestamp(char* out, size_t cap) {
    SYSTEMTIME st;
    GetLocalTime(&st);
    snprintf(out, cap, "%04d-%02d-%02dT%02d:%02d:%02d.%03d",
             st.wYear, st.wMonth, st.wDay, st.wHour, st.wMinute, st.wSecond, st.wMilliseconds);
}

// Helper: wide path join (dir + L'\\' + file)
static void join_path_w(wchar_t* out, size_t outCap, const wchar_t* dir, const wchar_t* file) {
    wcsncpy(out, dir, outCap - 1);
    out[outCap - 1] = L'\0';
    size_t len = wcslen(out);
    if (len + 1 < outCap) {
        out[len] = L'\\';
        out[len + 1] = L'\0';
    }
    wcsncat(out, file, outCap - wcslen(out) - 1);
}

// JNI OnLoad: cache JVM pointer
JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* reserved) {
    gJvm = vm;
    return JNI_VERSION_1_6;
}

/**
 * JNI Implementation: Start monitoring directory for file changes
 * Signature must match Java:
 * public native void startMonitoring(int monitorPathId, String path, NativeFileEventCallback callback);
 *
 * Callback method signature:
 * void onNativeFileEvent(int monitorPathId,
 *                        String fullPath,
 *                        String fileName,
 *                        String action,
 *                        long fileSize,
 *                        String timestamp);
 * -> (ILjava/lang/String;Ljava/lang/String;Ljava/lang/String;JLjava/lang/String;)V
 */
JNIEXPORT void JNICALL Java_com_neurasys_bridge_NativeFileMonitor_startMonitoring
  (JNIEnv* env, jobject self, jint monitorPathId, jstring jPath, jobject jCallback)
{
    if (jPath == NULL || jCallback == NULL) {
        fprintf(stderr, "[C] Error: Null path or callback\n");
        return;
    }

    // Convert Java String to UTF-16 for Windows APIs
    const jchar* jchars = (*env)->GetStringChars(env, jPath, NULL);
    if (!jchars) {
        fprintf(stderr, "[C] Error: GetStringChars failed\n");
        return;
    }

    // Copy into state as wide string
    wcsncpy(gState.dirPathW, (const wchar_t*)jchars, MAX_PATH_LEN - 1);
    gState.dirPathW[MAX_PATH_LEN - 1] = L'\0';
    (*env)->ReleaseStringChars(env, jPath, jchars);

    // Open directory handle with wide API
    HANDLE hDir = CreateFileW(
        gState.dirPathW,
        FILE_LIST_DIRECTORY,
        FILE_SHARE_READ | FILE_SHARE_WRITE | FILE_SHARE_DELETE,
        NULL,
        OPEN_EXISTING,
        FILE_FLAG_BACKUP_SEMANTICS, // directory handle
        NULL
    );

    if (hDir == INVALID_HANDLE_VALUE) {
        DWORD err = GetLastError();
        fprintf(stderr, "[C] Error: Cannot open directory (W) '%ws' (Error: %lu)\n", gState.dirPathW, err);
        return;
    }

    gState.monitorPathId   = monitorPathId;
    gState.dirHandle       = hDir;
    gState.running         = 1;

    // Promote callback to global ref
    gState.callbackGlobal = (*env)->NewGlobalRef(env, jCallback);
    if (!gState.callbackGlobal) {
        fprintf(stderr, "[C] Error: NewGlobalRef(callback) failed\n");
        CloseHandle(hDir);
        gState.dirHandle = NULL;
        return;
    }

    // Resolve and promote callback class to global ref
    jclass cbClsLocal = (*env)->GetObjectClass(env, gState.callbackGlobal);
    if (!cbClsLocal) {
        fprintf(stderr, "[C] Error: GetObjectClass(callback) failed\n");
        (*env)->DeleteGlobalRef(env, gState.callbackGlobal);
        gState.callbackGlobal = NULL;
        CloseHandle(hDir);
        gState.dirHandle = NULL;
        return;
    }
    gState.callbackClass = (jclass)(*env)->NewGlobalRef(env, cbClsLocal);
    (*env)->DeleteLocalRef(env, cbClsLocal);
    if (!gState.callbackClass) {
        fprintf(stderr, "[C] Error: NewGlobalRef(callbackClass) failed\n");
        (*env)->DeleteGlobalRef(env, gState.callbackGlobal);
        gState.callbackGlobal = NULL;
        CloseHandle(hDir);
        gState.dirHandle = NULL;
        return;
    }

    // Resolve method ID and validate signature
    gState.onEventMethod = (*env)->GetMethodID(
        env,
        gState.callbackClass,
        "onNativeFileEvent",
        "(ILjava/lang/String;Ljava/lang/String;Ljava/lang/String;JLjava/lang/String;)V"
    );
    if (!gState.onEventMethod) {
        fprintf(stderr, "[C] Error: Cannot find method onNativeFileEvent with required signature\n");
        (*env)->DeleteGlobalRef(env, gState.callbackClass);
        (*env)->DeleteGlobalRef(env, gState.callbackGlobal);
        gState.callbackClass = NULL;
        gState.callbackGlobal = NULL;
        CloseHandle(hDir);
        gState.dirHandle = NULL;
        return;
    }

    printf("[C] ✓ Monitoring started for: %ws (id=%d)\n", gState.dirPathW, gState.monitorPathId);

    // Monitoring loop (blocking in calling thread)
    BYTE buffer[BUFFER_SIZE];
    DWORD bytesReturned = 0;

    while (gState.running &&
           ReadDirectoryChangesW(
               gState.dirHandle,
               buffer,
               sizeof(buffer),
               TRUE, // watch subdirs
               FILE_NOTIFY_CHANGE_FILE_NAME |
               FILE_NOTIFY_CHANGE_LAST_WRITE |
               FILE_NOTIFY_CHANGE_SIZE |
               FILE_NOTIFY_CHANGE_ATTRIBUTES,
               &bytesReturned,
               NULL,
               NULL
           )) {

        if (!gState.running) break;
        if (bytesReturned == 0) continue;

        FILE_NOTIFY_INFORMATION* pNotify = (FILE_NOTIFY_INFORMATION*)buffer;

        for (;;) {
            // Extract the filename (UTF-16)
            size_t wcharLen = pNotify->FileNameLength / sizeof(wchar_t);
            wchar_t wFilename[MAX_PATH_LEN];
            size_t copyLen = (wcharLen < (MAX_PATH_LEN - 1)) ? wcharLen : (MAX_PATH_LEN - 1);
            wcsncpy(wFilename, pNotify->FileName, copyLen);
            wFilename[copyLen] = L'\0';

            // Determine action
            const char* actionUtf8 = "UNKNOWN";
            switch (pNotify->Action) {
                case FILE_ACTION_ADDED:            actionUtf8 = "CREATE"; break;
                case FILE_ACTION_MODIFIED:         actionUtf8 = "MODIFY"; break;
                case FILE_ACTION_REMOVED:          actionUtf8 = "DELETE"; break;
                case FILE_ACTION_RENAMED_OLD_NAME: actionUtf8 = "RENAME"; break;
                case FILE_ACTION_RENAMED_NEW_NAME: // skip; we only emit one event
                    if (pNotify->NextEntryOffset == 0) goto done_entries;
                    pNotify = (FILE_NOTIFY_INFORMATION*)((BYTE*)pNotify + pNotify->NextEntryOffset);
                    continue;
            }

            // Build full path (UTF-16)
            wchar_t fullPathW[MAX_PATH_LEN];
            join_path_w(fullPathW, MAX_PATH_LEN, gState.dirPathW, wFilename);

            // File size via wide API
            WIN32_FILE_ATTRIBUTE_DATA fad;
            long fileSize = 0;
            if (GetFileAttributesExW(fullPathW, GetFileExInfoStandard, &fad)) {
                fileSize = (long)fad.nFileSizeLow; // low DWORD; for large files extend accordingly
            }

            // Convert UTF-16 to UTF-8 for Java strings
            char filenameUtf8[MAX_PATH_LEN];
            char fullPathUtf8[MAX_PATH_LEN];
            int fnConv = WideCharToMultiByte(CP_UTF8, 0, wFilename, -1, filenameUtf8, MAX_PATH_LEN, NULL, NULL);
            int fpConv = WideCharToMultiByte(CP_UTF8, 0, fullPathW, -1, fullPathUtf8, MAX_PATH_LEN, NULL, NULL);

            if (fnConv == 0 || fpConv == 0) {
                fprintf(stderr, "[C] Error: UTF-16→UTF-8 conversion failed (fn=%d, fp=%d)\n", fnConv, fpConv);
            } else {
                // Build timestamp
                char ts[64];
                build_iso_timestamp(ts, sizeof(ts));

                // Attach this thread to JVM, call back
                if (gJvm == NULL) {
                    fprintf(stderr, "[C] Error: JVM pointer not cached\n");
                } else {
                    JNIEnv* env2 = NULL;
                    if ((*gJvm)->AttachCurrentThread(gJvm, (void**)&env2, NULL) == 0 && env2 != NULL) {
                        jstring jFullPath = (*env2)->NewStringUTF(env2, fullPathUtf8);
                        jstring jFileName = (*env2)->NewStringUTF(env2, filenameUtf8);
                        jstring jAction   = (*env2)->NewStringUTF(env2, actionUtf8);
                        jstring jTs       = (*env2)->NewStringUTF(env2, ts);

                        if (jFullPath && jFileName && jAction && jTs) {
                            (*env2)->CallVoidMethod(env2,
                                                    gState.callbackGlobal,
                                                    gState.onEventMethod,
                                                    (jint)gState.monitorPathId,
                                                    jFullPath,
                                                    jFileName,
                                                    jAction,
                                                    (jlong)fileSize,
                                                    jTs);

                            if ((*env2)->ExceptionCheck(env2)) {
                                fprintf(stderr, "[C] Warning: Exception in Java callback\n");
                                (*env2)->ExceptionClear(env2);
                            }
                        } else {
                            fprintf(stderr, "[C] Error: Failed to allocate Java strings for callback\n");
                        }

                        if (jFullPath) (*env2)->DeleteLocalRef(env2, jFullPath);
                        if (jFileName) (*env2)->DeleteLocalRef(env2, jFileName);
                        if (jAction)   (*env2)->DeleteLocalRef(env2, jAction);
                        if (jTs)       (*env2)->DeleteLocalRef(env2, jTs);

                        (*gJvm)->DetachCurrentThread(gJvm);
                    } else {
                        fprintf(stderr, "[C] Error: AttachCurrentThread failed\n");
                    }
                }
            }

            // Advance
            if (pNotify->NextEntryOffset == 0) break;
            pNotify = (FILE_NOTIFY_INFORMATION*)((BYTE*)pNotify + pNotify->NextEntryOffset);
        }
    done_entries:;
        // Continue outer loop
    }

    // Cleanup
    printf("[C] Monitoring stopping for id=%d, cleaning up...\n", gState.monitorPathId);

    if (gState.dirHandle && gState.dirHandle != INVALID_HANDLE_VALUE) {
        CancelIo(gState.dirHandle);
        CloseHandle(gState.dirHandle);
        gState.dirHandle = NULL;
    }

    if (gJvm != NULL) {
        JNIEnv* env3 = NULL;
        if ((*gJvm)->AttachCurrentThread(gJvm, (void**)&env3, NULL) == 0 && env3 != NULL) {
            if (gState.callbackClass) {
                (*env3)->DeleteGlobalRef(env3, gState.callbackClass);
                gState.callbackClass = NULL;
            }
            if (gState.callbackGlobal) {
                (*env3)->DeleteGlobalRef(env3, gState.callbackGlobal);
                gState.callbackGlobal = NULL;
            }
            (*gJvm)->DetachCurrentThread(gJvm);
        }
    }

    gState.running = 0;
    printf("[C] Monitoring thread completed for id=%d\n", gState.monitorPathId);
}

/**
 * JNI Implementation: Stop monitoring by id
 * public native void stopMonitoring(int monitorPathId);
 */
JNIEXPORT void JNICALL Java_com_neurasys_bridge_NativeFileMonitor_stopMonitoring
  (JNIEnv* env, jobject self, jint monitorPathId)
{
    if (gState.monitorPathId != monitorPathId) {
        // If you later support multiple monitors, locate the right state here
        fprintf(stderr, "[C] stopMonitoring: id mismatch (requested=%d, active=%d)\n",
                (int)monitorPathId, (int)gState.monitorPathId);
    }

    gState.running = 0;

    if (gState.dirHandle && gState.dirHandle != INVALID_HANDLE_VALUE) {
        CancelIo(gState.dirHandle);
        CloseHandle(gState.dirHandle);
        gState.dirHandle = NULL;
    }

    if (gJvm != NULL) {
        JNIEnv* env2 = NULL;
        if ((*gJvm)->AttachCurrentThread(gJvm, (void**)&env2, NULL) == 0 && env2 != NULL) {
            if (gState.callbackClass) {
                (*env2)->DeleteGlobalRef(env2, gState.callbackClass);
                gState.callbackClass = NULL;
            }
            if (gState.callbackGlobal) {
                (*env2)->DeleteGlobalRef(env2, gState.callbackGlobal);
                gState.callbackGlobal = NULL;
            }
            (*gJvm)->DetachCurrentThread(gJvm);
        }
    }

    printf("[C] ✓ stopMonitoring completed for id=%d\n", (int)monitorPathId);
}

/**
 * JNI Implementation: Get monitor stats
 * public native String getNativeMonitorStats();
 */
JNIEXPORT jstring JNICALL Java_com_neurasys_bridge_NativeFileMonitor_getNativeMonitorStats
  (JNIEnv* env, jobject self)
{
    char buffer[256];
    snprintf(buffer, sizeof(buffer),
             "running=%d, monitorId=%d",
             (int)gState.running,
             (int)gState.monitorPathId);

    return (*env)->NewStringUTF(env, buffer);
}

/**
 * Optional: Monitoring active status
 * public native boolean isMonitoringActive();
 */
JNIEXPORT jboolean JNICALL Java_com_neurasys_bridge_NativeFileMonitor_isMonitoringActive
  (JNIEnv* env, jobject self)
{
    return (gState.running && gState.dirHandle && gState.dirHandle != INVALID_HANDLE_VALUE) ? JNI_TRUE : JNI_FALSE;
}
