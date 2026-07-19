#define _GNU_SOURCE 1
#include <jni.h>
#include <android/log.h>
#include <errno.h>
#include <fcntl.h>
#include <pthread.h>
#include <setjmp.h>
#include <stdarg.h>
#include <stdatomic.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <unistd.h>

#include "common.h"

#define LOG_TAG "SpdFlashNative"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define CANCEL_SLICE_MS 500U

extern int spd_dump_main(int argc, char **argv);
extern spdio_t *spd_upstream_spdio_init(int flags);
extern void spd_upstream_spdio_free(spdio_t *io);
extern int bListenLibusb;
extern int gpt_failed;
extern int m_bOpened;
extern int fdl1_loaded;
extern int fdl2_executed;
extern int selected_ab;
extern uint64_t fblk_size;
extern libusb_device *curPort;
extern char fn_partlist[40];
extern char savepath[ARGV_LEN];
extern DA_INFO_T Da_Info;
extern partition_t gPartInfo;

static JavaVM *g_vm = NULL;
static pthread_mutex_t g_run_mutex = PTHREAD_MUTEX_INITIALIZER;
static pthread_mutex_t g_state_mutex = PTHREAD_MUTEX_INITIALIZER;
static atomic_int g_cancel_requested = ATOMIC_VAR_INIT(0);
static atomic_uint_fast64_t g_transferred_bytes = ATOMIC_VAR_INIT(0);
static atomic_uint_fast64_t g_expected_bytes = ATOMIC_VAR_INIT(0);
static _Atomic(spdio_t *) g_active_io = NULL;

static jobject g_callback = NULL;
static jmethodID g_log_method = NULL;
static jmethodID g_progress_method = NULL;
static jmethodID g_open_output_method = NULL;
static jmethodID g_open_input_method = NULL;
static int g_direct_saf_output = 0;
static char g_workdir[4096] = {0};
static char g_command[128] = {0};
static struct timespec g_progress_started = {0};
static uint64_t g_last_progress_emit_ms = 0;

static _Thread_local jmp_buf g_exit_jump;
static _Thread_local int g_exit_jump_ready = 0;
static _Thread_local int g_exit_code = 0;
static _Thread_local int g_confirm_dangerous = 0;

typedef struct {
    int read_fd;
    jobject callback;
    jmethodID method;
} reader_context_t;

static uint64_t monotonic_ms(void) {
    struct timespec now;
    clock_gettime(CLOCK_MONOTONIC, &now);
    return (uint64_t)now.tv_sec * 1000ULL + (uint64_t)now.tv_nsec / 1000000ULL;
}

static JNIEnv *get_env(int *attached) {
    JNIEnv *env = NULL;
    *attached = 0;
    if ((*g_vm)->GetEnv(g_vm, (void **)&env, JNI_VERSION_1_6) == JNI_OK) return env;
    if ((*g_vm)->AttachCurrentThread(g_vm, &env, NULL) != JNI_OK) return NULL;
    *attached = 1;
    return env;
}

static jstring new_safe_string(JNIEnv *env, const char *data, size_t length) {
    char *safe = (char *)malloc(length + 1);
    if (safe == NULL) return NULL;
    for (size_t i = 0; i < length; ++i) {
        unsigned char c = (unsigned char)data[i];
        safe[i] = (c == 0 || c >= 0x80) ? '?' : (char)c;
    }
    safe[length] = '\0';
    jstring result = (*env)->NewStringUTF(env, safe);
    free(safe);
    return result;
}

static void clear_java_exception(JNIEnv *env) {
    if ((*env)->ExceptionCheck(env)) {
        (*env)->ExceptionDescribe(env);
        (*env)->ExceptionClear(env);
    }
}

static void callback_line(JNIEnv *env, jobject callback, jmethodID method, const char *data, size_t length) {
    if (length == 0 || env == NULL || callback == NULL || method == NULL) return;
    jstring line = new_safe_string(env, data, length);
    if (line == NULL) {
        clear_java_exception(env);
        return;
    }
    (*env)->CallVoidMethod(env, callback, method, line);
    (*env)->DeleteLocalRef(env, line);
    clear_java_exception(env);
}

