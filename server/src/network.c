#include "../include/network.h"
#include "../include/room.h"
#include <string.h>
#include <stdarg.h>
#include <sys/socket.h>

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
    // if (strcmp(fmt, "PING") != 0) {
        printf("Sent: %s \n", fmt);
    // }

    return (written == (ssize_t)to_write) ? 0 : -1;
}

int send_broadcast_about_new_room(const char *text) {
    for (int i = 0; i < MAX_CLIENTS; i++) {
        if (clients[i] != NULL && clients[i]->state == ST_AUTH) {
            send_line(clients[i]->fd, "%s", text);
        }
    }
    return 0;
}

int send_room_list(int fd) {
    int count = 0;
    for (int i = 0; i < MAX_ROOMS; i++)
        if (rooms[i].id != 0) count++;

    send_line(fd, "ROOM_LIST %d", count);
    for (int i = 0; i < MAX_ROOMS; i++) {
        if (rooms[i].id == 0) continue;
        send_line(fd, "ROOM %d %s %d/2 %s",
                  rooms[i].id, rooms[i].name,
                  rooms[i].player_count,
                  get_room_state_name(rooms[i].state));
    }
    return 0;
}

