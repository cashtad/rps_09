#include "../include/send_line.h"


extern pthread_mutex_t global_lock;
extern client_t *clients[MAX_CLIENTS];
extern room_t rooms[MAX_ROOMS];

void trim_crlf(char *s) {
    size_t n = strlen(s);
    while (n > 0 && (s[n-1] == '\r' || s[n-1] == '\n')) {
        s[n-1] = '\0';
        n--;
    }
}

int send_line(const int fd, const char *fmt, ...) {
    char buf[LINE_BUF];
    va_list ap;
    va_start(ap, fmt);
    vsnprintf(buf, sizeof(buf), fmt, ap);
    va_end(ap);
    strncat(buf, "\r\n", sizeof(buf) - strlen(buf) - 1);
    size_t to_write = strlen(buf);
    ssize_t written = send(fd, buf, to_write, 0);

    return (written == (ssize_t)to_write) ? 0 : -1;
}
