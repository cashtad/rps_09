#ifndef RPS_09_CLIENT_H
#define RPS_09_CLIENT_H

#include "server.h"

// Client management functions
int register_client(client_t *c);
void unregister_client(const client_t *c);
client_t* find_client_by_fd(int fd);
void gen_token(char *out);
client_t* find_client_by_name(const char *name);


#endif //RPS_09_CLIENT_H
