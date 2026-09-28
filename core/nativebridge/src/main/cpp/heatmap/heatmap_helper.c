// Graffux raw touch heatmap helper. Packaged as lib/<abi>/libgraffux_heatmap.so so that Android
// extracts it, executable, into nativeLibraryDir; the app runs it as `su -c <that path>`.
//
// Streams the touch controller's capacitive heatmap to stdout as binary records (format below) for
// stroke-model training (tools/stroke-model/SCHEMA.md "heatmap"). READ ONLY: it sets no V4L2 control
// or format and sends the sec factory interface only read commands (get_x_num, get_y_num,
// run_delta_read_all). No set_*, enable_*, calibration or firmware command is ever written.
//
//   Mode A, v4l2:      /dev/v4l-touch0 -- query the format, mmap, stream. Fails with EBUSY when
//                      system_server's InputReader already streams it (likely on Pixels).
//   Mode B, sec_delta: /sys/devices/virtual/sec/tsp/{cmd,cmd_result} -- poll run_delta_read_all.
//
// Record: "GHM1" | u8 type | u8 version (1) | u16 0 | u32 payload length | payload. Little-endian.
//   type 1 HELLO  u8 source | u8 dtype | u16 w | u16 h | u16 0 | i64 monotonic ns | utf8 detail
//   type 2 FRAME  u8 source | u8 dtype | u16 w | u16 h | u16 flags | i64 monotonic ns |
//                 i64 buffer ns (0 = none) | u32 sequence | w*h int16 row-major
//   type 3 ERROR  i64 monotonic ns | utf8 text (fatal; the helper exits after it)
//   type 4 INFO   i64 monotonic ns | utf8 text (e.g. why v4l2 was skipped)
// source: 1 v4l2, 2 sec_delta. dtype: 1 int16. flags bit 0: buffer ns is CLOCK_MONOTONIC.
// Monotonic ns is CLOCK_MONOTONIC when the frame was read, the clock of MotionEvent times.
//
// Stops on SIGTERM/SIGINT/SIGHUP, on EOF of stdin (the app closed its end) and on EPIPE.
//
//   --mode=auto|v4l2|sec   (default auto: v4l2, then sec on any failure)
//   --device=PATH          (default /dev/v4l-touch0)
//   --max-hz=N             (sec polling cap, default 120)
//   --ignore-stdin         (do not stop on stdin EOF; for `adb shell ... </dev/null`)
#define _GNU_SOURCE
#include <errno.h>
#include <fcntl.h>
#include <linux/videodev2.h>
#include <poll.h>
#include <signal.h>
#include <stdarg.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/mman.h>
#include <time.h>
#include <unistd.h>

#include "sec_parse.h"

enum { REC_HELLO = 1, REC_FRAME = 2, REC_ERROR = 3, REC_INFO = 4 };
enum { SRC_V4L2 = 1, SRC_SEC = 2 };
enum { DTYPE_I16 = 1 };
#define MAX_CELLS 8192
#define SEC_DIR "/sys/devices/virtual/sec/tsp"
#define SEC_RESULT_MAX (MAX_CELLS * 8)

static volatile sig_atomic_t g_stop = 0;
static int g_watch_stdin = 1;

static void on_signal(int sig) { (void)sig; g_stop = 1; }

static int64_t mono_ns(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (int64_t)ts.tv_sec * 1000000000LL + ts.tv_nsec;
}

static int write_all(const void *buf, size_t len) {
    const uint8_t *p = buf;
    while (len > 0) {
        ssize_t w = write(STDOUT_FILENO, p, len);
        if (w < 0) {
            if (errno == EINTR) continue;
            g_stop = 1;  // EPIPE: the reader is gone.
            return -1;
        }
        p += w;
        len -= (size_t)w;
    }
    return 0;
}

static void put16(uint8_t *b, uint16_t v) { b[0] = v & 0xff; b[1] = v >> 8; }
static void put32(uint8_t *b, uint32_t v) { for (int i = 0; i < 4; i++) b[i] = (v >> (8 * i)) & 0xff; }
static void put64(uint8_t *b, int64_t v) { for (int i = 0; i < 8; i++) b[i] = ((uint64_t)v >> (8 * i)) & 0xff; }

