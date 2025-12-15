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
        c->timeout_state = CONNECTED;
        gen_token(c->token);

        if (register_client(c) != 0) {
            send_line(connfd, "ERR 200 SERVER_FULL");
            close(connfd);
            free(c);
            continue;
        }

        if (pthread_create(&c->thread, NULL, client_worker, c) != 0) {
            perror("pthread_create");
            send_line(connfd, "ERR 500 SERVER_ERROR");
            unregister_client(c);
            close(connfd);
            free(c);
            continue;
        }
        pthread_detach(c->thread);
    }
}

// Dedicated timeout checker thread
void *room_timeout_worker(void *arg) {
    (void)arg;

    // Sleep granularity ~200ms to fire close to the 30s mark
    const struct timespec interval = { .tv_sec = 0, .tv_nsec = 200 * 1000 * 1000 };

    for (;;) {
        nanosleep(&interval, NULL);  // Сначала спим, чтобы не блокировать сразу

        pthread_mutex_lock(&global_lock);
        check_rooms();     // проверка на таймаут комнат (если кто-то не ходит)
        check_clients();   // проверка на таймаут клиентов
        pthread_mutex_unlock(&global_lock);
    }
    return NULL;
}

void *client_worker(void *arg) {
    client_t *c = arg;
    char buf[LINE_BUF];
    FILE *f = fdopen(c->fd, "r+");
    if (!f) {
        perror("fdopen");
        send_line(c->fd, "ERR 500 SERVER_ERROR");
        close(c->fd);
        unregister_client(c);
        free(c);
        return NULL;
    }
    setvbuf(f, NULL, _IOLBF, 0);

    while (fgets(buf, sizeof(buf), f) != NULL) {
        handle_line(c, buf);
    }

    fprintf(stderr, "Client %s fd:%d disconnected\n", c->nick, c->fd);
    close(c->fd);
    pthread_mutex_lock(&global_lock);
    process_client_hard_disconnection(c);
    unregister_client_without_lock(c);
    pthread_mutex_unlock(&global_lock);
    free(c);
    return NULL;
}

void process_client_hard_disconnection(client_t *c) {
    switch (c->state) {
        case ST_IN_LOBBY:
        case ST_READY:
            room_t *r = find_room_by_id(c->room_id);
            if (r && !was_replaced(r,c)) {
                printf("Removing player %s fd%d from room %s\n", c->nick, c->fd,r->name);
                remove_player_from_room(c, r);
            }
            break;

        case ST_PLAYING:
            room_t *room = find_room_by_id(c->room_id);
            if (room && !was_replaced(room, c)) {
                client_t *opponent = get_opponent_in_room(room, c);
                if (opponent) {
                    send_line(opponent->fd, "GAME_END opponent_left");
                    opponent->state = ST_AUTH;
                    opponent->room_id = -1;
                }
                remove_room(room);
            }
            break;
        default:
            break;
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


void check_clients(void) {
    time_t now = time(NULL);

    for (int i = 0; i < MAX_CLIENTS; i++) {
        client_t *c = clients[i];
        if (!c) continue;

        // 1) Если давно ничего не получали → клиент завис/отвалился
        if (now - c->last_seen >= CLIENT_TIMEOUT_SOFT && c->timeout_state == CONNECTED) {
            fprintf(stderr, "Client soft timeout: %s\n", c->nick);
            c->timeout_state = SOFT_TIMEOUT;
            process_client_timeout(c);
            continue;
        }

        // 2) Hard отключение клиента с удалением его
        if (now - c->last_seen >= CLIENT_TIMEOUT_HARD && c->timeout_state == SOFT_TIMEOUT) {
            fprintf(stderr, "Client hard timeout: %s\n", c->nick);
            shutdown(c->fd, SHUT_RDWR);
            continue;
        }

        // 3) Отправляем PING раз в PING_INTERVAL секунд
        if (now - c->last_ping_sent >= PING_INTERVAL && c->timeout_state == CONNECTED) {
            send_line(c->fd, "PING");
            c->last_ping_sent = now;
        }
    }
}

void process_client_timeout(client_t *c) {
    if (c == NULL) return;
    switch (c->state) {
        case ST_IN_LOBBY:
        case ST_READY:
            room_t *r = find_room_by_id(c->room_id);
            if (r == NULL) break;
            c->state = ST_IN_LOBBY;
            client_t *opponent = get_opponent_in_room(r, c);
            if (opponent) {
                const char *status = (opponent->state == ST_READY) ? "READY" : "NOT_READY";
                send_line(opponent->fd, "OPPONENT_INFO %s %s", c->nick, status);
            }
            break;
        case ST_PLAYING:

            room_t *room = find_room_by_id(c->room_id);

            room->state = RM_PAUSED;
            room->awaiting_moves = 0;

            client_t *opp = (room->player1 == c) ? room->player2 : room->player1;
            if (opp) {
                send_line(opp->fd, "GAME_PAUSED");
            }
            fprintf(stderr, "Game paused in room %d\n", room->id);
            break;
        default:
            break;
    }
}