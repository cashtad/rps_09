#ifndef RPS_09_ROOM_H
#define RPS_09_ROOM_H

#include "server.h"

// Room management functions
void init_rooms(void);
int create_room(const char *name);
room_t* find_room_by_id(int id);
room_t* find_room_by_player_fd(int fd);
int add_player_to_room(client_t *c, room_t *r);
int remove_player_from_room(client_t *c, room_t *r);
const char* get_room_state_name(room_state_t state);
int remove_room(room_t *room);
int remove_room_by_id(int id);
int get_amount_of_players_in_room(const room_t *r);
client_t* get_opponent_in_room(room_t *r, client_t *c);

#endif //RPS_09_ROOM_H

