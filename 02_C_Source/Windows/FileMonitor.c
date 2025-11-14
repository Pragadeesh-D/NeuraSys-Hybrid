/*
 * FileMonitor.c - Native file monitoring implementation for Windows
 * Uses ReadDirectoryChangesW API for real-time file system event detection
 * Calls back to Java via JNI for event notification
 */

#include "FileMonitor.h"
#include <windows.h>
#include <process.h>   // _beginthreadex, _endthreadex
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

#define BUFFER_SIZE   32768
#define MAX_PATH_LEN  1024

// Cache JavaVM for thread attachment
static JavaVM* gJvm = NULL;

// Simple state for a single monitor (extend to map by id if needed)
typedef struct MonitorState {
    jint           monitorPathId;
    HANDLE         dirHandle;
    jobject        callbackGlobal;   // Global ref to Java callback
    jmethodID      onEventMethod;    // MethodID for onNativeFileEvent
    wchar_t        dirPathW[MAX_PATH_LEN];
    volatile LONG  running;
} MonitorState;

static MonitorState gState = {0};

// Helper: build ISO timestamp
static void build_iso_timestamp(char* out, size_t cap) {
    SYSTEMTIME st;
    GetLocalTime(&st);
    _snprintf_s(out, cap, cap - 1, "%04d-%02d-%02dT%02d:%02d:%02d.%03d",
                st.wYear, st.wMonth, st.wDay, st.wHour, st.wMinute, st.wSecond, st.wMilliseconds);
    out[cap - 1] = '\0';
}

// Helper: wide path join (dir + L'\\' + file)
static void join_path_w(wchar_t* out, size_t outCap, const wchar_t* dir, const wchar_t* file) {
    size_t dirLen = wcslen(dir);
    size_t fileLen = wcslen(file);
    if (dirLen + 1 + fileLen + 1 > outCap) {
        // Truncate safely
        wcsncpy_s(out, outCap, dir, _TRUNCATE);
        wcscat_s(out, outCap, L"\\");
        wcsncat_s(out, outCap, file, outCap - wcslen(out) - 1);
        return;
    }
    wcscpy_s(out, outCap, dir);
    wcscat_s(out, outCap, L"\\");
    wcscat_s(out, outCap, file);
}