static int emit(int type, const void *a, size_t alen, const void *b, size_t blen) {
    uint8_t h[12] = {'G', 'H', 'M', '1', (uint8_t)type, 1, 0, 0};
    put32(h + 8, (uint32_t)(alen + blen));
    if (write_all(h, sizeof h) != 0 || write_all(a, alen) != 0) return -1;
    return blen ? write_all(b, blen) : 0;
}

static void emit_text(int type, const char *fmt, ...) {
    char text[512];
    va_list ap;
    va_start(ap, fmt);
    int n = vsnprintf(text, sizeof text, fmt, ap);
    va_end(ap);
    if (n < 0) n = 0;
    if ((size_t)n >= sizeof text) n = sizeof text - 1;
    uint8_t t[8];
    put64(t, mono_ns());
    emit(type, t, sizeof t, text, (size_t)n);
}

static void emit_hello(int source, unsigned w, unsigned h, const char *detail) {
    uint8_t p[16] = {(uint8_t)source, DTYPE_I16};
    put16(p + 2, (uint16_t)w);
    put16(p + 4, (uint16_t)h);
    put64(p + 8, mono_ns());
    emit(REC_HELLO, p, sizeof p, detail, strlen(detail));
}

static int emit_frame(int source, unsigned w, unsigned h, int flags, int64_t mono, int64_t buf_ns,
                      uint32_t seq, const int16_t *cells) {
    uint8_t p[28] = {(uint8_t)source, DTYPE_I16};
    put16(p + 2, (uint16_t)w);
    put16(p + 4, (uint16_t)h);
    put16(p + 6, (uint16_t)flags);
    put64(p + 8, mono);
    put64(p + 16, buf_ns);
    put32(p + 24, seq);
    // Android is little-endian, so int16 cells are already in record byte order.
    return emit(REC_FRAME, p, sizeof p, cells, (size_t)w * h * 2);
}

/** True when the app closed our stdin (or it is at EOF): time to exit. */
static int stdin_closed(int timeout_ms) {
    struct pollfd p = {STDIN_FILENO, POLLIN, 0};
    if (!g_watch_stdin) {
        if (timeout_ms > 0) poll(NULL, 0, timeout_ms);
        return 0;
    }
    if (poll(&p, 1, timeout_ms) <= 0) return 0;
    if (p.revents & (POLLHUP | POLLERR | POLLNVAL)) return 1;
    if (p.revents & POLLIN) {
        char junk[64];
        ssize_t r = read(STDIN_FILENO, junk, sizeof junk);
        return r == 0;
    }
    return 0;
}

static int xioctl(int fd, unsigned long req, void *arg) {
    int r;
    do { r = ioctl(fd, req, arg); } while (r == -1 && errno == EINTR);
    return r;
}

static const char *errno_hint(int e) {
    switch (e) {
        case EBUSY: return "EBUSY (another process, likely system_server, streams it)";
        case EACCES: return "EACCES (permission denied or SELinux)";
        case EPERM: return "EPERM (permission denied)";
        case ENOENT: return "ENOENT (no such device)";
        default: return strerror(e);
    }
}

// ------------------------------------------------------------------------------------------------
// Mode A: V4L2

