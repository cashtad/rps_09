#include "../include/client.h"

extern pthread_mutex_t global_lock;
extern client_t *clients[MAX_CLIENTS];

int register_client(client_t *c) {
    pthread_mutex_lock(&global_lock);
    for (int i = 0; i < MAX_CLIENTS; i++) {
        if (clients[i] == NULL) {
            clients[i] = c;
            pthread_mutex_unlock(&global_lock);
            return 0;
        }
    }
    pthread_mutex_unlock(&global_lock);
    return -1;
}

void unregister_client(const client_t *c) {
    pthread_mutex_lock(&global_lock);
    for (int i = 0; i < MAX_CLIENTS; i++) {
        if (clients[i] == c) {
            clients[i] = NULL;
            break;
        }
    }
    pthread_mutex_unlock(&global_lock);
}

void unregister_client_without_lock(const client_t *c) {
    if (c == NULL) return;
    for (int i = 0; i < MAX_CLIENTS; i++) {
        if (clients[i] == c) {
            clients[i] = NULL;
            break;
        }
    }
}

client_t* find_client_by_fd(const int fd) {
    for (int i = 0; i < MAX_CLIENTS; i++) {
        if (clients[i] != NULL && clients[i]->fd == fd)
            return clients[i];
    }
    return NULL;
}
client_t* find_client_by_token(const char* token) {
    if (!token) {printf("No token was provided\n"); return NULL;}
    
    for (int i = 0; i < MAX_CLIENTS; i++) {
        if (clients[i] != NULL) {
            if (clients[i]->token[0] != '\0') {
                if (strcmp(clients[i]->token, token) == 0) {
                    printf("Found client with token %s = %s\n", token, clients[i]->token);
                    return clients[i];
                } else {
                    printf("%s != %s\n", token, clients[i]->token);
                }
            }
        }
    }
    printf("Didnt find any client with this token\n");
    return NULL;
}

client_t* find_client_by_name(const char *name) {
    for (int i = 0; i < MAX_CLIENTS; i++) {
        if (clients[i] != NULL && strcmp(clients[i]->nick, name) == 0){
            return clients[i];
        }
    }
    return NULL;
}

void gen_token(char *out) {
    const char *hex = "0123456789abcdef";
    srand((unsigned)time(NULL) ^ pthread_self());
    for (size_t i=0;i<30 && i+1<TOKEN_LEN;i++) out[i] = hex[rand() % 16];
    out[30 < TOKEN_LEN ? 30 : TOKEN_LEN-1] = '\0';
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
            send_line(opponent->fd, "OPP_INF %s N_R", c->nick);
        }
        break;
    case ST_PLAYING:
        room_t *room = find_room_by_id(c->room_id);

        room->state = RM_PAUSED;
        room->awaiting_moves = 0;

        client_t *opp = (room->player1 == c) ? room->player2 : room->player1;
        if (opp) {
            send_line(opp->fd, "G_PAUSE");
        }
        fprintf(stderr, "Game paused in room %d\n", room->id);
        break;
    default:
        break;
    }
}

void process_client_hard_disconnection(client_t *c) {
    printf("Processing hard disconnect for client %s fd%d\n", c->nick, c->fd);
    // If the client was replaced via RECONNECT, skip cleanup
    if (c->is_replaced) {
        printf("Client %s was replaced, skipping cleanup\n", c->nick);
        return;
    }
    switch (c->state) {
    case ST_IN_LOBBY:
    case ST_READY:
        room_t *r = find_room_by_id(c->room_id);
        if (r) {
            printf("Removing player %s fd%d from room %s\n", c->nick, c->fd,r->name);
            remove_player_from_room(c, r);
        }
        break;

    case ST_PLAYING:
        room_t *room = find_room_by_id(c->room_id);
        if (room) {
            client_t *opponent = get_opponent_in_room(room, c);
            if (opponent) {
                send_line(opponent->fd, "G_END opp_l");
                opponent->state = ST_AUTH;
                opponent->room_id = -1;
            }
            printf("Room %s ended due to client %s fd%d disconnect\n", room->name, c->nick, c->fd);
            remove_room(room);
        }
        break;
    default:
        break;
    }
}
