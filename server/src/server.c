// server/src/server.c
#define _GNU_SOURCE

#include "../include/server.h"
#include "../include/client.h"
#include "../include/room.h"
#include "../include/network.h"
#include "../include/commands.h"

pthread_mutex_t global_lock = PTHREAD_MUTEX_INITIALIZER;
client_t *clients[MAX_CLIENTS];
room_t rooms[MAX_ROOMS];

// Dedicated timeout checker thread
static void *room_timeout_worker(void *arg) {
    (void)arg;

    // Sleep granularity ~200ms to fire close to the 30s mark
    const struct timespec interval = { .tv_sec = 0, .tv_nsec = 200 * 1000 * 1000 };

    for (;;) {
        pthread_mutex_lock(&global_lock);
        check_rooms();
        pthread_mutex_unlock(&global_lock);

        nanosleep(&interval, NULL);
    }
    return NULL;
}

void *client_worker(void *arg) {
    client_t *c = arg;
    char buf[LINE_BUF];
    FILE *f = fdopen(c->fd, "r+");
    if (!f) {
        perror("fdopen");
        close(c->fd);
        unregister_client(c);
        free(c);
        return NULL;
    }
    setvbuf(f, NULL, _IOLBF, 0);

    while (fgets(buf, sizeof(buf), f) != NULL) {
        c->last_seen = time(NULL);
        handle_line(c, buf);
    }

    fprintf(stderr, "Client %s disconnected\n", c->nick);
    close(c->fd);
    unregister_client(c);
    free(c);
    return NULL;
}

int main(int argc, char **argv) {
    const char *port = "2500";
    if (argc >= 2) port = argv[1];

    struct sockaddr_in servaddr;
    int listen_fd = socket(AF_INET, SOCK_STREAM, 0);
    if (listen_fd < 0) { perror("socket"); exit(1); }

    int opt = 1;
    setsockopt(listen_fd, SOL_SOCKET, SO_REUSEADDR, &opt, sizeof(opt));

    memset(&servaddr, 0, sizeof(servaddr));
    servaddr.sin_family = AF_INET;
    servaddr.sin_addr.s_addr = INADDR_ANY;
    servaddr.sin_port = htons(atoi(port));

    if (bind(listen_fd, (struct sockaddr*)&servaddr, sizeof(servaddr)) < 0) {
        perror("bind"); exit(1);
    }
    if (listen(listen_fd, LISTEN_BACKLOG) < 0) {
        perror("listen"); exit(1);
    }

    fprintf(stderr, "Server listening on 0.0.0.0:%s\n", port);

    for (int i = 0; i < MAX_CLIENTS; i++) clients[i] = NULL;
    init_rooms();

    // Start independent timeout monitor
    pthread_t timer_thread;
    if (pthread_create(&timer_thread, NULL, room_timeout_worker, NULL) != 0) {
        perror("pthread_create(timer_thread)");
        exit(1);
    }
    pthread_detach(timer_thread);

    while (1) {
        struct sockaddr_in cliaddr;
        socklen_t clilen = sizeof(cliaddr);
        int connfd = accept(listen_fd, (struct sockaddr*)&cliaddr, &clilen);
        if (connfd < 0) {
            perror("accept");
            continue;
        }

        fprintf(stderr, "New connection fd=%d\n", connfd);
        client_t *c = calloc(1, sizeof(client_t));
        c->fd = connfd;
        c->state = ST_CONNECTED;
        c->room_id = -1;
        c->last_seen = time(NULL);
        gen_token(c->token);

        if (register_client(c) != 0) {
            send_line(connfd, "ERR 200 SERVER_FULL");
            close(connfd);
            free(c);
            continue;
        }

        if (pthread_create(&c->thread, NULL, client_worker, c) != 0) {
            perror("pthread_create");
            unregister_client(c);
            close(connfd);
            free(c);
            continue;
        }
        pthread_detach(c->thread);
    }
}

// Fires exactly at TIMEOUT seconds since round start (no extra 1s delay)
void check_rooms(void) {
    time_t now = time(NULL);

    for (int i = 0; i < MAX_ROOMS; i++) {
        room_t *r = &rooms[i];

        if (r->state == RM_PLAYING && r->awaiting_moves) {
            if (now - r->round_start_time >= ROUND_TIMEOUT) {
                handle_round_timeout(r);
            }
        }
    }
}
