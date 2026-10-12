// DroidPad UDP -> uhid bridge.
//
// Receives the HID report descriptor (handshake) and packed input reports over
// UDP from the droidpad app, and feeds them into /dev/uhid so the kernel /
// hid-multitouch / libinput see a real touchpad. No Bluetooth involved.
//
// Wire format:
//   0x01 HELLO: [1][u16 descriptorLen LE][descriptor...]
//   0x02 INPUT: [2][report payload...]      (report id = 1)
//
// Build:  cc -O2 -o uhid_udp uhid_udp.c
// Run:    sudo ./uhid_udp [port]      (default 9999; needs /dev/uhid access)
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <stdint.h>
#include <unistd.h>
#include <errno.h>
#include <fcntl.h>
#include <sys/select.h>
#include <sys/socket.h>
#include <netinet/in.h>
#include <arpa/inet.h>
#include <linux/uhid.h>

#define REPORT_ID 1
#define BUF 4096

static int uhid_fd = -1;

static void create_device(int fd, const unsigned char *desc, int dlen) {
    struct uhid_event ev;
    if (uhid_fd >= 0) {
        memset(&ev, 0, sizeof(ev));
        ev.type = UHID_DESTROY;
        write(uhid_fd, &ev, sizeof(ev));
        uhid_fd = -1;
    }
    memset(&ev, 0, sizeof(ev));
    ev.type = UHID_CREATE2;
    strcpy((char *)ev.u.create2.name, "DroidPad (UDP)");
    ev.u.create2.bus = BUS_BLUETOOTH;
    ev.u.create2.vendor = 0x1234;
    ev.u.create2.product = 0x5678;
    ev.u.create2.version = 1;
    ev.u.create2.rd_size = dlen;
    memcpy(ev.u.create2.rd_data, desc, dlen);
    if (write(fd, &ev, sizeof(ev)) < 0) {
        perror("UHID_CREATE2");
        return;
    }
    uhid_fd = fd;
    fprintf(stderr, "created HID device from %d-byte descriptor\n", dlen);
}

static void input_report(int fd, const unsigned char *payload, int len) {
    struct uhid_event ev;
    if (len <= 0) return;
    memset(&ev, 0, sizeof(ev));
    ev.type = UHID_INPUT2;
    ev.u.input2.data[0] = REPORT_ID;               // numbered report
    int n = len < (int)sizeof(ev.u.input2.data) - 1 ? len : (int)sizeof(ev.u.input2.data) - 1;
    memcpy(&ev.u.input2.data[1], payload, n);
    ev.u.input2.size = 1 + n;
    if (write(fd, &ev, sizeof(ev)) < 0) perror("UHID_INPUT2");
}

static void handle_uhid(int fd) {
    struct uhid_event ev;
    if (read(fd, &ev, sizeof(ev)) < 0) return;
    switch (ev.type) {
    case UHID_START:
        fprintf(stderr, "UHID_START\n");
        break;
    case UHID_OPEN:
        fprintf(stderr, "UHID_OPEN\n");
        break;
    case UHID_GET_REPORT: {
        struct uhid_event r;
        memset(&r, 0, sizeof(r));
        r.type = UHID_GET_REPORT_REPLY;
        r.u.get_report_reply.id = ev.u.get_report.id;
        r.u.get_report_reply.err = 0;
        r.u.get_report_reply.size = 0;
        write(fd, &r, sizeof(r));
        break;
    }
    default:
        break;
    }
}

int main(int argc, char **argv) {
    int port = argc > 1 ? atoi(argv[1]) : 9999;

    int ufd = open("/dev/uhid", O_RDWR | O_CLOEXEC);
    if (ufd < 0) { perror("open /dev/uhid (need root or udev rule)"); return 1; }

    int s = socket(AF_INET, SOCK_DGRAM, 0);
    if (s < 0) { perror("socket"); return 1; }
    int one = 1;
    setsockopt(s, SOL_SOCKET, SO_REUSEADDR, &one, sizeof(one));
    struct sockaddr_in a;
    memset(&a, 0, sizeof(a));
    a.sin_family = AF_INET;
    a.sin_addr.s_addr = htonl(INADDR_ANY);
    a.sin_port = htons(port);
    if (bind(s, (struct sockaddr *)&a, sizeof(a)) < 0) { perror("bind"); return 1; }
    fprintf(stderr, "listening on udp/%d, feeding /dev/uhid\n", port);

    unsigned char buf[BUF];
    for (;;) {
        fd_set rfds;
        FD_ZERO(&rfds);
        FD_SET(s, &rfds);
        int maxfd = s;
        if (uhid_fd >= 0) { FD_SET(uhid_fd, &rfds); if (uhid_fd > maxfd) maxfd = uhid_fd; }

        int r = select(maxfd + 1, &rfds, NULL, NULL, NULL);
        if (r < 0) { if (errno == EINTR) continue; perror("select"); break; }

        if (uhid_fd >= 0 && FD_ISSET(uhid_fd, &rfds)) handle_uhid(uhid_fd);

        if (FD_ISSET(s, &rfds)) {
            struct sockaddr_in peer;
            socklen_t pl = sizeof(peer);
            ssize_t n = recvfrom(s, buf, sizeof(buf), 0, (struct sockaddr *)&peer, &pl);
            if (n <= 0) continue;
            if (buf[0] == 0x01 && n >= 3) {
                int dlen = buf[1] | (buf[2] << 8);
                if (3 + dlen <= n) {
                    if (uhid_fd < 0) {
                        fprintf(stderr, "HELLO from %s (%d byte descriptor)\n",
                                inet_ntoa(peer.sin_addr), dlen);
                        create_device(ufd, buf + 3, dlen);
                    }
                }
            } else if (buf[0] == 0x02 && n > 1) {
                if (uhid_fd >= 0) input_report(ufd, buf + 1, (int)n - 1);
            }
        }
    }
    close(s);
    close(ufd);
    return 0;
}
