/*
 * JNI bridge for fetching config from injector via UDS broadcast.
 *
 * 1) Connects to injector's UDS (SOCK_DGRAM, CONFIG_BROADCAST_SOCKET)
 * 2) Sends "GET" command
 * 3) Receives raw response JSON (object with templates array)
 * 4) Extracts & transforms the templates array to module-compatible format
 * 5) Returns a clean JSON array string that Template.GSON can parse directly
 *
 * Transformations:
 *   - Extract templates array from outer object (bracket counting)
 *   - "hide_paths" → "filter_path" (different lengths handled safely)
 *   - Strip injector-only fields (global_inject, fuse_direct, monitor, inject_enable)
 *
 * Based on log_ctl.c: config_broadcast_connect + send_cmd + recv + handle_get_config
 */

#include <jni.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <poll.h>
#include <stddef.h>
#include <errno.h>
#include <ctype.h>
#include <time.h>
#include <android/log.h>

#define LOG_TAG "nsp_bridge-JNI"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

#define CONFIG_BROADCAST_SOCKET "nsp_config_broadcast"
#define BROADCAST_RECEIVE_MAX_SIZE (256 * 1024)
#define RECV_TIMEOUT_MS 3000

/* ─── Helper: bracket-counting array extraction ─── */

/* Extract the JSON array value after key "templates". Returns malloc'd string.
 * Uses independent counters for [] and {} to avoid nested arrays closing
 * the top-level templates array prematurely.
 *
 * Scans from the start of the JSON with context awareness so that
 * a "templates" substring inside a string value (e.g. a path) is not
 * mistaken for the key.
 */
static char* extract_templates_array(const char *json) {
    const char *key = NULL;
    int in_str = 0, esc = 0, depth = 0;
    /* Scan from start, tracking string state + {} nesting so we only match
     * the top-level "templates" key, never a string value */
    for (const char *p = json; *p; p++) {
        if (esc) { esc = 0; continue; }
        if (*p == '\\' && in_str) { esc = 1; continue; }
        if (*p == '"') {
            /* At opening quote (outside string, top-level key depth=1):
             * check if it's "templates" key — but only if the quote
             * is at a key position (preceded by '{' or ',' not ':'). */
            if (!in_str && depth == 1 && strncmp(p, "\"templates\"", 11) == 0) {
                // Skip whitespace backward to find the real preceding char
                const char *before = p - 1;
                while (before >= json && (*before == ' ' || *before == '\t' || *before == '\n' || *before == '\r'))
                    before--;
                // If preceded by ':' this "templates" is a value string, not a key
                if (before >= json && *before == ':') {
                    in_str = !in_str;
                    continue;
                }
                key = p;
                break;
            }
            in_str = !in_str;
            continue;
        }
        /* Track JSON {} nesting outside strings so we can reject
         * "templates" that appears inside a nested value */
        if (!in_str) {
            if (*p == '{') depth++;
            else if (*p == '}') depth--;
        }
    }
    if (!key) { LOGE("no \"templates\" key"); return NULL; }
    const char *start = strchr(key, '[');
    if (!start) { LOGE("no '[' after templates"); return NULL; }

    /* Reuse in_str/esc from the key-scan phase */
    in_str = 0; esc = 0;
    int arr_depth = 0, obj_depth = 0;
    const char *p = start;
    while (*p) {
        if (esc) { esc = 0; p++; continue; }
        if (*p == '\\' && in_str) { esc = 1; p++; continue; }
        if (*p == '"') { in_str = !in_str; p++; continue; }
        if (!in_str) {
            if (*p == '[') arr_depth++;
            else if (*p == '{') obj_depth++;
            else if (*p == ']') {
                arr_depth--;
                if (arr_depth == 0) {
                    long len = p - start + 1;
                    char *r = malloc(len + 1);
                    if (!r) return NULL;
                    strncpy(r, start, len); r[len] = '\0';
                    return r;
                }
            } else if (*p == '}') obj_depth--;
        }
        p++;
    }
    LOGE("unmatched '['"); return NULL;
}