static void emit_progress(int force) {
    if (g_callback == NULL || g_progress_method == NULL) return;
    uint64_t now_ms = monotonic_ms();
    if (!force && now_ms - g_last_progress_emit_ms < 120) return;
    g_last_progress_emit_ms = now_ms;

    uint64_t current = atomic_load(&g_transferred_bytes);
    uint64_t total = atomic_load(&g_expected_bytes);
    uint64_t start_ms = (uint64_t)g_progress_started.tv_sec * 1000ULL + (uint64_t)g_progress_started.tv_nsec / 1000000ULL;
    uint64_t elapsed_ms = now_ms > start_ms ? now_ms - start_ms : 1;
    uint64_t speed = current * 1000ULL / elapsed_ms;

    int attached = 0;
    JNIEnv *env = get_env(&attached);
    if (env == NULL) return;
    pthread_mutex_lock(&g_state_mutex);
    char label[sizeof(g_command)];
    memcpy(label, g_command, sizeof(label));
    pthread_mutex_unlock(&g_state_mutex);
    jstring jlabel = new_safe_string(env, label, strnlen(label, sizeof(label)));
    if (jlabel != NULL) {
        (*env)->CallVoidMethod(env, g_callback, g_progress_method,
            (jlong)current, (jlong)total, (jlong)speed, jlabel);
        (*env)->DeleteLocalRef(env, jlabel);
        clear_java_exception(env);
    }
    if (attached) (*g_vm)->DetachCurrentThread(g_vm);
}

void spd_android_set_command(const char *command) {
    pthread_mutex_lock(&g_state_mutex);
    snprintf(g_command, sizeof(g_command), "%s", command == NULL ? "" : command);
    pthread_mutex_unlock(&g_state_mutex);
    emit_progress(1);
}

static void reset_upstream_globals(void) {
    bListenLibusb = -1;
    gpt_failed = 1;
    m_bOpened = 0;
    fdl1_loaded = 0;
    fdl2_executed = 0;
    selected_ab = -1;
    fblk_size = 0;
    curPort = NULL;
    memset(fn_partlist, 0, sizeof(fn_partlist));
    memset(savepath, 0, sizeof(savepath));
    memset(&Da_Info, 0, sizeof(Da_Info));
    memset(&gPartInfo, 0, sizeof(gPartInfo));
}

spdio_t *spd_android_spdio_init(int flags) {
    spdio_t *io = spd_upstream_spdio_init(flags);
    atomic_store(&g_active_io, io);
    return io;
}

void spd_android_spdio_free(spdio_t *io) {
    spdio_t *expected = io;
    atomic_compare_exchange_strong(&g_active_io, &expected, NULL);
    spd_upstream_spdio_free(io);
}

static void force_cleanup(void) {
    spdio_t *io = atomic_exchange(&g_active_io, NULL);
    if (io == NULL) return;
    if (io->dev_handle != NULL) {
        libusb_close(io->dev_handle);
        io->dev_handle = NULL;
    }
    libusb_exit(NULL);
    free(io->ptable);
    io->ptable = NULL;
    free(io);
}

__attribute__((noreturn)) void spd_android_exit(int code) {
    if (g_exit_jump_ready) {
        g_exit_code = code;
        longjmp(g_exit_jump, 1);
    }
    _exit(code);
}

int spd_android_scanf(const char *format, ...) {
    va_list args;
    va_start(args, format);
    int result = 0;
    if (strstr(format, "%c") != NULL) {
        char *value = va_arg(args, char *);
        if (value != NULL) {
            *value = g_confirm_dangerous ? 'y' : 'n';
            result = 1;
        }
    } else {
        result = EOF;
    }
    va_end(args);
    return result;
}

int spd_android_getchar(void) {
    return '\n';
}

