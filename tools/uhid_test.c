// Local validator: creates a uhid HID device from DroidPad's report descriptor
// and drives synthetic multi-touch input reports, so we can verify the kernel
// binds hid-multitouch and libinput sees a touchpad — without a phone.
//
// Needs access to /dev/uhid (root or a udev rule). Build:
//   cc -O2 -o uhid_test uhid_test.c
// Run (in one terminal):  sudo ./uhid_test [descriptor.hex]
// Inspect (other terminal): libinput list-devices | evtest | libinput debug-events
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <fcntl.h>
#include <unistd.h>
#include <errno.h>
#include <time.h>
#include <linux/uhid.h>
#include <math.h>

#define REPORT_BYTES 27
#define MAXC 5

static int readhex(const char *path, unsigned char *out, int max) {
    FILE *f = fopen(path, "r");
    if (!f) { perror(path); exit(1); }
    int n = 0, c1, c2;
    while ((c1 = fgetc(f)) != EOF && n < max) {
        if (c1==' '||c1=='\n'||c1=='\r'||c1=='\t') continue;
        c2 = fgetc(f);
        char b[3] = {(char)c1,(char)c2,0};
        out[n++] = (unsigned char)strtol(b, NULL, 16);
    }
    fclose(f);
    return n;
}

static void send_report(int fd, unsigned char *rep, int len) {
    struct uhid_event ev;
    memset(&ev, 0, sizeof(ev));
    ev.type = UHID_INPUT2;
    ev.u.input2.size = 1 + len;
    ev.u.input2.data[0] = 0x01;              // report number
    memcpy(&ev.u.input2.data[1], rep, len);  // payload
    if (write(fd, &ev, sizeof(ev)) < 0) perror("write UHID_INPUT2");
}

static void set_finger(unsigned char *r, int i, int down, int cid, int x, int y) {
    int o = i*5;
    r[o]   = (unsigned char)((down?1:0) | ((cid&0x0F)<<4));
    r[o+1] = x & 0xFF; r[o+2] = (x>>8)&0xFF;
    r[o+3] = y & 0xFF; r[o+4] = (y>>8)&0xFF;
}

static void msleep(int ms) { struct timespec ts={ms/1000,(long)(ms%1000)*1000000L}; nanosleep(&ts,NULL); }

int main(int argc, char **argv) {
    const char *hexpath = argc > 1 ? argv[1] : "descriptor.hex";
    unsigned char desc[4096];
    int dlen = readhex(hexpath, desc, sizeof(desc));
    fprintf(stderr, "descriptor: %d bytes\n", dlen);

    int fd = open("/dev/uhid", O_RDWR);
    if (fd < 0) { perror("open /dev/uhid (need root or udev rule)"); return 1; }

    struct uhid_event ev;
    memset(&ev, 0, sizeof(ev));
    ev.type = UHID_CREATE2;
    strcpy((char*)ev.u.create2.name, "DroidPad (test)");
    ev.u.create2.bus = BUS_BLUETOOTH;
    ev.u.create2.vendor = 0x1234;
    ev.u.create2.product = 0x5678;
    ev.u.create2.version = 1;
    ev.u.create2.country = 0;
    ev.u.create2.rd_size = dlen;
    memcpy(ev.u.create2.rd_data, desc, dlen);
    if (write(fd, &ev, sizeof(ev)) < 0) { perror("UHID_CREATE2"); return 1; }
    fprintf(stderr, "created uhid device; waiting for START...\n");

    int started = 0;
    for (;;) {
        struct uhid_event e;
        ssize_t n = read(fd, &e, sizeof(e));
        if (n < 0) { perror("read"); return 1; }
        if (e.type == UHID_START) {
            fprintf(stderr, "UHID_START (dev_flags=0x%llx)\n", (unsigned long long)e.u.start.dev_flags);
            started = 1; break;
        } else if (e.type == UHID_OPEN) {
            fprintf(stderr, "UHID_OPEN\n");
        } else {
            fprintf(stderr, "event %d before start\n", e.type);
        }
    }
    if (!started) return 1;

    unsigned char r[REPORT_BYTES];
    for (int loop = 0; loop < 60; loop++) {
        // one finger circling
        for (int a = 0; a < 120; a++) {
            memset(r, 0, sizeof(r));
            int x = 16384 + (int)(12000.0 * cos(a*0.10));
            int y = 16384 + (int)(12000.0 * sin(a*0.10));
            set_finger(r, 0, 1, 0, x, y);
            r[25] = 1; r[26] = 0;
            send_report(fd, r, REPORT_BYTES);
            msleep(8);
        }
        // two-finger pinch out/in
        for (int a = 0; a < 120; a++) {
            memset(r, 0, sizeof(r));
            int d = 4000 + (int)(3000.0 * (1.0 + sin(a*0.10)));
            set_finger(r, 0, 1, 0, 16384-d, 16384);
            set_finger(r, 1, 1, 1, 16384+d, 16384);
            r[25] = 2; r[26] = 0;
            send_report(fd, r, REPORT_BYTES);
            msleep(8);
        }
        // release
        memset(r, 0, sizeof(r));
        r[25] = 0; r[26] = 0;
        send_report(fd, r, REPORT_BYTES);
        msleep(300);
        fprintf(stderr, "loop %d\n", loop);
    }
    close(fd);
    return 0;
}
