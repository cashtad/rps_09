#ifndef RPS_09_CLIENT_H
#define RPS_09_CLIENT_H

#include "server.h"
#include <time.h>
#include <stdint.h>
#include "room.h"

/**
 * @brief Registers a freshly connected client in the global registry.
 * @param c Input pointer to the client to add; its state is used as output in the registry.
 * @return int Output 0 on success or -1 when the registry is full.
 */
int register_client(client_t *c);
/**
 * @brief Removes a client from the registry using thread-safe locking.
 * @param c Input pointer to the client being removed.
 * @return void
 */
void unregister_client(const client_t *c);
/**
 * @brief Finds a client object by socket descriptor.
 * @param fd Input file descriptor to search for.
 * @return client_t* Output pointer to the matching client or NULL.
 */
client_t* find_client_by_fd(int fd);
/**
 * @brief Generates a random session token for reconnect support.
 * @param out Output buffer that receives the null-terminated token.
 * @return void
 */
void gen_token(char *out);
/**
 * @brief Looks up a client by nickname.
 * @param name Input nickname string.
 * @return client_t* Output pointer to the client or NULL when absent.
 */
client_t* find_client_by_name(const char *name);
/**
 * @brief Retrieves a client using its reconnect token.
 * @param token Input token string.
 * @return client_t* Output pointer to the associated client or NULL.
 */
client_t* find_client_by_token(const char* token);
/**
 * @brief Removes a client from the registry when the caller already holds the lock.
 * @param c Input pointer to the client being removed.
 * @return void
 */
void unregister_client_without_lock(const client_t *c);
/**
 * @brief Applies timeout-specific logic to a client inside a room or a game.
 * @param c Input pointer to the stalled client.
 * @return void
 */
void process_client_timeout(client_t *c);
/**
 * @brief Processes cleanup required after a hard client disconnection.
 * @param c Input pointer to the disconnected client; its state is updated as output.
 * @return void
 */
void process_client_hard_disconnection(client_t *c);

#endif //RPS_09_CLIENT_H
