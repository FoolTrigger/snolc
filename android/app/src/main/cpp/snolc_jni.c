#include <jni.h>
#include <android/log.h>
#include <stdint.h>
#include <unistd.h>
#include <pthread.h>

#define TAG "snolc-jni"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

static JavaVM *g_jvm = NULL;
static jobject g_service_obj = NULL;
static int g_running = 0;

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved) {
    g_jvm = vm;
    LOGI("JNI_OnLoad: snolc JNI bridge initialized");
    return JNI_VERSION_1_6;
}

// Вызов Java метода VpnService.protect(socketFd)
int snolc_android_protect_socket(int socket_fd) {
    if (!g_jvm || !g_service_obj) {
        LOGE("protect_socket: JVM or Service reference is null");
        return 0;
    }

    JNIEnv *env = NULL;
    int get_env_res = (*g_jvm)->GetEnv(g_jvm, (void **)&env, JNI_VERSION_1_6);
    int attached = 0;

    if (get_env_res == JNI_EDETACHED) {
        if ((*g_jvm)->AttachCurrentThread(g_jvm, &env, NULL) != 0) {
            LOGE("protect_socket: Failed to attach thread");
            return 0;
        }
        attached = 1;
    }

    jclass cls = (*env)->GetObjectClass(env, g_service_obj);
    jmethodID method = (*env)->GetMethodID(env, cls, "protectSocketFromVpn", "(I)Z");
    if (!method) {
        LOGE("protect_socket: Method protectSocketFromVpn not found");
        if (attached) (*g_jvm)->DetachCurrentThread(g_jvm);
        return 0;
    }

    jboolean res = (*env)->CallBooleanMethod(env, g_service_obj, method, (jint)socket_fd);
    LOGI("protect_socket: socket %d protected = %d", socket_fd, res);

    if (attached) {
        (*g_jvm)->DetachCurrentThread(g_jvm);
    }

    return res ? 1 : 0;
}

JNIEXPORT jlong JNICALL
Java_org_owenewans_snolc_SnolcVpnService_startEngine(
    JNIEnv *env,
    jobject thiz,
    jint tun_fd,
    jstring config_toml
) {
    LOGI("startEngine: starting with TUN FD = %d", tun_fd);

    if (g_service_obj) {
        (*env)->DeleteGlobalRef(env, g_service_obj);
    }
    g_service_obj = (*env)->NewGlobalRef(env, thiz);
    g_running = 1;

    const char *config_str = (*env)->GetStringUTFChars(env, config_toml, NULL);
    LOGI("startEngine: config loaded successfully");

    // Имитация детекции работы петли событий userspace стека smoltcp
    while (g_running) {
        usleep(50000); // 50 мс такт
    }

    LOGI("startEngine: stopped");
    (*env)->ReleaseStringUTFChars(env, config_toml, config_str);
    close(tun_fd);
    return 1;
}

JNIEXPORT void JNICALL
Java_org_owenewans_snolc_SnolcVpnService_stopEngine(
    JNIEnv *env,
    jobject thiz,
    jlong handle
) {
    LOGI("stopEngine: stopping engine");
    g_running = 0;
}
