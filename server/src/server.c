// server.c
// Minimal TCP server skeleton for RPS bo9 project.
// - accept connections
// - parse simple line-based protocol (CRLF terminated)
// - implement HELLO, LIST, CREATE, JOIN (basic)
// - thread-per-client model, global mutex for rooms/clients

#define GNU_SOURCE

#include "../include/server.h"



static pthread_mutex_t global_lock = PTHREAD_MUTEX_INITIALIZER;
static client_t *clients[MAX_CLIENTS];
static room_t rooms[MAX_ROOMS];
static int next_room_id = 1;

const char* get_room_state_name(room_state_t room_state) {
    switch (room_state) {
        case RM_OPEN: return "OPEN";
        case RM_FULL: return "FULL";
        case RM_PLAYING: return "PLAYING";
        case RM_PAUSED: return "PAUSED";
    }
    return "";
}


/* utility: trim CRLF */ // trims \r\n at the end of the line
static void trim_crlf(char *s) {
    size_t n = strlen(s);
    while (n>0 && (s[n-1] == '\r' || s[n-1] == '\n')) { s[n-1] = '\0'; n--; }
}

/* send a line (adds CRLF) */ // adds \r\n at the end of the line
static int send_line(int fd, const char *fmt, ...) {
    char buf[LINE_BUF];
    va_list ap;
    va_start(ap, fmt);
    vsnprintf(buf, sizeof(buf), fmt, ap);
    va_end(ap);
    strncat(buf, "\r\n", sizeof(buf)-strlen(buf)-1);
    size_t to_write = strlen(buf);
    ssize_t written = send(fd, buf, to_write, 0);
    return (written == (ssize_t)to_write) ? 0 : -1;
}

/* generate simple token */
static void gen_token(char *out) {
    const char *hex = "0123456789abcdef";
    srand((unsigned)time(NULL) ^ (uintptr_t)pthread_self());
    uint8_t outlen= TOKEN_LEN;
    for (size_t i=0;i<30 && i+1<outlen;i++) out[i] = hex[rand() % 16];
    out[30 < outlen ? 30 : outlen-1] = '\0';
}

/* find free client slot */
static int register_client(client_t *c) {
    pthread_mutex_lock(&global_lock);
    for (int i=0;i<MAX_CLIENTS;i++) {
        if (clients[i] == NULL) {
            clients[i] = c;
            pthread_mutex_unlock(&global_lock);
            return 0;
        }
    }
    pthread_mutex_unlock(&global_lock);
    return -1;
}

static void unregister_client(const client_t *c) {
    pthread_mutex_lock(&global_lock);
    for (int i=0;i<MAX_CLIENTS;i++) {
        if (clients[i] == c) {
            clients[i] = NULL;
            break;
        }
    }
    pthread_mutex_unlock(&global_lock);
}


// =================== FINDERS ===================

/* find room by id */
static room_t* find_room_by_id(int id) {
    for (int i=0;i<MAX_ROOMS;i++) {
        if (rooms[i].id == id) return &rooms[i];
    }
    return NULL;
}

static room_t* find_room_by_player_fd(const int fd) {
    for (int i=0; i < MAX_ROOMS; i++) {
        if (rooms[i].player1->fd == fd || rooms[i].player2->fd == fd) return &rooms[i];
    }
    return NULL;
}

static client_t* find_client_by_fd(const int fd) {
    for (int i = 0; i < MAX_CLIENTS; i++) {
        if (clients[i] != NULL && clients[i]->fd == fd) return clients[i];
    }
    return NULL;
}

/* create room */
static int create_room(const char *name) {
    for (int i=0;i<MAX_ROOMS;i++) {
        if (rooms[i].id == 0) {
            rooms[i].id = next_room_id++;
            strncpy(rooms[i].name, name, ROOM_NAME_MAX);
            rooms[i].name[ROOM_NAME_MAX] = '\0';
            rooms[i].player1 = rooms[i].player2 = NULL;
            rooms[i].player_count = 0;
            rooms[i].state = RM_OPEN;
            int id = rooms[i].id;
            pthread_mutex_unlock(&global_lock);
            return id;
        }
    }
    return -1;
}

