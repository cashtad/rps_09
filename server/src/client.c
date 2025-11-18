#include "../include/client.h"
#include <time.h>
#include <pthread.h>
#include <stdint.h>

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

client_t* find_client_by_fd(int fd) {
    for (int i = 0; i < MAX_CLIENTS; i++) {
        if (clients[i] != NULL && clients[i]->fd == fd)
            return clients[i];
    }
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
    srand((unsigned)time(NULL) ^ (uintptr_t)pthread_self());
    for (size_t i=0;i<30 && i+1<TOKEN_LEN;i++) out[i] = hex[rand() % 16];
    out[30 < TOKEN_LEN ? 30 : TOKEN_LEN-1] = '\0';
}