/* ─── Field name replacement + injector-field stripping ───
 *
 * Uses a bracket-depth-aware field processor. Maintains JSON {} nesting
 * depth and string-state. At depth 2 (inside a template object), when a
 * field key is entering (after '{' or ','), the entire key is looked
 * ahead and compared:
 *   - injector-only fields (global_inject, fuse_direct, monitor,
 *     inject_enable) → skip key + ':' + value + trailing comma
 *   - "hide_paths" → rename to "filter_path" (12→13 bytes, safe realloc)
 *   - everything else → copied verbatim
 *
 * On any error or unexpected input, characters are copied through
 * without transformation (safe degradation).
 */
static char* transform_templates(const char *src) {
    if (!src || !*src) return strdup("[]");
    size_t src_len = strlen(src);
    size_t max_out = src_len + 100;  /* initial estimate */
    char *out = malloc(max_out + 1);
    if (!out) return NULL;

#define GROW_CHECK(need) do { \
    while (w + (need) > max_out) { \
        size_t new_max = max_out * 2; \
        char *new_out = realloc(out, new_max + 1); \
        if (!new_out) { \
            LOGE("transform_templates: realloc failed"); \
            free(out); return NULL; \
        } \
        out = new_out; \
        max_out = new_max; \
    } \
} while (0)

    /* Unquoted field names to strip (injector-internal) */
    static const char *injector_fields[] = {
        "fuse_direct", "monitor", "inject_enable", NULL
    };

    int depth = 0;      /* {} nesting */
    int in_str = 0;     /* inside JSON string */
    int esc = 0;        /* escape char pending */
    int expect_key = 1; /* next string at depth=1 is a field key (within array) */
    int need_comma = 0; /* 1 = injector fields were stripped; next key needs leading comma */
    int has_emitted_field = 0; /* 1 = at least one non-injector key-value has been emitted at depth=1 */
    size_t w = 0, r = 0;

    while (r < src_len) {
        if (w >= max_out) {
            /* Dynamic realloc instead of hard failure */
            size_t new_max = max_out * 2;
            char *new_out = realloc(out, new_max + 1);
            if (!new_out) {
                LOGE("transform_templates: realloc failed, falling back");
                free(out); return NULL;
            }
            out = new_out;
            max_out = new_max;
        }

        char c = src[r];

        /* ── Escape handling ── */
        if (esc) { esc = 0; goto emit; }
        if (c == '\\' && in_str) { esc = 1; goto emit; }

        /* ── String boundaries & field key detection ── */
        if (c == '"') {
            in_str = !in_str;

            /* Depth=1, after '{' or ',': this " starts a field key */
            if (in_str && depth == 1 && expect_key) {
                size_t ks = r + 1;            /* key start */
                size_t ke = ks;               /* key end (on closing ") */
                while (ke < src_len && src[ke] != '"') ke++;
                size_t klen = ke - ks;

                if (klen > 0) {
                    /* Buffer the key (bounded to 63 chars) */
                    size_t blen = klen < 63 ? klen : 63;
                    char kbuf[64];
                    memcpy(kbuf, src + ks, blen);
                    kbuf[blen] = '\0';

                    /* Check injector fields (skip entire key:value) */
                    int is_injector = 0;
                    for (int f = 0; injector_fields[f]; f++) {
                        size_t flen = strlen(injector_fields[f]);
                        if (klen == flen && memcmp(kbuf, injector_fields[f], flen) == 0) {
                            is_injector = 1; break;
                        }
                    }

                    /* Check hide_paths → filter_path rename */
                    int is_hide_paths = (klen == 10 && memcmp(kbuf, "hide_paths", 10) == 0);
                    /* Check allow_rules → allow_paths rename */
                    int is_allow_rules = (klen == 11 && memcmp(kbuf, "allow_rules", 11) == 0);

                    if (is_injector) {
                        /* ── Injector field: skip key + : + value + trailing comma ── */
                        r = ke + 1;           /* past closing " */
                        expect_key = 0;
                        in_str = 0;

                        /* Skip whitespace before ':', then consume ':' */
                        while (r < src_len && (src[r] == ' ' || src[r] == '\t' || src[r] == '\n' || src[r] == '\r')) r++;
                        if (r < src_len && src[r] == ':') r++;
                        /* Skip whitespace between ':' and value */
                        while (r < src_len && (src[r] == ' ' || src[r] == '\t' || src[r] == '\n' || src[r] == '\r')) r++;
                        /* Skip value generically: string, number, boolean, null, object, array */
                        if (r < src_len) {
                            char vc = src[r];
                            if (vc == '"') {
                                /* Value is a string — skip to closing unescaped " */
                                r++;
                                while (r < src_len) {
                                    if (src[r] == '\\') { r += 2; continue; }
                                    if (src[r] == '"') { r++; break; }
                                    r++;
                                }
                            } else if (vc == '{') {
                                /* Value is an object — skip counting {} depth */
                                int obj_depth = 1; r++;
                                while (r < src_len && obj_depth > 0) {
                                    if (src[r] == '"') {
                                        r++;
                                        /* Skip to closing unescaped " (even backslash count) */
                                        while (r < src_len) {
                                            if (src[r] == '"') {
                                                int bs = 0;
                                                while (bs < r && src[r - bs - 1] == '\\') bs++;
                                                if (bs % 2 == 0) break; /* unescaped quote */
                                            }
                                            r++;
                                        }
                                        if (r < src_len) r++;
                                    } else if (src[r] == '{') { obj_depth++; r++; }
                                    else if (src[r] == '}') { obj_depth--; r++; }
                                    else r++;
                                }
                            } else if (vc == '[') {
                                /* Value is an array — skip counting [] depth */
                                int arr_depth = 1; r++;
                                while (r < src_len && arr_depth > 0) {
                                    if (src[r] == '[') { arr_depth++; r++; }
                                    else if (src[r] == ']') { arr_depth--; r++; }
                                    else r++;
                                }
                            } else {
                                /* Value is a number, boolean, or null — skip alphanumeric chars */
                                while (r < src_len && (isalnum((unsigned char)src[r]) || src[r] == '-' || src[r] == '+' || src[r] == '.' || src[r] == 'e' || src[r] == 'E')) r++;
                            }
                        }
                        /* Skip whitespace then trailing comma */
                        while (r < src_len && (src[r] == ' ' || src[r] == '\t' || src[r] == '\n' || src[r] == '\r')) r++;
                        if (r < src_len && src[r] == ',') {
                            r++;
                            expect_key = 1;
                        }
                        /* Remove trailing comma from output buffer (skip whitespace backward) */
                        {
                            size_t b = w;
                            while (b > 0 && (out[b-1] == ' ' || out[b-1] == '\t' || out[b-1] == '\n' || out[b-1] == '\r')) b--;
                            if (b > 0 && out[b-1] == ',') {
                                w = b - 1;
                                need_comma = 1;
                            }
                        }
                        continue;   /* skip field entirely */
                    }

                    if (is_hide_paths) {
                        /* ── Rename "hide_paths" → "filter_path" ── */
                        /* If injector fields were stripped just before, re-add the comma */
                        if (need_comma) {
                            GROW_CHECK(1);
                            out[w++] = ',';
                            need_comma = 0;
                        }
                        has_emitted_field = 1;
                        GROW_CHECK(13);
                        memcpy(out + w, "\"filter_path\"", 13);
                        w += 13;
                        r = ke + 1;   /* past closing " */
                        in_str = 0;
                        expect_key = 0;
                        continue;     /* key written, continue to ':' */
                    }

                    if (is_allow_rules) {
                        /* ── Rename "allow_rules" → "allow_paths" ── */
                        if (need_comma) {
                            GROW_CHECK(1);
                            out[w++] = ',';
                            need_comma = 0;
                        }
                        has_emitted_field = 1;
                        GROW_CHECK(13);
                        memcpy(out + w, "\"allow_paths\"", 13);
                        w += 13;
                        r = ke + 1;   /* past closing " */
                        in_str = 0;
                        expect_key = 0;
                        continue;     /* key written, continue to ':' */
                    }

                    /* Non-injector, non-hide_paths, non-allow_rules key at depth=1: mark field emitted */
                    has_emitted_field = 1;
                }
            } else if (!in_str) {
                /* Closing quote of a key or value — expect_key remains 0 until ':' */
            }

            /* Fall through: copy the quote character normally */
            if (need_comma && in_str && has_emitted_field) {
                /* Injector fields were stripped just before; re-add leading comma.
                 * Only if at least one non-injector field was already emitted in this object. */
                GROW_CHECK(1);
                out[w++] = ',';
                need_comma = 0;
            }
            goto emit;
        }

        /* ── Depth tracking & state transitions (outside strings) ── */
        if (!in_str) {
            if (c == '{') { depth++; if (depth == 1) { expect_key = 1; need_comma = 0; has_emitted_field = 0; } }
            else if (c == '}') { depth--; if (depth == 1) need_comma = 0; }
            else if (c == ',') { if (depth == 1) expect_key = 1; need_comma = 0; }
            else if (c == ':') { expect_key = 0; }
        }

    emit:
        GROW_CHECK(1);
        out[w++] = c;
        r++;
    }

    out[w] = '\0';
    return out;
}

