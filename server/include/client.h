#ifndef RPS_09_CLIENT_H
#define RPS_09_CLIENT_H

#include "server.h"

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


#endif //RPS_09_CLIENT_H
