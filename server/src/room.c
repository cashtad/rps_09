#include "../include/room.h"
#include "../include/network.h"
#include <string.h>
#include <pthread.h>

extern pthread_mutex_t global_lock;
extern room_t rooms[MAX_ROOMS];
static int next_room_id = 1;

void init_rooms(void) {
    for (int i = 0; i < MAX_ROOMS; i++)
        rooms[i].id = 0;
}

int get_amount_of_players_in_room(const room_t *r) {
    return r->player_count;
}

client_t* get_opponent_in_room(room_t *r, client_t *c) {
    if (r-> player_count < 2) return NULL;
    if (r->player1 == c) return r->player2;
    if (r->player2 == c) return r->player1;
    return NULL;
}

const char* get_room_state_name(const room_state_t state) {
    switch (state) {
        case RM_OPEN: return "OPEN";
        case RM_FULL: return "FULL";
        case RM_PLAYING: return "PLAYING";
        case RM_PAUSED: return "PAUSED";
        case RM_FINISHED: return "FINISHED";
    }
    return "";
}

int create_room(const char *name) {
    for (int i = 0; i < MAX_ROOMS; i++) {
        if (rooms[i].id == 0) {
            rooms[i].id = next_room_id++;
            strncpy(rooms[i].name, name, ROOM_NAME_MAX);
            rooms[i].name[ROOM_NAME_MAX] = '\0';
            rooms[i].player1 = rooms[i].player2 = NULL;
            rooms[i].player_count = 0;
            rooms[i].state = RM_OPEN;
            return rooms[i].id;
        }
    }
    return -1;
}

int remove_room_by_id(const int id) {
    for (int i = 0; i < MAX_ROOMS; i++) {
        if (rooms[i].id == id) {
            rooms[i].id = 0;
            rooms[i].name[0] = '\0';
            rooms[i].player1 = rooms[i].player2 = NULL;
            rooms[i].player_count = 0;
            rooms[i].state = RM_OPEN;
            return 0;
        }
    }
    return -1;
}

int remove_room(room_t *room) {
    room->id = 0;
    room->name[0] = '\0';
    room->player1 = room->player2 = NULL;
    room->player_count = 0;
    room->state = RM_OPEN;
    return 0;
}

room_t* find_room_by_id(const int id) {
    if (id < 0) return NULL;
    for (int i = 0; i < MAX_ROOMS; i++) {
        if (rooms[i].id == id) return &rooms[i];
    }
    return NULL;
}

room_t* find_room_by_player_fd(const int fd) {
    for (int i = 0; i < MAX_ROOMS; i++) {
        if (rooms[i].player1 && rooms[i].player1->fd == fd)
            return &rooms[i];
        if (rooms[i].player2 && rooms[i].player2->fd == fd)
            return &rooms[i];
    }
    return NULL;
}

int add_player_to_room(client_t *c, room_t *r) {
    if (r->player1 == NULL) {
        r->player1 = c;
    } else {
        r->player2 = c;
    }
    r->player_count++;
    if (r->player_count == 2) r->state = RM_FULL;
    c->room_id = r->id;
    c->state = ST_IN_LOBBY;
    send_line(c->fd, "ROOM_JOINED %d", r->id);
    return 0;
}

int remove_player_from_room(client_t *c, room_t *r) {
    if (r == NULL || c == NULL) return -1;
    if (r->player_count == 2) {
        if (r->player1 == c) {
            r->player1 = r->player2;
            r->player_count--;
            r->player2 = NULL;
            r->state = RM_OPEN;
        } else {
            r->player_count--;
            r->player2 = NULL;
            r->state = RM_OPEN;
        }
        send_line(r->player1->fd, "PLAYER_LEFT %s", c->nick);
    } else {
        r->player_count--;
        r->player1 = NULL;
    }
    return 0;
}

