#ifndef RPS_09_ROOM_H
#define RPS_09_ROOM_H

#include "server.h"
#include "send_line.h"
#include <string.h>
#include <pthread.h>

/**
 * @brief Initializes the global room table.
 * @details Input: none. Output: resets every room slot to the default state.
 */
void init_rooms(void);
/**
 * @brief Checks every room for round expirations and clears completed sessions.
 * @details Input: none. Output: room states are updated in place.
 * @return void
 */
void check_rooms(void);
/**
 * @brief Creates a new room with the provided name.
 * @param name Input room name string; output is a populated room entry.
 * @return int Output room identifier on success or -1 on failure.
 */
int create_room(const char *name);
/**
 * @brief Retrieves a room pointer by its numeric identifier.
 * @param id Input room identifier to search for.
 * @return room_t* Output pointer to the room or NULL if not found.
 */
room_t* find_room_by_id(int id);
/**
 * @brief Finds the room that contains a client with the supplied file descriptor.
 * @param fd Input client socket descriptor.
 * @return room_t* Output pointer to the matching room or NULL.
 */
room_t* find_room_by_player_fd(int fd);
/**
 * @brief Adds a client into the specified room.
 * @param c Input pointer to the client joining; output state is updated.
 * @param r Input pointer to the target room; output state gains the client.
 * @return int Output 0 on success or -1 on error.
 */
int add_player_to_room(client_t *c, room_t *r);
/**
 * @brief Removes a client from the specified room.
 * @param c Input pointer to the client leaving.
 * @param r Input pointer to the room being modified.
 * @return int Output 0 on success or -1 on error.
 */
int remove_player_from_room(client_t *c, room_t *r);
/**
 * @brief Returns a string representation of the supplied room state.
 * @param state Input room_state_t to describe.
 * @return const char* Output pointer to a static string literal.
 */
const char* get_room_state_name(room_state_t state);
/**
 * @brief Clears all data stored inside a room instance.
 * @param room Input pointer to the room being reset.
 * @return int Output 0 when the reset succeeds.
 */
int remove_room(room_t *room);
/**
 * @brief Removes a room by identifier.
 * @param id Input identifier that should be cleared.
 * @return int Output 0 on success or -1 if the room does not exist.
 */
int remove_room_by_id(int id);
/**
 * @brief Reports how many players are currently seated in a room.
 * @param r Input pointer to the room to inspect.
 * @return int Output player count.
 */
int get_amount_of_players_in_room(const room_t *r);
/**
 * @brief Retrieves the opponent of the provided client inside a room.
 * @param r Input pointer to the room containing both players.
 * @param c Input pointer to the client whose opponent is requested.
 * @return client_t* Output pointer to the opponent or NULL.
 */
client_t* get_opponent_in_room(const room_t *r, const client_t *c);
/**
 * @brief Indicates whether a client in a room has been replaced during reconnect.
 * @param r Input pointer to the room under inspection.
 * @param c Input pointer to the client of interest.
 * @return int Output non-zero if the client was replaced, zero otherwise.
 */
int was_replaced(room_t *r, client_t *c);

#endif //RPS_09_ROOM_H