/* ─── JNI entry point ─── */

JNIEXPORT jstring JNICALL
Java_me_gm_cleaner_plugin_xposed_NativeConfigBridge_nativeFetchConfig(
    JNIEnv *env, jclass clazz, jstring cmd_j) {

    if (cmd_j == NULL) { LOGE("cmd_j is NULL"); return NULL; }

    const char *cmd = (*env)->GetStringUTFChars(env, cmd_j, NULL);
    if (!cmd) { LOGE("GetStringUTFChars failed"); return NULL; }

    // 1) socket
    int sock = socket(AF_UNIX, SOCK_DGRAM | SOCK_CLOEXEC, 0);
    if (sock < 0) {
        LOGE("socket failed: errno=%d (%s)", errno, strerror(errno));
        (*env)->ReleaseStringUTFChars(env, cmd_j, cmd);
        return NULL;
    }

    // 2) bind
    struct sockaddr_un local;
    memset(&local, 0, sizeof(local));
    local.sun_family = AF_UNIX;
    local.sun_path[0] = '\0';
    snprintf(local.sun_path + 1, sizeof(local.sun_path) - 2, "nsp_jni_%d", getpid());
    socklen_t local_len = offsetof(struct sockaddr_un, sun_path) + 1 + strlen(local.sun_path + 1);
    if (bind(sock, (struct sockaddr *)&local, local_len) < 0) {
        LOGE("bind failed: errno=%d (%s)", errno, strerror(errno));
        close(sock); (*env)->ReleaseStringUTFChars(env, cmd_j, cmd);
        return NULL;
    }

    // 3) sendto
    struct sockaddr_un dest;
    memset(&dest, 0, sizeof(dest));
    dest.sun_family = AF_UNIX;
    dest.sun_path[0] = '\0';
    strncpy(dest.sun_path + 1, CONFIG_BROADCAST_SOCKET, sizeof(dest.sun_path) - 2);
    socklen_t dest_len = offsetof(struct sockaddr_un, sun_path) + 1 + strlen(CONFIG_BROADCAST_SOCKET);
    if (sendto(sock, cmd, strlen(cmd), MSG_NOSIGNAL,
               (struct sockaddr *)&dest, dest_len) < 0) {
        LOGE("sendto failed: errno=%d (%s)", errno, strerror(errno));
        close(sock); (*env)->ReleaseStringUTFChars(env, cmd_j, cmd);
        return NULL;
    }
    (*env)->ReleaseStringUTFChars(env, cmd_j, cmd);

    // 4) poll + recv
    struct pollfd pfd;
    memset(&pfd, 0, sizeof(pfd));
    pfd.fd = sock; pfd.events = POLLIN;
    int pr = poll(&pfd, 1, RECV_TIMEOUT_MS);
    if (pr <= 0) {
        LOGE("poll %s", pr == 0 ? "timed out" : "error");
        close(sock); return NULL;
    }

    char *raw = malloc(BROADCAST_RECEIVE_MAX_SIZE);
    if (!raw) { LOGE("malloc failed"); close(sock); return NULL; }
    ssize_t n = recv(sock, raw, BROADCAST_RECEIVE_MAX_SIZE - 1, MSG_DONTWAIT);
    close(sock);
    if (n <= 0) { LOGE("recv failed"); free(raw); return NULL; }
    raw[n] = '\0';

    LOGD("recv OK: %zd bytes, preview: %.200s", n, raw);

    // 5) Extract templates array
    char *templates = extract_templates_array(raw);
    free(raw);
    if (!templates) { LOGE("extract failed"); return NULL; }
    LOGD("extracted: %s", templates);

    // 6) Transform: rename + strip fields
    char *cleaned = transform_templates(templates);
    free(templates);
    if (!cleaned) { LOGE("transform failed"); return NULL; }
    LOGD("cleaned: %s", cleaned);

    jstring result = (*env)->NewStringUTF(env, cleaned);
    free(cleaned);
    return result;
}