int spd_android_bulk_transfer(
    libusb_device_handle *dev_handle,
    unsigned char endpoint,
    unsigned char *data,
    int length,
    int *transferred,
    unsigned int timeout) {
    unsigned int spent = 0;
    if (transferred != NULL) *transferred = 0;
    while (!atomic_load(&g_cancel_requested)) {
        unsigned int slice = CANCEL_SLICE_MS;
        if (timeout != 0 && timeout - spent < slice) slice = timeout - spent;
        if (slice == 0) slice = 1;
        int local_transferred = 0;
        int result = libusb_bulk_transfer(dev_handle, endpoint, data, length, &local_transferred, slice);
        if (transferred != NULL) *transferred = local_transferred;
        if (local_transferred > 0) {
            atomic_fetch_add(&g_transferred_bytes, (uint64_t)local_transferred);
            emit_progress(0);
            if (result == LIBUSB_ERROR_TIMEOUT) result = LIBUSB_SUCCESS;
        }
        if (result != LIBUSB_ERROR_TIMEOUT) return result;
        if (timeout != 0) {
            spent += slice;
            if (spent >= timeout) return LIBUSB_ERROR_TIMEOUT;
        }
    }
    return LIBUSB_ERROR_INTERRUPTED;
}

static const char *relative_output_path(const char *path) {
    if (path == NULL) return "output.bin";
    size_t workdir_length = strlen(g_workdir);
    if (workdir_length > 0 && strncmp(path, g_workdir, workdir_length) == 0) {
        const char *relative = path + workdir_length;
        while (*relative == '/') relative++;
        return *relative ? relative : "output.bin";
    }
    if (path[0] != '/') return path;
    const char *slash = strrchr(path, '/');
    return slash != NULL && slash[1] != '\0' ? slash + 1 : "output.bin";
}

FILE *spd_android_fopen(const char *path, const char *mode) {
    int write_mode = mode != NULL && (mode[0] == 'w' || mode[0] == 'a');
    int content_input = !write_mode && path != NULL && strncmp(path, "content://", 10) == 0;
    if (!content_input && (!write_mode || !g_direct_saf_output || g_callback == NULL || g_open_output_method == NULL)) {
        return fopen(path, mode);
    }
    if (g_callback == NULL || (content_input ? g_open_input_method == NULL : g_open_output_method == NULL)) {
        errno = EACCES;
        return NULL;
    }

    int attached = 0;
    JNIEnv *env = get_env(&attached);
    if (env == NULL) {
        errno = EIO;
        return NULL;
    }
    const char *callback_path = content_input ? path : relative_output_path(path);
    jstring jpath = new_safe_string(env, callback_path, strlen(callback_path));
    int fd = -1;
    if (jpath != NULL) {
        if (content_input) {
            fd = (*env)->CallIntMethod(env, g_callback, g_open_input_method, jpath);
        } else {
            fd = (*env)->CallIntMethod(env, g_callback, g_open_output_method, jpath, mode[0] == 'a' ? JNI_TRUE : JNI_FALSE);
        }
        (*env)->DeleteLocalRef(env, jpath);
        clear_java_exception(env);
    }
    if (attached) (*g_vm)->DetachCurrentThread(g_vm);
    if (fd < 0) {
        errno = EACCES;
        return NULL;
    }
    FILE *stream = fdopen(fd, mode);
    if (stream == NULL) close(fd);
    return stream;
}

static void *reader_thread(void *opaque) {
    reader_context_t *ctx = (reader_context_t *)opaque;
    int attached = 0;
    JNIEnv *env = get_env(&attached);
    if (env == NULL) {
        close(ctx->read_fd);
        return NULL;
    }

    char read_buffer[4096];
    char line_buffer[16384];
    size_t line_length = 0;
    ssize_t count;
    while ((count = read(ctx->read_fd, read_buffer, sizeof(read_buffer))) > 0) {
        for (ssize_t i = 0; i < count; ++i) {
            char c = read_buffer[i];
            if (c == '\n') {
                while (line_length > 0 && line_buffer[line_length - 1] == '\r') --line_length;
                callback_line(env, ctx->callback, ctx->method, line_buffer, line_length);
                line_length = 0;
            } else if (line_length < sizeof(line_buffer) - 1) {
                line_buffer[line_length++] = c;
            } else {
                callback_line(env, ctx->callback, ctx->method, line_buffer, line_length);
                line_length = 0;
                line_buffer[line_length++] = c;
            }
        }
    }
    if (line_length > 0) callback_line(env, ctx->callback, ctx->method, line_buffer, line_length);
    close(ctx->read_fd);
    if (attached) (*g_vm)->DetachCurrentThread(g_vm);
    return NULL;
}

