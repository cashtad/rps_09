#define GNU_SOURCE


#include "../include/server.h"



#include "../include/client.h"
#include "../include/room.h"
#include "../include/send_line.h"
#include "../include/commands.h"

/** Protects shared server-wide state accessed across threads. */
pthread_mutex_t global_lock = PTHREAD_MUTEX_INITIALIZER;
/** Holds pointers to every currently connected client slot. */
client_t *clients[MAX_CLIENTS];
/** Stores all room descriptors available on the server. */
room_t rooms[MAX_ROOMS];

int main(int argc, char **argv) {
    const char *bind_ip = DEFAULT_BIND_IP;
    int port = DEFAULT_BIND_PORT;

    if (argc >= 2 && argv[1][0] != '\0') {
        struct in_addr tmp_addr;
        if (inet_pton(AF_INET, argv[1], &tmp_addr) == 1) {
            bind_ip = argv[1];
        } else {
            fprintf(stderr, "Invalid IP '%s', using default %s\n", argv[1], DEFAULT_BIND_IP);
        }
    }
    if (argc >= 3 && argv[2][0] != '\0') {
        char *end_ptr = NULL;
        long parsed_port = strtol(argv[2], &end_ptr, 10);
        if (end_ptr && *end_ptr == '\0' && parsed_port > 0 && parsed_port <= 65535) {
            port = (int)parsed_port;
        } else {
            fprintf(stderr, "Invalid port '%s', using default %d\n", argv[2], DEFAULT_BIND_PORT);
        }
    }

    struct sockaddr_in servaddr;
    int listen_fd = socket(AF_INET, SOCK_STREAM, 0);
    if (listen_fd < 0) { perror("socket"); exit(1); }

    int opt = 1;
    setsockopt(listen_fd, SOL_SOCKET, SO_REUSEADDR, &opt, sizeof(opt));

    memset(&servaddr, 0, sizeof(servaddr));
    servaddr.sin_family = AF_INET;
    if (inet_pton(AF_INET, bind_ip, &servaddr.sin_addr) != 1) {
        inet_pton(AF_INET, DEFAULT_BIND_IP, &servaddr.sin_addr);
        bind_ip = DEFAULT_BIND_IP;
    }
    servaddr.sin_port = htons(port);

    if (bind(listen_fd, (struct sockaddr*)&servaddr, sizeof(servaddr)) < 0) {
        perror("bind"); exit(1);
    }
    if (listen(listen_fd, LISTEN_BACKLOG) < 0) {
        perror("listen"); exit(1);
    }

    fprintf(stderr, "Server listening on %s:%d\n", bind_ip, port);

    for (int i = 0; i < MAX_CLIENTS; i++) clients[i] = NULL;
    init_rooms();

    // Start an independent timeout monitor
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
        c->is_replaced = 0;
        c->last_ping_sent = time(NULL);
        c->invalid_msg_streak = 0;
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
        nanosleep(&interval, NULL);  // Sleep first so the loop never spins aggressively

        pthread_mutex_lock(&global_lock);
        check_rooms();     // Enforce room round-expiration rules
        check_clients();   // Enforce client heartbeat and timeout rules
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
        if (c->invalid_msg_streak >= MAX_INVALID_MSG_STREAK) {
            break;
        }
        handle_line(c, buf);
    }
    close(c->fd);
    pthread_mutex_lock(&global_lock);

    if (c->timeout_state == SOFT_TIMEOUT) {
        printf("Client %s fd:%d disconnected, waiting for reconnect\n", c->nick, c->fd);
        pthread_mutex_unlock(&global_lock);
        return NULL;
    }
    printf("Client %s fd:%d fully disconnected, deleting him from everywhere\n", c->nick, c->fd);
    process_client_hard_disconnection(c);
    unregister_client_without_lock(c);
    pthread_mutex_unlock(&global_lock);
    free(c);
    return NULL;
}

void check_clients(void) {
    time_t now = time(NULL);

    for (int i = 0; i < MAX_CLIENTS; i++) {
        client_t *c = clients[i];
        if (!c) continue;

        // 1) If no data has been received recently, mark the client as stalled
        if (now - c->last_seen >= CLIENT_TIMEOUT_SOFT && c->timeout_state == CONNECTED) {
            fprintf(stderr, "check_clients: Client soft timeout: %s\n", c->nick);
            c->timeout_state = SOFT_TIMEOUT;
            process_client_timeout(c);
            shutdown(c->fd, SHUT_RDWR);
            continue;
        }

        // 2) Force a hard disconnect if the client never recovered after the soft timeout
        if (now - c->last_seen >= CLIENT_TIMEOUT_HARD && c->timeout_state == SOFT_TIMEOUT) {
            fprintf(stderr, "Client hard timeout: %s\n", c->nick);
            process_client_hard_disconnection(c);
            unregister_client_without_lock(c);
            c->timeout_state = HARD_TIMEOUT;
            shutdown(c->fd, SHUT_RDWR);
            continue;
        }

        // 3) Send periodic ping frames to keep the connection alive
        if (now - c->last_ping_sent >= PING_INTERVAL && c->timeout_state == CONNECTED) {
            send_line(c->fd, "PING");
            c->last_ping_sent = now;
        }
    }
}