/** Streams until stopped. Returns 0 after a clean stop, or fills [why] and returns -1 before HELLO. */
static int run_v4l2(const char *dev, char *why, size_t why_len) {
    int fd = open(dev, O_RDWR | O_NONBLOCK);
    if (fd < 0) { snprintf(why, why_len, "open %s: %s", dev, errno_hint(errno)); return -1; }
    struct v4l2_format fmt = {0};
    fmt.type = V4L2_BUF_TYPE_VIDEO_CAPTURE;
    if (xioctl(fd, VIDIOC_G_FMT, &fmt) != 0) {
        snprintf(why, why_len, "G_FMT: %s", errno_hint(errno)); close(fd); return -1;
    }
    uint32_t pf = fmt.fmt.pix.pixelformat;
    unsigned w = fmt.fmt.pix.width, h = fmt.fmt.pix.height, bpl = fmt.fmt.pix.bytesperline;
    int bytes_per_cell;
    int is_signed;
    switch (pf) {
        case V4L2_TCH_FMT_DELTA_TD16: bytes_per_cell = 2; is_signed = 1; break;
        case V4L2_TCH_FMT_TU16: bytes_per_cell = 2; is_signed = 0; break;
        case V4L2_TCH_FMT_DELTA_TD08: bytes_per_cell = 1; is_signed = 1; break;
        case V4L2_TCH_FMT_TU08: bytes_per_cell = 1; is_signed = 0; break;
        // Unknown fourcc: assume the common case, signed 16-bit deltas. Recorded in HELLO's detail.
        default: bytes_per_cell = 2; is_signed = 1; break;
    }
    if (w == 0 || h == 0 || (size_t)w * h > MAX_CELLS) {
        snprintf(why, why_len, "unexpected format %ux%u", w, h); close(fd); return -1;
    }
    if (bpl < w * (unsigned)bytes_per_cell) bpl = w * (unsigned)bytes_per_cell;

    struct v4l2_requestbuffers req = {0};
    req.count = 4;
    req.type = V4L2_BUF_TYPE_VIDEO_CAPTURE;
    req.memory = V4L2_MEMORY_MMAP;
    if (xioctl(fd, VIDIOC_REQBUFS, &req) != 0 || req.count == 0) {
        snprintf(why, why_len, "REQBUFS: %s", errno_hint(errno)); close(fd); return -1;
    }
    void *maps[8] = {0};
    size_t lens[8] = {0};
    unsigned nbuf = req.count < 8 ? req.count : 8;
    for (unsigned i = 0; i < nbuf; i++) {
        struct v4l2_buffer b = {0};
        b.type = req.type; b.memory = req.memory; b.index = i;
        if (xioctl(fd, VIDIOC_QUERYBUF, &b) != 0) {
            snprintf(why, why_len, "QUERYBUF: %s", errno_hint(errno)); goto fail;
        }
        // The frame loop reads h rows of bpl bytes from every mapping; a driver reporting a
        // buffer shorter than that would make it read past the end of the mmap.
        if ((size_t)bpl * h > (size_t)b.length) {
            snprintf(why, why_len, "buffer %u too small: %u bytes < %zu (%u x %u)", i, b.length,
                     (size_t)bpl * h, bpl, h);
            goto fail;
        }
        lens[i] = b.length;
        maps[i] = mmap(NULL, b.length, PROT_READ, MAP_SHARED, fd, b.m.offset);
        if (maps[i] == MAP_FAILED) {
            maps[i] = NULL;
            snprintf(why, why_len, "mmap: %s", errno_hint(errno)); goto fail;
        }
        if (xioctl(fd, VIDIOC_QBUF, &b) != 0) {
            snprintf(why, why_len, "QBUF: %s", errno_hint(errno)); goto fail;
        }
    }
    enum v4l2_buf_type type = V4L2_BUF_TYPE_VIDEO_CAPTURE;
    if (xioctl(fd, VIDIOC_STREAMON, &type) != 0) {
        snprintf(why, why_len, "STREAMON: %s", errno_hint(errno)); goto fail;
    }

    char detail[160];
    snprintf(detail, sizeof detail, "v4l2 %s fourcc=%c%c%c%c bytesperline=%u", dev, pf & 0xff,
             (pf >> 8) & 0xff, (pf >> 16) & 0xff, (pf >> 24) & 0xff, bpl);
    emit_hello(SRC_V4L2, w, h, detail);

    static int16_t cells[MAX_CELLS];
    while (!g_stop) {
        struct pollfd p[2] = {{fd, POLLIN, 0}, {STDIN_FILENO, POLLIN, 0}};
        int r = poll(p, g_watch_stdin ? 2 : 1, 500);
        if (r < 0 && errno != EINTR) break;
        if (g_watch_stdin && (p[1].revents & (POLLIN | POLLHUP | POLLERR)) && stdin_closed(0)) break;
        if (r <= 0 || !(p[0].revents & POLLIN)) continue;
        struct v4l2_buffer b = {0};
        b.type = type; b.memory = V4L2_MEMORY_MMAP;
        if (xioctl(fd, VIDIOC_DQBUF, &b) != 0) {
            if (errno == EAGAIN) continue;
            emit_text(REC_ERROR, "DQBUF: %s", errno_hint(errno));
            break;
        }
        int64_t now = mono_ns();
        if (b.index < nbuf && maps[b.index]) {
            const uint8_t *src = maps[b.index];
            for (unsigned y = 0; y < h; y++) {
                const uint8_t *row = src + (size_t)y * bpl;
                for (unsigned x = 0; x < w; x++) {
                    int v;
                    if (bytes_per_cell == 2) {
                        uint16_t u = (uint16_t)(row[2 * x] | (row[2 * x + 1] << 8));
                        v = is_signed ? (int16_t)u : (u > INT16_MAX ? INT16_MAX : u);
                    } else {
                        v = is_signed ? (int8_t)row[x] : row[x];
                    }
                    cells[y * w + x] = (int16_t)v;
                }
            }
            int64_t buf_ns = (int64_t)b.timestamp.tv_sec * 1000000000LL + (int64_t)b.timestamp.tv_usec * 1000;
            int mono_flag = (b.flags & V4L2_BUF_FLAG_TIMESTAMP_MASK) == V4L2_BUF_FLAG_TIMESTAMP_MONOTONIC;
            emit_frame(SRC_V4L2, w, h, mono_flag, now, buf_ns, b.sequence, cells);
        }
        xioctl(fd, VIDIOC_QBUF, &b);
    }
    xioctl(fd, VIDIOC_STREAMOFF, &type);
    for (unsigned i = 0; i < nbuf; i++) if (maps[i]) munmap(maps[i], lens[i]);
    close(fd);
    return 0;

fail:
    for (unsigned i = 0; i < nbuf; i++) if (maps[i]) munmap(maps[i], lens[i]);
    // Release the buffers we requested so nothing is left allocated on the device.
    struct v4l2_requestbuffers none = {0};
    none.type = V4L2_BUF_TYPE_VIDEO_CAPTURE; none.memory = V4L2_MEMORY_MMAP;
    xioctl(fd, VIDIOC_REQBUFS, &none);
    close(fd);
    return -1;
}

