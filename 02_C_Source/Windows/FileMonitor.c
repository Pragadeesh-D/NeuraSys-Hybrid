/*
 * FileMonitor.c - Native file monitoring implementation for Windows
 * Uses ReadDirectoryChangesW API for real-time file system event detection
 * Calls back to Java via JNI for event notification
 */

#include "FileMonitor.h"
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#define BUFFER_SIZE 32768
#define MAX_PATH_LEN 260

/**
 * JNI Implementation: Start monitoring directory for file changes
 * Receives callbacks from Windows API and notifies Java
 */
JNIEXPORT void JNICALL Java_com_neurasys_service_NativeFileMonitor_startMonitoring
(JNIEnv *env, jobject obj, jstring jpath, jobject jcallback)
{
    const char *dirPath = (*env)->GetStringUTFChars(env, jpath, 0);
    
    if (dirPath == NULL) {
        fprintf(stderr, "[C] Error: Invalid directory path\n");
        return;
    }

    printf("[C Monitor] Starting file monitoring for: %s\n", dirPath);

    /* Open directory handle */
    HANDLE dirHandle = CreateFileA(
        dirPath,
        FILE_LIST_DIRECTORY,
        FILE_SHARE_READ | FILE_SHARE_WRITE | FILE_SHARE_DELETE,
        NULL,
        OPEN_EXISTING,
        FILE_FLAG_BACKUP_SEMANTICS,
        NULL
    );

    if (dirHandle == INVALID_HANDLE_VALUE) {
        fprintf(stderr, "[C] Error: Cannot open directory %s (Error Code: %ld)\n", 
                dirPath, GetLastError());
        (*env)->ReleaseStringUTFChars(env, jpath, dirPath);
        return;
    }

    printf("[C] Successfully opened directory handle\n");

    /* Store Java VM reference for callbacks from this thread */
    JavaVM *jvm;
    (*env)->GetJavaVM(env, &jvm);

    /* Keep global reference to callback object */
    jobject callbackGlobal = (*env)->NewGlobalRef(env, jcallback);

    /* Main monitoring loop */
    char buffer[BUFFER_SIZE];
    DWORD bytesReturned = 0;
    int eventCount = 0;

    while (ReadDirectoryChangesW(
        dirHandle,
        buffer,
        sizeof(buffer),
        TRUE,  /* Watch subdirectories */
        FILE_NOTIFY_CHANGE_FILE_NAME |
        FILE_NOTIFY_CHANGE_LAST_WRITE |
        FILE_NOTIFY_CHANGE_SIZE,
        &bytesReturned,
        NULL,
        NULL
    )) {
        if (bytesReturned == 0) {
            continue;
        }

        /* Process file notifications */
        FILE_NOTIFY_INFORMATION *pNotify = (FILE_NOTIFY_INFORMATION *)buffer;

        while (1) {
            /* Convert Unicode filename to UTF-8 */
            wchar_t wFilename[MAX_PATH_LEN];
            memset(wFilename, 0, sizeof(wFilename));
            memcpy(wFilename, pNotify->FileName, pNotify->FileNameLength);
            
            /* Null-terminate wide string */
            wFilename[pNotify->FileNameLength / sizeof(wchar_t)] = L'\0';

            /* Determine action type */
            const char *action = "UNKNOWN";
            switch (pNotify->Action) {
                case FILE_ACTION_ADDED:
                    action = "CREATE";
                    break;
                case FILE_ACTION_MODIFIED:
                    action = "MODIFY";
                    break;
                case FILE_ACTION_REMOVED:
                    action = "DELETE";
                    break;
                case FILE_ACTION_RENAMED_OLD_NAME:
                    action = "RENAME";
                    break;
                case FILE_ACTION_RENAMED_NEW_NAME:
                    /* Skip new name, we only need old */
                    if (pNotify->NextEntryOffset == 0) break;
                    pNotify = (FILE_NOTIFY_INFORMATION *)((char *)pNotify + pNotify->NextEntryOffset);
                    continue;
            }

            /* Convert wide char filename to UTF-8 */
            char filename[MAX_PATH_LEN];
            memset(filename, 0, sizeof(filename));
            
            int converted = WideCharToMultiByte(
                CP_UTF8, 
                0, 
                wFilename, 
                -1, 
                filename, 
                sizeof(filename), 
                NULL, 
                NULL
            );

            if (converted == 0) {
                fprintf(stderr, "[C] Error: Failed to convert filename\n");
                if (pNotify->NextEntryOffset == 0) break;
                pNotify = (FILE_NOTIFY_INFORMATION *)((char *)pNotify + pNotify->NextEntryOffset);
                continue;
            }

            /* Get file size */
            long fileSize = 0;
            char fullPath[MAX_PATH_LEN];
            snprintf(fullPath, sizeof(fullPath), "%s\\%s", dirPath, filename);
            
            WIN32_FILE_ATTRIBUTE_DATA fileInfo;
            if (GetFileAttributesExA(fullPath, GetFileExInfoStandard, &fileInfo)) {
                fileSize = fileInfo.nFileSizeLow;
            }

            printf("[C] Event #%d: %s -> %s (Size: %ld bytes)\n", 
                   ++eventCount, action, filename, fileSize);

            /* Attach to Java VM and call callback */
            JNIEnv *jniEnv = NULL;
            int attachStatus = (*jvm)->AttachCurrentThread(jvm, (void**)&jniEnv, NULL);
            
            if (attachStatus == 0 && jniEnv != NULL) {
                /* Get callback class and method */
                jclass callbackClass = (*jniEnv)->GetObjectClass(jniEnv, callbackGlobal);
                
                if (callbackClass != NULL) {
                    jmethodID onEventMethod = (*jniEnv)->GetMethodID(
                        jniEnv, 
                        callbackClass, 
                        "onFileEvent", 
                        "(Ljava/lang/String;Ljava/lang/String;J)V"
                    );

                    if (onEventMethod != NULL) {
                        /* Create Java strings and call callback */
                        jstring jFilename = (*jniEnv)->NewStringUTF(jniEnv, filename);
                        jstring jAction = (*jniEnv)->NewStringUTF(jniEnv, action);

                        (*jniEnv)->CallVoidMethod(
                            jniEnv, 
                            callbackGlobal, 
                            onEventMethod, 
                            jFilename, 
                            jAction, 
                            (jlong)fileSize
                        );

                        /* Clean up local references */
                        (*jniEnv)->DeleteLocalRef(jniEnv, jFilename);
                        (*jniEnv)->DeleteLocalRef(jniEnv, jAction);

                        /* Check for exceptions */
                        if ((*jniEnv)->ExceptionCheck(jniEnv)) {
                            printf("[C] Warning: Exception occurred in Java callback\n");
                            (*jniEnv)->ExceptionClear(jniEnv);
                        }
                    } else {
                        fprintf(stderr, "[C] Error: Cannot find onFileEvent method\n");
                    }

                    (*jniEnv)->DeleteLocalRef(jniEnv, callbackClass);
                } else {
                    fprintf(stderr, "[C] Error: Cannot get callback class\n");
                }

                (*jvm)->DetachCurrentThread(jvm);
            } else {
                fprintf(stderr, "[C] Error: Cannot attach to JVM (status: %d)\n", attachStatus);
            }

            /* Move to next notification */
            if (pNotify->NextEntryOffset == 0) break;
            pNotify = (FILE_NOTIFY_INFORMATION *)((char *)pNotify + pNotify->NextEntryOffset);
        }
    }

    /* Cleanup */
    printf("[C] Monitoring stopped, cleaning up...\n");
    CloseHandle(dirHandle);
    (*env)->DeleteGlobalRef(env, callbackGlobal);
    (*env)->ReleaseStringUTFChars(env, jpath, dirPath);

    printf("[C] Monitoring thread completed\n");
}