// JNI OnLoad: cache JVM pointer
JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* reserved) {
    gJvm = vm;
    return JNI_VERSION_1_8; // FIX: Use 1.8 for modern builds
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
static unsigned __stdcall monitorThreadProc(void* arg)
{
    JNIEnv* env2 = NULL;
    if (!gJvm || (*gJvm)->AttachCurrentThread(gJvm, (void**)&env2, NULL) != 0 || !env2) {
        fprintf(stderr, "[C] ✗ Failed to attach native thread to JVM\n");
        goto THREAD_OUT;
    }

    HANDLE hDir = CreateFileW(
        gState.dirPathW,
        FILE_LIST_DIRECTORY,
        FILE_SHARE_READ | FILE_SHARE_WRITE | FILE_SHARE_DELETE,
        NULL,
        OPEN_EXISTING,
        FILE_FLAG_BACKUP_SEMANTICS,
        NULL
    );

    if (hDir == INVALID_HANDLE_VALUE) {
        fprintf(stderr, "[C] ✗ Failed to open directory\n");
        goto THREAD_OUT;
    }

    BYTE buffer[4096];
    DWORD bytesReturned;

    while (ReadDirectoryChangesW(
        hDir,
        &buffer,
        sizeof(buffer),
        TRUE,
        FILE_NOTIFY_CHANGE_FILE_NAME | FILE_NOTIFY_CHANGE_DIR_NAME | FILE_NOTIFY_CHANGE_SIZE,
        &bytesReturned,
        NULL,
        NULL
    )) {
        FILE_NOTIFY_INFORMATION* info = (FILE_NOTIFY_INFORMATION*)buffer;

        do {
            int wideLen = info->FileNameLength / sizeof(WCHAR);
            WCHAR* wideName = info->FileName;

            char utf8FileName[512];
            int utf8Len = WideCharToMultiByte(CP_UTF8, 0, wideName, wideLen, utf8FileName, sizeof(utf8FileName) - 1, NULL, NULL);
            utf8FileName[utf8Len] = '\0';

            char utf8DirPath[512];
            int dirLen = WideCharToMultiByte(CP_UTF8, 0, gState.dirPathW, -1, utf8DirPath, sizeof(utf8DirPath) - 1, NULL, NULL);
            utf8DirPath[dirLen] = '\0';

            char fullPath[1024];
            snprintf(fullPath, sizeof(fullPath), "%s\\%s", utf8DirPath, utf8FileName);

            const char* action;
            switch (info->Action) {
                case FILE_ACTION_ADDED:
                    action = "CREATE"; break;
                case FILE_ACTION_REMOVED:
                    action = "DELETE"; break;
                case FILE_ACTION_MODIFIED:
                    action = "MODIFY"; break;
                case FILE_ACTION_RENAMED_OLD_NAME:
                    action = "RENAME_OLD"; break;
                case FILE_ACTION_RENAMED_NEW_NAME:
                    action = "RENAME_NEW"; break;
                default:
                    action = "UNKNOWN"; break;
            }


            jstring jFullPath = (*env2)->NewStringUTF(env2, fullPath);
            jstring jFileName = (*env2)->NewStringUTF(env2, utf8FileName);
            jstring jAction   = (*env2)->NewStringUTF(env2, action);
            jstring jTs       = (*env2)->NewStringUTF(env2, "2025-11-14T08:30:00.000");

            printf("[C] → Dispatching real event: %s (%s)\n", utf8FileName, action);

            (*env2)->CallVoidMethod(env2, gState.callbackGlobal, gState.onEventMethod,
                                    (jint)gState.monitorPathId,
                                    jFullPath, jFileName, jAction,
                                    (jlong)1234, jTs);

            if ((*env2)->ExceptionCheck(env2)) {
                (*env2)->ExceptionDescribe(env2);
                (*env2)->ExceptionClear(env2);
                fprintf(stderr, "[C] ✗ Java exception during callback\n");
            }

            (*env2)->DeleteLocalRef(env2, jFullPath);
            (*env2)->DeleteLocalRef(env2, jFileName);
            (*env2)->DeleteLocalRef(env2, jAction);
            (*env2)->DeleteLocalRef(env2, jTs);

            if (info->NextEntryOffset == 0) break;
            info = (FILE_NOTIFY_INFORMATION*)((BYTE*)info + info->NextEntryOffset);
        } while (TRUE);
    }

    CloseHandle(hDir);

THREAD_OUT:
    (*gJvm)->DetachCurrentThread(gJvm);
    _endthreadex(0);
    return 0;
}

JNIEXPORT void JNICALL Java_com_neurasys_bridge_NativeFileMonitor_startMonitoring
  (JNIEnv* env, jobject self, jint monitorPathId, jstring jPath, jobject jCallback)
{
    if (!jPath || !jCallback) {
        fprintf(stderr, "[C] ✗ startMonitoring: null path or callback\n");
        return;
    }

    const jchar* pathChars = (*env)->GetStringChars(env, jPath, NULL);
    jsize pathLen = (*env)->GetStringLength(env, jPath);
    if (pathLen >= MAX_PATH_LEN) pathLen = MAX_PATH_LEN - 1;
    wcsncpy_s(gState.dirPathW, MAX_PATH_LEN, (const wchar_t*)pathChars, pathLen);
    gState.dirPathW[pathLen] = L'\0';
    (*env)->ReleaseStringChars(env, jPath, pathChars);

    gState.monitorPathId = monitorPathId;
    gState.callbackGlobal = (*env)->NewGlobalRef(env, jCallback);

    jclass cbCls = (*env)->GetObjectClass(env, jCallback);
    gState.onEventMethod = (*env)->GetMethodID(env, cbCls,
        "onNativeFileEvent",
        "(ILjava/lang/String;Ljava/lang/String;Ljava/lang/String;JLjava/lang/String;)V");

    if (!gState.onEventMethod) {
        fprintf(stderr, "[C] ✗ startMonitoring: method not found\n");
        return;
    }

    gState.running = 1;

    uintptr_t threadHandle = _beginthreadex(NULL, 0, monitorThreadProc, NULL, 0, NULL);
    if (!threadHandle) {
        fprintf(stderr, "[C] ✗ Failed to launch monitor thread\n");
        gState.running = 0;
        return;
    }

    CloseHandle((HANDLE)threadHandle);
    fprintf(stderr, "[C] ✓ startMonitoring launched thread for pathId=%d\n", monitorPathId);
}

/**
 * JNI Implementation: Stop monitoring by id
 * public native void stopMonitoring(int monitorPathId);
 */
JNIEXPORT void JNICALL Java_com_neurasys_bridge_NativeFileMonitor_stopMonitoring
  (JNIEnv* env, jobject self, jint monitorPathId)
{
    if (gState.monitorPathId != monitorPathId) {
        fprintf(stderr, "[C] stopMonitoring: id mismatch (requested=%d, active=%d)\n",
                (int)monitorPathId, (int)gState.monitorPathId);
        // Continue anyway to stop whatever is running
    }

    // Signal thread to stop
    gState.running = 0;

    // Cancel and close directory handle to unblock ReadDirectoryChangesW
    if (gState.dirHandle && gState.dirHandle != INVALID_HANDLE_VALUE) {
        CancelIo(gState.dirHandle);
        CloseHandle(gState.dirHandle);
        gState.dirHandle = NULL;
    }

    // Clean global refs
    if (gState.callbackGlobal) {
        (*env)->DeleteGlobalRef(env, gState.callbackGlobal);
        gState.callbackGlobal = NULL;
    }

    gState.monitorPathId = 0;
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
    _snprintf_s(buffer, sizeof(buffer), _TRUNCATE,
                "running=%d, monitorId=%d",
                (int)gState.running,
                (int)gState.monitorPathId);

    return (*env)->NewStringUTF(env, buffer);
}

// Add to FileMonitor.c
JNIEXPORT void JNICALL Java_com_neurasys_bridge_NativeFileMonitor_triggerTestCallback
  (JNIEnv* env, jobject self, jint monitorPathId, jstring jPath, jobject jCallback)
{
    if (!jCallback) {
        fprintf(stderr, "[C] triggerTestCallback: null callback\n");
        return;
    }

    jclass cbCls = (*env)->GetObjectClass(env, jCallback);
    if (!cbCls) {
        fprintf(stderr, "[C] triggerTestCallback: GetObjectClass failed\n");
        return;
    }
    jmethodID mid = (*env)->GetMethodID(env, cbCls,
        "onNativeFileEvent",
        "(ILjava/lang/String;Ljava/lang/String;Ljava/lang/String;JLjava/lang/String;)V");
    if (!mid) {
        fprintf(stderr, "[C] triggerTestCallback: method not found\n");
        return;
    }

    const char* fullPathUtf8 = "C:\\Temp\\harness.txt";
    const char* fileNameUtf8 = "harness.txt";
    const char* actionUtf8   = "CREATE";
    const char* tsUtf8       = "2025-11-14T07:20:00.000";

    jstring jFullPath = (*env)->NewStringUTF(env, fullPathUtf8);
    jstring jFileName = (*env)->NewStringUTF(env, fileNameUtf8);
    jstring jAction   = (*env)->NewStringUTF(env, actionUtf8);
    jstring jTs       = (*env)->NewStringUTF(env, tsUtf8);

    printf("[C] ✓ triggerTestCallback dispatching\n");

    (*env)->CallVoidMethod(env, jCallback, mid,
                           (jint)monitorPathId,
                           jFullPath, jFileName, jAction,
                           (jlong)1234, jTs);

    if ((*env)->ExceptionCheck(env)) {
        (*env)->ExceptionDescribe(env);
        (*env)->ExceptionClear(env);
        fprintf(stderr, "[C] triggerTestCallback: exception during callback\n");
    }

    (*env)->DeleteLocalRef(env, jFullPath);
    (*env)->DeleteLocalRef(env, jFileName);
    (*env)->DeleteLocalRef(env, jAction);
    (*env)->DeleteLocalRef(env, jTs);

    printf("[C] ✓ triggerTestCallback completed\n");
}

JNIEXPORT jboolean JNICALL Java_com_neurasys_bridge_NativeFileMonitor_isMonitoringActive
  (JNIEnv* env, jobject self)
{
    return (gState.running && gState.dirHandle && gState.dirHandle != INVALID_HANDLE_VALUE) ? JNI_TRUE : JNI_FALSE;
}