static int send_welcome_message(client_t *c, const char* nick) {
    strncpy(c->nick, nick, NICK_MAX);
    c->nick[NICK_MAX] = '\0';
    gen_token(c->token);
    c->state = ST_AUTH;
    send_line(c->fd, "WELCOME %s", c->token);
    return 0;
}

/* list rooms: caller must hold no locks */
static int send_room_list(int fd) {
    int count = 0;
    for (int i=0;i<MAX_ROOMS;i++) if (rooms[i].id != 0) count++;
    send_line(fd, "ROOM_LIST %d", count);
    for (int i=0;i<MAX_ROOMS;i++) {
        if (rooms[i].id == 0) continue;
        send_line(fd, "ROOM %d %s %d/2 %s", rooms[i].id, rooms[i].name, rooms[i].player_count, get_room_state_name(rooms[i].state));
    }
    return 0;
}

static int send_broadcast(char* text) {

    for (uint8_t i=0;i<MAX_CLIENTS;i++) {
        if (clients[i] != NULL) {
            send_line(clients[i]->fd, "%s", text);
        }
    }

    return 0;
}

static int add_player_to_the_room(client_t *c, room_t* r) {
    printf("%d %d", c->fd, r->id);
    // add player
    if (r-> player1 == NULL) {
        r->player1 = c;
    }
    else r->player2 = c;
    r->player_count++;
    if (r->player_count == MAX_CLIENTS) r->state = RM_FULL;
    c->room_id = r->id;
    c->state = ST_IN_LOBBY;
    send_line(c->fd, "ROOM_JOINED %d", r->id);

    return 0;
}

