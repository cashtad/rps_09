#ifndef RPS_09_COMMANDS_H
#define RPS_09_COMMANDS_H

#include "server.h"
#include "game.h"

// Command handlers
void handle_line(client_t *c, char *line);
void handle_hello(client_t *c, char *args);
void handle_list(const client_t *c);
void handle_create(client_t *c, char *args);
void handle_join(client_t *c, char *args);
void handle_ready(client_t *c);
void handle_leave(client_t *c);
void handle_move(client_t *c, char *args);
void handle_get_opponent(client_t *c);
void handle_reconnect(client_t *c, char *args);
void handle_quit(client_t *c);

#endif //RPS_09_COMMANDS_H