// ------------------------------------------------------------------------------------------------
// Mode B: Samsung factory interface

static const char *const SEC_ALLOWED[] = {"get_x_num", "get_y_num", "run_delta_read_all"};

/** Writes one allow-listed read command and reads its result into [out]. 0 on success. */
static int sec_cmd(const char *cmd, char *out, size_t out_len, char *why, size_t why_len) {
    int allowed = 0;
    for (size_t i = 0; i < sizeof SEC_ALLOWED / sizeof SEC_ALLOWED[0]; i++)
        if (strcmp(cmd, SEC_ALLOWED[i]) == 0) allowed = 1;
    if (!allowed) { snprintf(why, why_len, "refusing non-read command %s", cmd); return -1; }

    int busy_tries = 0;
    for (;;) {
        int fd = open(SEC_DIR "/cmd", O_WRONLY);
        if (fd < 0) { snprintf(why, why_len, "open " SEC_DIR "/cmd: %s", errno_hint(errno)); return -1; }
        ssize_t w = write(fd, cmd, strlen(cmd));
        int e = errno;
        close(fd);
        if (w >= 0) break;
        // A command still running (another reader, e.g. a factory app) refuses new ones briefly.
        if ((e == EBUSY || e == EAGAIN) && ++busy_tries < 20) { usleep(5000); continue; }
        snprintf(why, why_len, "write %s: %s", cmd, errno_hint(e));
        return -1;
    }
    // Most sec_ts drivers run the command inside the write; cmd_status says when not.
    for (int i = 0; i < 100; i++) {
        char st[64] = {0};
        int sfd = open(SEC_DIR "/cmd_status", O_RDONLY);
        if (sfd < 0) break;
        ssize_t r = read(sfd, st, sizeof st - 1);
        close(sfd);
        if (r <= 0 || strstr(st, "RUNNING") == NULL) break;
        usleep(2000);
    }
    int rfd = open(SEC_DIR "/cmd_result", O_RDONLY);
    if (rfd < 0) { snprintf(why, why_len, "open cmd_result: %s", errno_hint(errno)); return -1; }
    size_t got = 0;
    for (;;) {
        ssize_t r = read(rfd, out + got, out_len - 1 - got);
        if (r < 0 && errno == EINTR) continue;
        if (r <= 0) break;
        got += (size_t)r;
        if (got >= out_len - 1) break;
    }
    close(rfd);
    out[got] = 0;
    if (sec_is_failure(out)) { snprintf(why, why_len, "%s: %.80s", cmd, out); return -1; }
    return 0;
}

