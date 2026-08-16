#include <jni.h>
#include <errno.h>
#include <fcntl.h>
#include <signal.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <termios.h>
#include <unistd.h>

static char **copy_string_array(JNIEnv *env, jobjectArray values) {
    jsize count = values == NULL ? 0 : (*env)->GetArrayLength(env, values);
    char **result = calloc((size_t) count + 1U, sizeof(char *));
    if (result == NULL) return NULL;
    for (jsize i = 0; i < count; ++i) {
        jstring value = (jstring) (*env)->GetObjectArrayElement(env, values, i);
        const char *utf = (*env)->GetStringUTFChars(env, value, NULL);
        result[i] = utf == NULL ? NULL : strdup(utf);
        if (utf != NULL) (*env)->ReleaseStringUTFChars(env, value, utf);
        (*env)->DeleteLocalRef(env, value);
        if (result[i] == NULL) {
            for (jsize j = 0; j < i; ++j) free(result[j]);
            free(result);
            return NULL;
        }
    }
    return result;
}

static void free_string_array(char **values) {
    if (values == NULL) return;
    for (size_t i = 0; values[i] != NULL; ++i) free(values[i]);
    free(values);
}

JNIEXPORT jlongArray JNICALL
Java_com_piperostool_PtyProcess_nativeCreate(
        JNIEnv *env, jobject ignored, jobjectArray command_values,
        jobjectArray environment_values, jstring cwd_value, jint rows, jint columns) {
    (void) ignored;
    char **command = copy_string_array(env, command_values);
    char **environment = copy_string_array(env, environment_values);
    const char *cwd_utf = (*env)->GetStringUTFChars(env, cwd_value, NULL);
    char *cwd = cwd_utf == NULL ? NULL : strdup(cwd_utf);
    if (cwd_utf != NULL) (*env)->ReleaseStringUTFChars(env, cwd_value, cwd_utf);
    if (command == NULL || command[0] == NULL || environment == NULL || cwd == NULL) {
        free_string_array(command);
        free_string_array(environment);
        free(cwd);
        errno = ENOMEM;
        return NULL;
    }

    int master = open("/dev/ptmx", O_RDWR | O_NOCTTY | O_CLOEXEC);
    if (master < 0 || grantpt(master) != 0 || unlockpt(master) != 0) {
        if (master >= 0) close(master);
        free_string_array(command);
        free_string_array(environment);
        free(cwd);
        return NULL;
    }
    char slave_name[128];
    if (ptsname_r(master, slave_name, sizeof(slave_name)) != 0) {
        close(master);
        free_string_array(command);
        free_string_array(environment);
        free(cwd);
        return NULL;
    }

    pid_t pid = fork();
    if (pid < 0) {
        close(master);
        free_string_array(command);
        free_string_array(environment);
        free(cwd);
        return NULL;
    }
    if (pid == 0) {
        setsid();
        int slave = open(slave_name, O_RDWR);
        if (slave < 0) _exit(126);
        ioctl(slave, TIOCSCTTY, 0);
        struct winsize size = {
            .ws_row = (unsigned short) rows,
            .ws_col = (unsigned short) columns,
            .ws_xpixel = 0,
            .ws_ypixel = 0
        };
        ioctl(slave, TIOCSWINSZ, &size);
        struct termios attributes;
        if (tcgetattr(slave, &attributes) == 0) {
            attributes.c_lflag &= (tcflag_t) ~(ECHO | ECHONL);
            tcsetattr(slave, TCSANOW, &attributes);
        }
        dup2(slave, STDIN_FILENO);
        dup2(slave, STDOUT_FILENO);
        dup2(slave, STDERR_FILENO);
        if (slave > STDERR_FILENO) close(slave);
        close(master);
        if (chdir(cwd) != 0) _exit(126);
        execve(command[0], command, environment);
        _exit(errno == ENOENT ? 127 : 126);
    }

    free_string_array(command);
    free_string_array(environment);
    free(cwd);
    jlong values[2] = {(jlong) master, (jlong) pid};
    jlongArray result = (*env)->NewLongArray(env, 2);
    if (result != NULL) (*env)->SetLongArrayRegion(env, result, 0, 2, values);
    return result;
}

JNIEXPORT jbyteArray JNICALL
Java_com_piperostool_PtyProcess_nativeRead(JNIEnv *env, jobject ignored, jint fd) {
    (void) ignored;
    char buffer[4096];
    ssize_t count;
    do count = read(fd, buffer, sizeof(buffer)); while (count < 0 && errno == EINTR);
    if (count <= 0) return NULL;
    jbyteArray result = (*env)->NewByteArray(env, (jsize) count);
    if (result != NULL) {
        (*env)->SetByteArrayRegion(env, result, 0, (jsize) count, (const jbyte *) buffer);
    }
    return result;
}

JNIEXPORT jboolean JNICALL
Java_com_piperostool_PtyProcess_nativeWrite(
        JNIEnv *env, jobject ignored, jint fd, jbyteArray bytes) {
    (void) ignored;
    jsize length = (*env)->GetArrayLength(env, bytes);
    jbyte *data = (*env)->GetByteArrayElements(env, bytes, NULL);
    if (data == NULL) return JNI_FALSE;
    ssize_t offset = 0;
    while (offset < length) {
        ssize_t count = write(fd, data + offset, (size_t) (length - offset));
        if (count < 0 && errno == EINTR) continue;
        if (count <= 0) break;
        offset += count;
    }
    (*env)->ReleaseByteArrayElements(env, bytes, data, JNI_ABORT);
    return offset == length ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_piperostool_PtyProcess_nativeIsAlive(JNIEnv *env, jobject ignored, jint pid) {
    (void) env;
    (void) ignored;
    int status = 0;
    pid_t result = waitpid(pid, &status, WNOHANG);
    return result == 0 ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_piperostool_PtyProcess_nativeResize(
        JNIEnv *env, jobject ignored, jint fd, jint rows, jint columns) {
    (void) env;
    (void) ignored;
    struct winsize size = {
        .ws_row = (unsigned short) rows,
        .ws_col = (unsigned short) columns,
        .ws_xpixel = 0,
        .ws_ypixel = 0
    };
    ioctl(fd, TIOCSWINSZ, &size);
}

JNIEXPORT void JNICALL
Java_com_piperostool_PtyProcess_nativeClose(
        JNIEnv *env, jobject ignored, jint fd, jint pid) {
    (void) env;
    (void) ignored;
    if (fd >= 0) close(fd);
    if (pid > 0) {
        kill(-pid, SIGHUP);
        kill(pid, SIGHUP);
        waitpid(pid, NULL, WNOHANG);
    }
}
