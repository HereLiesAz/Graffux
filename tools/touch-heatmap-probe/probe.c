// Read-only probe for the Pixel touch heatmap (/dev/v4l-touch0, V4L2 "heatmap").
// Prints capabilities and format, then tries to stream a few frames and dumps them as grids.
// Changes nothing on the device: no controls are set, and the format is only queried.
//
//   adb push probe /data/local/tmp/ && adb shell su -c /data/local/tmp/probe [/dev/v4l-touch0] [frames]
#include <errno.h>
#include <fcntl.h>
#include <linux/videodev2.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/mman.h>
#include <poll.h>
#include <unistd.h>

static int xioctl(int fd, unsigned long req, void *arg) {
    int r;
    do { r = ioctl(fd, req, arg); } while (r == -1 && errno == EINTR);
    return r;
}

int main(int argc, char **argv) {
    const char *dev = argc > 1 ? argv[1] : "/dev/v4l-touch0";
    int frames = argc > 2 ? atoi(argv[2]) : 5;
    int fd = open(dev, O_RDWR | O_NONBLOCK);
    if (fd < 0) { printf("open %s: %s\n", dev, strerror(errno)); return 1; }

    struct v4l2_capability cap = {0};
    if (xioctl(fd, VIDIOC_QUERYCAP, &cap) == 0)
        printf("driver=%s card=%s bus=%s caps=0x%08x devcaps=0x%08x\n",
               cap.driver, cap.card, cap.bus_info, cap.capabilities, cap.device_caps);
    else printf("QUERYCAP: %s\n", strerror(errno));

    struct v4l2_input in = {0};
    for (in.index = 0; xioctl(fd, VIDIOC_ENUMINPUT, &in) == 0; in.index++)
        printf("input %u: %s type=%u\n", in.index, in.name, in.type);

    struct v4l2_fmtdesc fd_desc = {0};
    fd_desc.type = V4L2_BUF_TYPE_VIDEO_CAPTURE;
    for (; xioctl(fd, VIDIOC_ENUM_FMT, &fd_desc) == 0; fd_desc.index++) {
        uint32_t f = fd_desc.pixelformat;
        printf("fmt %u: %c%c%c%c %s\n", fd_desc.index, f & 0xff, (f >> 8) & 0xff, (f >> 16) & 0xff,
               (f >> 24) & 0xff, fd_desc.description);
    }

    struct v4l2_format fmt = {0};
    fmt.type = V4L2_BUF_TYPE_VIDEO_CAPTURE;
    if (xioctl(fd, VIDIOC_G_FMT, &fmt) != 0) { printf("G_FMT: %s\n", strerror(errno)); return 1; }
    uint32_t pf = fmt.fmt.pix.pixelformat;
    unsigned w = fmt.fmt.pix.width, h = fmt.fmt.pix.height, bpl = fmt.fmt.pix.bytesperline;
    printf("format %ux%u %c%c%c%c bytesperline=%u sizeimage=%u\n", w, h, pf & 0xff, (pf >> 8) & 0xff,
           (pf >> 16) & 0xff, (pf >> 24) & 0xff, bpl, fmt.fmt.pix.sizeimage);

    struct v4l2_requestbuffers req = {0};
    req.count = 2; req.type = V4L2_BUF_TYPE_VIDEO_CAPTURE; req.memory = V4L2_MEMORY_MMAP;
    if (xioctl(fd, VIDIOC_REQBUFS, &req) != 0) {
        printf("REQBUFS: %s  (EBUSY = another process, likely system_server, is streaming it)\n",
               strerror(errno));
        return 2;
    }
    void *maps[8] = {0}; size_t lens[8] = {0};
    for (unsigned i = 0; i < req.count && i < 8; i++) {
        struct v4l2_buffer b = {0};
        b.type = req.type; b.memory = req.memory; b.index = i;
        if (xioctl(fd, VIDIOC_QUERYBUF, &b) != 0) { printf("QUERYBUF: %s\n", strerror(errno)); return 3; }
        lens[i] = b.length;
        maps[i] = mmap(NULL, b.length, PROT_READ | PROT_WRITE, MAP_SHARED, fd, b.m.offset);
        if (maps[i] == MAP_FAILED) { printf("mmap: %s\n", strerror(errno)); return 3; }
        xioctl(fd, VIDIOC_QBUF, &b);
    }
    enum v4l2_buf_type type = V4L2_BUF_TYPE_VIDEO_CAPTURE;
    if (xioctl(fd, VIDIOC_STREAMON, &type) != 0) { printf("STREAMON: %s\n", strerror(errno)); return 4; }
    printf("streaming; touch the screen\n");

    for (int n = 0; n < frames;) {
        struct pollfd p = {fd, POLLIN, 0};
        if (poll(&p, 1, 5000) <= 0) { printf("no frame within 5 s (heatmap may only flow while touched)\n"); continue; }
        struct v4l2_buffer b = {0};
        b.type = type; b.memory = V4L2_MEMORY_MMAP;
        if (xioctl(fd, VIDIOC_DQBUF, &b) != 0) { if (errno == EAGAIN) continue; printf("DQBUF: %s\n", strerror(errno)); break; }
        printf("frame %d seq=%u t=%ld.%06ld bytes=%u\n", n, b.sequence, (long)b.timestamp.tv_sec,
               (long)b.timestamp.tv_usec, b.bytesused);
        const int16_t *px = (const int16_t *)maps[b.index];   // heatmaps are 16-bit signed deltas
        unsigned stride = bpl ? bpl / 2 : w;
        for (unsigned y = 0; y < h; y++) {
            for (unsigned x = 0; x < w; x++) printf("%5d", px[y * stride + x]);
            printf("\n");
        }
        xioctl(fd, VIDIOC_QBUF, &b);
        n++;
    }
    xioctl(fd, VIDIOC_STREAMOFF, &type);
    for (unsigned i = 0; i < req.count && i < 8; i++) if (maps[i]) munmap(maps[i], lens[i]);
    close(fd);
    return 0;
}