static int run_sec(int max_hz, char *why, size_t why_len) {
    static char result[SEC_RESULT_MAX];
    int xn = 0, yn = 0;
    if (sec_cmd("get_x_num", result, sizeof result, why, why_len) != 0) return -1;
    if (sec_parse_scalar(result, &xn) != 0 || xn <= 0) {
        snprintf(why, why_len, "get_x_num: unparsed '%.60s'", result); return -1;
    }
    if (sec_cmd("get_y_num", result, sizeof result, why, why_len) != 0) return -1;
    if (sec_parse_scalar(result, &yn) != 0 || yn <= 0) {
        snprintf(why, why_len, "get_y_num: unparsed '%.60s'", result); return -1;
    }
    if ((size_t)xn * (size_t)yn > MAX_CELLS) { snprintf(why, why_len, "grid %dx%d too large", xn, yn); return -1; }
    size_t cells_n = (size_t)xn * (size_t)yn;
    static int16_t cells[MAX_CELLS];

    // One trial read before HELLO, so a driver that answers "OK" without data fails cleanly.
    if (sec_cmd("run_delta_read_all", result, sizeof result, why, why_len) != 0) return -1;
    size_t n = sec_parse_ints(result, cells, MAX_CELLS);
    if (n < cells_n) {
        snprintf(why, why_len, "run_delta_read_all gave %zu values, expected %dx%d: '%.60s'", n, xn, yn, result);
        return -1;
    }
    // Rows are x (get_x_num columns) wide; the driver's own order is kept, unverified on device.
    char detail[128];
    snprintf(detail, sizeof detail, "sec_delta " SEC_DIR " x_num=%d y_num=%d values=%zu", xn, yn, n);
    emit_hello(SRC_SEC, (unsigned)xn, (unsigned)yn, detail);

    int64_t period = max_hz > 0 ? 1000000000LL / max_hz : 0;
    uint32_t seq = 0;
    int64_t t = mono_ns();
    for (;;) {
        if (emit_frame(SRC_SEC, (unsigned)xn, (unsigned)yn, 0, t, 0, seq++, cells) != 0 || g_stop) break;
        int64_t wait = period - (mono_ns() - t);
        if (stdin_closed(wait > 0 ? (int)(wait / 1000000) : 0) || g_stop) break;
        if (sec_cmd("run_delta_read_all", result, sizeof result, why, why_len) != 0) {
            emit_text(REC_ERROR, "%s", why);
            break;
        }
        t = mono_ns();
        if (sec_parse_ints(result, cells, MAX_CELLS) < cells_n) {
            emit_text(REC_ERROR, "run_delta_read_all: short read '%.60s'", result);
            break;
        }
    }
    return 0;
}

int main(int argc, char **argv) {
    const char *mode = "auto";
    const char *dev = "/dev/v4l-touch0";
    int max_hz = 120;
    for (int i = 1; i < argc; i++) {
        if (strncmp(argv[i], "--mode=", 7) == 0) mode = argv[i] + 7;
        else if (strncmp(argv[i], "--device=", 9) == 0) dev = argv[i] + 9;
        else if (strncmp(argv[i], "--max-hz=", 9) == 0) max_hz = atoi(argv[i] + 9);
        else if (strcmp(argv[i], "--ignore-stdin") == 0) g_watch_stdin = 0;
    }
    struct sigaction sa = {0};
    sa.sa_handler = on_signal;
    sigaction(SIGTERM, &sa, NULL);
    sigaction(SIGINT, &sa, NULL);
    sigaction(SIGHUP, &sa, NULL);
    signal(SIGPIPE, SIG_IGN);  // A closed pipe surfaces as EPIPE from write(), handled there.

    char why_v4l2[256] = "not tried", why_sec[256] = "not tried";
    if (strcmp(mode, "sec") != 0) {
        if (run_v4l2(dev, why_v4l2, sizeof why_v4l2) == 0) return 0;
        if (strcmp(mode, "v4l2") == 0) { emit_text(REC_ERROR, "v4l2: %s", why_v4l2); return 2; }
        emit_text(REC_INFO, "v4l2 unavailable: %s; trying sec_delta", why_v4l2);
    }
    if (g_stop) return 0;
    if (run_sec(max_hz, why_sec, sizeof why_sec) == 0) return 0;
    emit_text(REC_ERROR, "v4l2: %s; sec_delta: %s", why_v4l2, why_sec);
    return 2;
}
