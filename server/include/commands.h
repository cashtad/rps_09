#ifndef RPS_09_COMMANDS_H
#define RPS_09_COMMANDS_H

#include "server.h"
#include "send_line.h"
#include "room.h"
#include "client.h"
#include <string.h>
#include <pthread.h>
#include "game.h"
#include <sys/socket.h>

/**
 * @brief Parses and dispatches a complete protocol line from a client.
 * @param c Input pointer to the client; output state may be modified.
 * @param line Input buffer containing the mutable line.
 * @return void
 */
void handle_line(client_t *c, char *line);
/**
 * @brief Handles the HELLO command and assigns identity data.
 * @param c Input pointer to the client issuing the command.
 * @param args Input argument string containing the nickname.
 * @return void
 */
void handle_hello(client_t *c, char *args);
/**
 * @brief Sends the room list to an authenticated client.
 * @param c Input pointer to the requester.
 * @return void
 */
void handle_list(client_t *c);
/**
 * @brief Creates a new room on behalf of a client.
 * @param c Input pointer to the requester; output state is updated.
 * @param args Input argument string containing the room name.
 * @return void
 */
void handle_create(client_t *c, char *args);
/**
 * @brief Joins the specified room if available.
 * @param c Input pointer to the client joining.
 * @param args Input argument string with the room identifier.
 * @return void
 */
void handle_join(client_t *c, char *args);
/**
 * @brief Marks the client as ready within its room.
 * @param c Input pointer to the client toggling readiness.
 * @return void
 */
void handle_ready(client_t *c);
/**
 * @brief Handles a client's request to leave its current room.
 * @param c Input pointer to the client leaving.
 * @return void
 */
void handle_leave(client_t *c);
/**
 * @brief Processes a MOVE command during an active round.
 * @param c Input pointer to the acting client.
 * @param args Input argument string containing the move symbol.
 * @return void
 */
void handle_move(client_t *c, char *args);
/**
 * @brief Returns information about the opponent sharing the same room.
 * @param c Input pointer to the querying client.
 * @return void
 */
void handle_get_opponent(client_t *c);
/**
 * @brief Reattaches a client session using a previously issued token.
 * @param c Input pointer to the new connection.
 * @param args Input argument string containing the reconnect token.
 * @return void
 */
void handle_reconnect(client_t *c, char *args);
/**
 * @brief Processes a client's decision to quit the server.
 * @param c Input pointer to the departing client.
 * @return void
 */
void handle_quit(client_t *c);

#endif //RPS_09_COMMANDS_H