/* parse a single line and handle */
static void handle_line(client_t *c, char *line) {
    trim_crlf(line);
    if (strlen(line) == 0) return;
    // tokenize
    char *cmd = strtok(line, " ");
    if (!cmd) return;
    if (strcmp(cmd, "HELLO") == 0) {
        pthread_mutex_lock(&global_lock);

        char *nick = strtok(NULL, " ");
        if (!nick) { send_line(c->fd, "ERR 100 BAD_FORMAT missing_nick"); pthread_mutex_unlock(&global_lock); return; }
        send_welcome_message(c, nick);
        pthread_mutex_unlock(&global_lock);

    } else if (strcmp(cmd, "LIST") == 0) {
        pthread_mutex_lock(&global_lock);

        if (c->state != ST_AUTH) { send_line(c->fd, "ERR 101 INVALID_STATE not_auth"); pthread_mutex_unlock(&global_lock); return; }
        send_room_list(c->fd);
        pthread_mutex_unlock(&global_lock);

    } else if (strcmp(cmd, "CREATE") == 0) {
        pthread_mutex_lock(&global_lock);
        if (c->state != ST_AUTH) { send_line(c->fd, "ERR 101 INVALID_STATE"); pthread_mutex_unlock(&global_lock); return; }
        char *rname = strtok(NULL, " ");
        if (!rname) { send_line(c->fd, "ERR 100 BAD_FORMAT missing_room_name"); pthread_mutex_unlock(&global_lock); return; }
        int rid = create_room(rname);
        if (rid < 0) { send_line(c->fd, "ERR 200 SERVER_FULL"); pthread_mutex_unlock(&global_lock); return; }

        char buf[LINE_BUF];
        snprintf(buf, LINE_BUF, "ROOM_CREATED %d", rid);
        send_broadcast(buf);
        pthread_mutex_unlock(&global_lock);

    } else if (strcmp(cmd, "JOIN") == 0) {
        if (c->state != ST_AUTH) {
            send_line(c->fd, "ERR 101 INVALID_STATE");
            return;
        }
        char *idstr = strtok(NULL, " ");
        if (!idstr) { send_line(c->fd, "ERR 100 BAD_FORMAT missing_room_id"); return; }
        int rid = atoi(idstr);

        pthread_mutex_lock(&global_lock);

        room_t *r = find_room_by_id(rid);
        if (!r) {
            pthread_mutex_unlock(&global_lock);
            send_line(c->fd, "ERR 104 UNKNOWN_ROOM");
            return;
        }

        if (r->state != RM_OPEN) {
            pthread_mutex_unlock(&global_lock);
            send_line(c->fd, "ERR 106 ROOM_WRONG_STATE");
            return;
        }

        add_player_to_the_room(c,r);

        if (r->player_count == 1) pthread_mutex_unlock(&global_lock); return;

        if (r->player1 != c) {send_line(r->player1->fd, "PLAYER_JOINED %s", c->nick);}
        else {send_line(r->player2->fd, "PLAYER_JOINED %s", c->nick);}
        pthread_mutex_unlock(&global_lock);


    } else if (strcmp(cmd, "READY") == 0) {
        pthread_mutex_lock(&global_lock);
        c->state = ST_READY;
        room_t *r = find_room_by_id(c->room_id);
        if (r->player_count == 1) pthread_mutex_unlock(&global_lock); return;

        client_t* opponent;
        if (r->player1 != c) opponent = r->player1;
        else opponent = r->player2;

        if (opponent->state == ST_READY) {
            r->state = RM_PLAYING;
        } else {
            send_line(opponent->fd, "PLAYER_READY %s", c->nick);
        }
        pthread_mutex_unlock(&global_lock);

    } else if (strcmp(cmd, "UNREADY") == 0) {


    } else if (strcmp(cmd, "LEAVE") == 0) {
        pthread_mutex_lock(&global_lock);
        room_t *r = find_room_by_id(c->room_id);
        if (!r) {
            pthread_mutex_unlock(&global_lock);
            send_line(c->fd, "ERR 104 UNKNOWN_ROOM");
            return;
        }
        // проверка на ошибки
        if (r->state != RM_FULL || r->state != RM_OPEN) {
            pthread_mutex_unlock(&global_lock);
            send_line(c->fd, "ERR 101 INVALID_STATE cannot_leave_now");
            return;
        }

        if (c->state != ST_IN_LOBBY) {
            pthread_mutex_unlock(&global_lock);
            send_line(c->fd, "ERR 101 INVALID_STATE cannot_leave_now");
            return;
        }

        // выход из комнаты, т.е из комнаты убрать игрока, из игрока убрать комнату
        if (r->player_count > 1) {
            if (r->player1 == c) {
                r->player1 = r->player2;
                r->player2 = NULL;
                // отправить оповещение второму игроку, если он есть в комнате
                send_line(r->player1->fd, "PLAYER_LEFT %s", c->nick);
            } else {
                r->player2 = NULL;
            }
        }
        r->player_count--;
        r->state = RM_OPEN;

        // поставить статус игроку
        c->state = ST_AUTH;
        c->room_id = -1;

        send_line(c->fd, "LEFT_ROOM %d", r->id);




        pthread_mutex_unlock(&global_lock);

        // отправить подтверждение игроку


    } else if (strcmp(cmd, "QUIT") == 0) {
        send_line(c->fd, "OK bye");
        // close handled by caller
        return;
    } else if (strcmp(cmd, "PING") == 0) {
        send_line(c->fd, "PONG");
        return;
    } else {
        send_line(c->fd, "ERR 100 BAD_FORMAT unknown_command");
        return;
    }
}

/* client thread */
static void *client_worker(void *arg) {
    client_t *c = (client_t*)arg;
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
    // cleanup on disconnect
    fprintf(stderr, "Client %s disconnected\n", c->nick);
    close(c->fd);
    unregister_client(c);
    free(c);
    return NULL;
}

int main(int argc, char **argv) {
    const char *host = "0.0.0.0";
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

    if (bind(listen_fd, (struct sockaddr*)&servaddr, sizeof(servaddr)) < 0) { perror("bind"); exit(1); }
    if (listen(listen_fd, LISTEN_BACKLOG) < 0) { perror("listen"); exit(1); }
    fprintf(stderr, "Server listening on 0.0.0.0:%s\n", port);

    /* init arrays */
    for (int i=0;i<MAX_CLIENTS;i++) clients[i] = NULL;
    for (int i=0;i<MAX_ROOMS;i++) rooms[i].id = 0;

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