/* ─── Subscription ───
 * Blocking call: subscribe to config changes via UDS.
 * Runs on a dedicated Kotlin thread.
 * Sends "SUBSCRIBE", then loops receiving config push notifications.
 * Each push is processed (extract + transform) and the cleaned result
 * is passed to the Java callback.
 */

static JavaVM *g_jvm = NULL;

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved) {
    g_jvm = vm;
    return JNI_VERSION_1_6;
}

static int g_subscribe_running = 0;  /* use __atomic_* for cross-thread access */
static int g_sub_sock = -1;          /* subscription socket fd, for interrupt by nativeStopSubscribe */
static int g_sub_counter = 0;        /* counter for unique bind address on rapid restart */

/* 缓存的接口方法 ID（避免在 R8 混淆后从具体匿名类按名查找失败） */
static jclass g_onConfigUpdateListener_class = NULL;
static jmethodID g_onConfigUpdate_mid = NULL;
static jmethodID g_onError_mid = NULL;

JNIEXPORT void JNICALL
Java_me_gm_cleaner_plugin_xposed_NativeConfigBridge_nativeSubscribeConfig(
    JNIEnv *env, jclass clazz, jobject callback) {

    // 代数计数器 + 当前代数，防止清理时关闭新订阅的 socket
    static int g_sub_gen = 0;
    int my_gen = __sync_fetch_and_add(&g_sub_gen, 1);
    
    /* Atomic test-and-set to guard against concurrent subscription setup */
    if (__sync_lock_test_and_set(&g_subscribe_running, 1)) {
        LOGE("subscribe already running"); return;
    }

    // 0) 首次调用时缓存接口方法 ID（一次初始化）
    if (g_onConfigUpdateListener_class == NULL) {
        jclass local = (*env)->FindClass(env,
            "me/gm/cleaner/plugin/xposed/OnConfigUpdateListener");
        if (local == NULL) {
            LOGE("nativeSubscribeConfig: FindClass OnConfigUpdateListener failed");
            __atomic_store_n(&g_subscribe_running, 0, __ATOMIC_RELEASE); return;
        }
        g_onConfigUpdateListener_class =
            (jclass)(*env)->NewGlobalRef(env, local);
        g_onConfigUpdate_mid = (*env)->GetMethodID(env,
            g_onConfigUpdateListener_class, "onConfigUpdate",
            "(Ljava/lang/String;)V");
        g_onError_mid = (*env)->GetMethodID(env,
            g_onConfigUpdateListener_class, "onError",
            "(Ljava/lang/String;)V");
        (*env)->DeleteLocalRef(env, local);
        LOGE("nativeSubscribeConfig: cached onConfigUpdate=%p onError=%p",
             (void*)g_onConfigUpdate_mid, (void*)g_onError_mid);
    }

    // 1) socket
    int sock = socket(AF_UNIX, SOCK_DGRAM | SOCK_CLOEXEC, 0);
    if (sock < 0) { LOGE("subscribe socket failed"); __atomic_store_n(&g_subscribe_running, 0, __ATOMIC_RELEASE); return; }
    __atomic_store_n(&g_sub_sock, sock, __ATOMIC_RELEASE);

    // 2) bind — use unique counter to avoid EADDRINUSE on rapid stop/start
    int seq = __sync_fetch_and_add(&g_sub_counter, 1);
    struct sockaddr_un local;
    memset(&local, 0, sizeof(local));
    local.sun_family = AF_UNIX;
    local.sun_path[0] = '\0';
    snprintf(local.sun_path + 1, sizeof(local.sun_path) - 2, "nsp_sub_%d_%d", getpid(), seq);
    socklen_t local_len = offsetof(struct sockaddr_un, sun_path) + 1 + strlen(local.sun_path + 1);
    if (bind(sock, (struct sockaddr *)&local, local_len) < 0) {
        LOGE("subscribe bind failed"); close(sock); __atomic_store_n(&g_subscribe_running, 0, __ATOMIC_RELEASE); return;
    }

    // 3) sendto SUBSCRIBE
    struct sockaddr_un dest;
    memset(&dest, 0, sizeof(dest));
    dest.sun_family = AF_UNIX;
    dest.sun_path[0] = '\0';
    strncpy(dest.sun_path + 1, CONFIG_BROADCAST_SOCKET, sizeof(dest.sun_path) - 2);
    socklen_t dest_len = offsetof(struct sockaddr_un, sun_path) + 1 + strlen(CONFIG_BROADCAST_SOCKET);
    if (sendto(sock, "SUBSCRIBE", 9, MSG_NOSIGNAL, (struct sockaddr *)&dest, dest_len) < 0) {
        LOGE("subscribe sendto failed"); close(sock); __atomic_store_n(&g_subscribe_running, 0, __ATOMIC_RELEASE); return;
    }

    // 4) Loop: poll + recv → process → callback
    //
    // 关键修复：不再因"30s 静默"自动重连。
    // injector 只在配置变更时推送，长时间无数据是正常状态。
    // 仅在 recv 返回对端失联错误（ECONNREFUSED/ENOTCONN/EPIPE 等）
    // 时才认为 injector 退出，执行 reconnect。无数据时永远保持订阅，
    // 避免服务端订阅者列表周期性抖动。
    while (__atomic_load_n(&g_subscribe_running, __ATOMIC_ACQUIRE)) {
        struct pollfd pfd;
        memset(&pfd, 0, sizeof(pfd));
        pfd.fd = sock; pfd.events = POLLIN;
        int pr = poll(&pfd, 1, 2000);
        if (pr <= 0) {
            // 超时或被中断：仅检查是否被 stop，否则保持订阅继续等待
            if (!__atomic_load_n(&g_subscribe_running, __ATOMIC_ACQUIRE)) break;
            continue;
        }

        // poll 可读 → 读取数据
        char *raw = malloc(BROADCAST_RECEIVE_MAX_SIZE);
        if (!raw) continue;
        ssize_t n = recv(sock, raw, BROADCAST_RECEIVE_MAX_SIZE - 1, MSG_DONTWAIT);
        if (n < 0) {
            int e = errno;
            free(raw);
            // EAGAIN/EWOULDBLOCK/EINTR：暂时无数据或被中断，继续等待
            if (e == EAGAIN || e == EWOULDBLOCK || e == EINTR) continue;
            // 其他错误（ECONNREFUSED/ENOTCONN/EPIPE/EBADF 等）：
            // injector 已退出或 socket 失效 → 关闭并 reconnect
            LOGE("subscribe recv error: %s (%d), reconnecting...", strerror(e), e);
            int old_sock = __atomic_exchange_n(&g_sub_sock, -1, __ATOMIC_ACQ_REL);
            if (old_sock >= 0) close(old_sock);

            // 重连尝试循环，直到成功或被 stop
            while (__atomic_load_n(&g_subscribe_running, __ATOMIC_ACQUIRE)) {
                sock = socket(AF_UNIX, SOCK_DGRAM | SOCK_CLOEXEC, 0);
                if (sock < 0) { LOGE("subscribe reconnect socket failed"); __atomic_store_n(&g_subscribe_running, 0, __ATOMIC_RELEASE); return; }
                __atomic_store_n(&g_sub_sock, sock, __ATOMIC_RELEASE);

                memset(&local, 0, sizeof(local));
                local.sun_family = AF_UNIX;
                local.sun_path[0] = '\0';
                int reconn_seq = __sync_fetch_and_add(&g_sub_counter, 1);
                snprintf(local.sun_path + 1, sizeof(local.sun_path) - 2, "nsp_sub_%d_%d", getpid(), reconn_seq);
                local_len = offsetof(struct sockaddr_un, sun_path) + 1 + strlen(local.sun_path + 1);
                if (bind(sock, (struct sockaddr *)&local, local_len) < 0) {
                    LOGE("subscribe reconnect bind failed"); close(sock);
                    __atomic_store_n(&g_subscribe_running, 0, __ATOMIC_RELEASE); return;
                }

                memset(&dest, 0, sizeof(dest));
                dest.sun_family = AF_UNIX;
                dest.sun_path[0] = '\0';
                strncpy(dest.sun_path + 1, CONFIG_BROADCAST_SOCKET, sizeof(dest.sun_path) - 2);
                dest_len = offsetof(struct sockaddr_un, sun_path) + 1 + strlen(CONFIG_BROADCAST_SOCKET);
                if (sendto(sock, "SUBSCRIBE", 9, MSG_NOSIGNAL, (struct sockaddr *)&dest, dest_len) < 0) {
                    LOGE("subscribe reconnect sendto failed"); close(sock);
                    // 退避后重试
                    struct timespec ts = { 2, 0 };
                    nanosleep(&ts, NULL);
                    continue;
                }

                LOGD("subscribe: reconnected successfully");
                // Notify Kotlin side that we reconnected
                if (g_onError_mid) {
                    JNIEnv *cb_env2 = NULL;
                    int nd2 = 0;
                    int ger2 = (*g_jvm)->GetEnv(g_jvm, (void**)&cb_env2, JNI_VERSION_1_6);
                    if (ger2 == JNI_EDETACHED) {
                        if ((*g_jvm)->AttachCurrentThread(g_jvm, &cb_env2, NULL) != JNI_OK) {
                            cb_env2 = NULL;
                        } else { nd2 = 1; }
                    } else if (ger2 != JNI_OK) {
                        cb_env2 = NULL;
                    }
                    if (cb_env2 != NULL && ger2 != JNI_ERR) {
                        jstring err = (*cb_env2)->NewStringUTF(cb_env2, "injector disconnected, reconnected");
                        (*cb_env2)->CallVoidMethod(cb_env2, callback, g_onError_mid, err);
                        if ((*cb_env2)->ExceptionCheck(cb_env2)) (*cb_env2)->ExceptionClear(cb_env2);
                        (*cb_env2)->DeleteLocalRef(cb_env2, err);
                        if (nd2) (*g_jvm)->DetachCurrentThread(g_jvm);
                    }
                }
                break;  // 重连成功，退出重连循环回到主循环
            }
            continue;
        }
        if (n == 0) { free(raw); continue; }
        raw[n] = '\0';

        // Attach JNI early so we can call onError on failure
        JNIEnv *cb_env;
        int need_detach = 0;
        int ger = (*g_jvm)->GetEnv(g_jvm, (void**)&cb_env, JNI_VERSION_1_6);
        if (ger == JNI_EDETACHED) {
            if ((*g_jvm)->AttachCurrentThread(g_jvm, &cb_env, NULL) != JNI_OK) {
                LOGE("AttachCurrentThread failed"); free(raw); continue;
            }
            need_detach = 1;
        } else if (ger != JNI_OK) { free(raw); continue; }

        // Extract templates array + transform
        char *arr = extract_templates_array(raw);
        free(raw);
        if (!arr) {
            if (g_onError_mid) {
                jstring err = (*cb_env)->NewStringUTF(cb_env, "extract_templates_array failed");
                if (err != NULL) {
                    (*cb_env)->CallVoidMethod(cb_env, callback, g_onError_mid, err);
                    if ((*cb_env)->ExceptionCheck(cb_env)) (*cb_env)->ExceptionClear(cb_env);
                    (*cb_env)->DeleteLocalRef(cb_env, err);
                }
            }
            if (need_detach) (*g_jvm)->DetachCurrentThread(g_jvm);
            continue;
        }
        char *cleaned = transform_templates(arr);
        free(arr);
        if (!cleaned) {
            if (g_onError_mid) {
                jstring err = (*cb_env)->NewStringUTF(cb_env, "transform_templates failed");
                if (err != NULL) {
                    (*cb_env)->CallVoidMethod(cb_env, callback, g_onError_mid, err);
                    if ((*cb_env)->ExceptionCheck(cb_env)) (*cb_env)->ExceptionClear(cb_env);
                    (*cb_env)->DeleteLocalRef(cb_env, err);
                }
            }
            if (need_detach) (*g_jvm)->DetachCurrentThread(g_jvm);
            continue;
        }

        // Call Java callback via cached interface method ID
        if (g_onConfigUpdate_mid) {
            jstring js = (*cb_env)->NewStringUTF(cb_env, cleaned);
            if (js == NULL) {
                LOGE("NewStringUTF returned NULL, skipping callback");
                free(cleaned);
                if (need_detach) (*g_jvm)->DetachCurrentThread(g_jvm);
                continue;
            }
            (*cb_env)->CallVoidMethod(cb_env, callback, g_onConfigUpdate_mid, js);
            if ((*cb_env)->ExceptionCheck(cb_env)) (*cb_env)->ExceptionClear(cb_env);
            (*cb_env)->DeleteLocalRef(cb_env, js);
        }
        free(cleaned);
        if (need_detach) (*g_jvm)->DetachCurrentThread(g_jvm);
    }

    // 原子交换 g_sub_sock，仅当代数匹配时才关闭
    if (my_gen == __atomic_load_n(&g_sub_gen, __ATOMIC_ACQUIRE) - 1) {
        int old_sock = __atomic_exchange_n(&g_sub_sock, -1, __ATOMIC_ACQ_REL);
        if (old_sock >= 0) close(old_sock);
    }
    __atomic_store_n(&g_subscribe_running, 0, __ATOMIC_RELEASE);
}

JNIEXPORT void JNICALL
Java_me_gm_cleaner_plugin_xposed_NativeConfigBridge_nativeStopSubscribe(
    JNIEnv *env, jclass clazz) {
    __atomic_store_n(&g_subscribe_running, 0, __ATOMIC_RELEASE);

    // 先原子交换拿到 fd 所有权，再发 UNSUBSCRIBE + close，消除竞态
    int fd = __atomic_exchange_n(&g_sub_sock, -1, __ATOMIC_ACQ_REL);
    if (fd >= 0) {
        struct sockaddr_un dest;
        memset(&dest, 0, sizeof(dest));
        dest.sun_family = AF_UNIX;
        dest.sun_path[0] = '\0';
        strncpy(dest.sun_path + 1, CONFIG_BROADCAST_SOCKET, sizeof(dest.sun_path) - 2);
        socklen_t dest_len = offsetof(struct sockaddr_un, sun_path) + 1 + strlen(CONFIG_BROADCAST_SOCKET);
        sendto(fd, "UNSUBSCRIBE", 11, MSG_NOSIGNAL,
               (struct sockaddr *)&dest, dest_len);
        close(fd);
    }
}