static char *copy_jstring(JNIEnv *env, jstring value) {
    if (value == NULL) return strdup("");
    const char *utf = (*env)->GetStringUTFChars(env, value, NULL);
    if (utf == NULL) return NULL;
    char *copy = strdup(utf);
    (*env)->ReleaseStringUTFChars(env, value, utf);
    return copy;
}

JNIEXPORT jint JNICALL
Java_io_github_lootdev78_spdflash_NativeBridge_run(
    JNIEnv *env,
    jobject ignored,
    jint usb_file_descriptor,
    jobjectArray arguments,
    jstring working_directory,
    jboolean confirm_dangerous,
    jlong expected_bytes,
    jboolean direct_saf_output,
    jobject callback) {
    (void)ignored;
    if (usb_file_descriptor < 0 || working_directory == NULL || callback == NULL) return -215;
    if (pthread_mutex_trylock(&g_run_mutex) != 0) return -200;

    int result = -1;
    int duplicate_fd = -1;
    int pipe_fds[2] = {-1, -1};
    int saved_stdout = -1;
    int saved_stderr = -1;
    pthread_t reader;
    int reader_started = 0;
    jobject callback_global = NULL;
    char *workdir = NULL;
    char old_cwd[4096] = {0};
    int cwd_saved = getcwd(old_cwd, sizeof(old_cwd)) != NULL;
    char **argv = NULL;
    int argc = 0;

    atomic_store(&g_cancel_requested, 0);
    atomic_store(&g_transferred_bytes, 0);
    atomic_store(&g_expected_bytes, expected_bytes > 0 ? (uint64_t)expected_bytes : 0);
    clock_gettime(CLOCK_MONOTONIC, &g_progress_started);
    g_last_progress_emit_ms = 0;
    memset(g_command, 0, sizeof(g_command));

    duplicate_fd = dup(usb_file_descriptor);
    if (duplicate_fd < 0) { result = -201; goto cleanup; }

    jsize supplied_count = arguments == NULL ? 0 : (*env)->GetArrayLength(env, arguments);
    argc = (int)supplied_count + 3;
    argv = (char **)calloc((size_t)argc + 1, sizeof(char *));
    if (argv == NULL) { result = -202; goto cleanup; }
    argv[0] = strdup("spd_dump");
    argv[1] = strdup("--usb-fd");
    for (jsize i = 0; i < supplied_count; ++i) {
        jstring item = (jstring)(*env)->GetObjectArrayElement(env, arguments, i);
        argv[i + 2] = copy_jstring(env, item);
        if (item != NULL) (*env)->DeleteLocalRef(env, item);
        if (argv[i + 2] == NULL) { result = -203; goto cleanup; }
    }
    char fd_text[32];
    snprintf(fd_text, sizeof(fd_text), "%d", duplicate_fd);
    argv[argc - 1] = strdup(fd_text);
    if (argv[0] == NULL || argv[1] == NULL || argv[argc - 1] == NULL) { result = -204; goto cleanup; }

    workdir = copy_jstring(env, working_directory);
    if (workdir == NULL || chdir(workdir) != 0) { result = -205; goto cleanup; }
    snprintf(g_workdir, sizeof(g_workdir), "%s", workdir);
    g_direct_saf_output = direct_saf_output == JNI_TRUE;

    jclass callback_class = (*env)->GetObjectClass(env, callback);
    if (callback_class == NULL) { result = -206; goto cleanup; }
    g_log_method = (*env)->GetMethodID(env, callback_class, "onNativeLog", "(Ljava/lang/String;)V");
    g_progress_method = (*env)->GetMethodID(env, callback_class, "onNativeProgress", "(JJJLjava/lang/String;)V");
    g_open_output_method = (*env)->GetMethodID(env, callback_class, "onNativeOpenOutput", "(Ljava/lang/String;Z)I");
    g_open_input_method = (*env)->GetMethodID(env, callback_class, "onNativeOpenInput", "(Ljava/lang/String;)I");
    (*env)->DeleteLocalRef(env, callback_class);
    if (g_log_method == NULL || g_progress_method == NULL || g_open_output_method == NULL || g_open_input_method == NULL) {
        clear_java_exception(env); result = -207; goto cleanup;
    }
    callback_global = (*env)->NewGlobalRef(env, callback);
    if (callback_global == NULL) { result = -208; goto cleanup; }
    g_callback = callback_global;

    if (pipe(pipe_fds) != 0) { result = -209; goto cleanup; }
    fcntl(pipe_fds[0], F_SETFD, FD_CLOEXEC);
    fcntl(pipe_fds[1], F_SETFD, FD_CLOEXEC);
    saved_stdout = dup(STDOUT_FILENO);
    saved_stderr = dup(STDERR_FILENO);
    if (saved_stdout < 0 || saved_stderr < 0) { result = -210; goto cleanup; }

    reader_context_t reader_context = {.read_fd = pipe_fds[0], .callback = callback_global, .method = g_log_method};
    if (pthread_create(&reader, NULL, reader_thread, &reader_context) != 0) { result = -211; goto cleanup; }
    reader_started = 1;
    pipe_fds[0] = -1;

    fflush(NULL);
    if (dup2(pipe_fds[1], STDOUT_FILENO) < 0 || dup2(pipe_fds[1], STDERR_FILENO) < 0) { result = -212; goto cleanup; }
    close(pipe_fds[1]);
    pipe_fds[1] = -1;
    setvbuf(stdout, NULL, _IONBF, 0);
    setvbuf(stderr, NULL, _IONBF, 0);

    reset_upstream_globals();
    g_confirm_dangerous = confirm_dangerous == JNI_TRUE;
    g_exit_code = 0;
    g_exit_jump_ready = 1;
    if (setjmp(g_exit_jump) == 0) {
        result = spd_dump_main(argc, argv);
    } else {
        result = atomic_load(&g_cancel_requested) ? -214 : (g_exit_code == 0 ? -213 : -abs(g_exit_code));
        force_cleanup();
    }
    g_exit_jump_ready = 0;
    emit_progress(1);

cleanup:
    fflush(NULL);
    if (saved_stdout >= 0) { dup2(saved_stdout, STDOUT_FILENO); close(saved_stdout); }
    if (saved_stderr >= 0) { dup2(saved_stderr, STDERR_FILENO); close(saved_stderr); }
    if (pipe_fds[1] >= 0) close(pipe_fds[1]);
    if (pipe_fds[0] >= 0) close(pipe_fds[0]);
    if (reader_started) pthread_join(reader, NULL);

    g_callback = NULL;
    g_log_method = NULL;
    g_progress_method = NULL;
    g_open_output_method = NULL;
    g_open_input_method = NULL;
    g_direct_saf_output = 0;
    memset(g_workdir, 0, sizeof(g_workdir));
    if (callback_global != NULL) (*env)->DeleteGlobalRef(env, callback_global);
    if (duplicate_fd >= 0) close(duplicate_fd); /* libusb_wrap_sys_device does not own this fd. */
    if (cwd_saved) chdir(old_cwd);
    free(workdir);
    if (argv != NULL) {
        for (int i = 0; i < argc; ++i) free(argv[i]);
        free(argv);
    }
    atomic_store(&g_cancel_requested, 0);
    pthread_mutex_unlock(&g_run_mutex);
    return result;
}

JNIEXPORT void JNICALL
Java_io_github_lootdev78_spdflash_NativeBridge_cancel(JNIEnv *env, jobject ignored) {
    (void)env; (void)ignored;
    atomic_store(&g_cancel_requested, 1);
}

JNIEXPORT jstring JNICALL
Java_io_github_lootdev78_spdflash_NativeBridge_version(JNIEnv *env, jobject ignored) {
    (void)ignored;
    return (*env)->NewStringUTF(env, "spreadtrum_flash d24c21a + libusb 1.0.29 + Android bridge v2");
}

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved) {
    (void)reserved;
    g_vm = vm;
    return JNI_VERSION_1_6;
}
