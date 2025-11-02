//
// Created by leonid on 10/26/25.
//

#ifndef RPS_09_SERVER_H
#define RPS_09_SERVER_H

#include <unistd.h>
#include <pthread.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <netinet/in.h>


#define LISTEN_BACKLOG 16
#define LINE_BUF 512
#define MAX_CLIENTS 128
#define MAX_ROOMS 64
#define NICK_MAX 32
#define ROOM_NAME_MAX 64
#define TOKEN_LEN 64

typedef enum { ST_CONNECTED, ST_AUTH, ST_IN_LOBBY, ST_READY, ST_PLAYING } client_state_t;
typedef enum { RM_OPEN, RM_FULL, RM_PLAYING, RM_PAUSED } room_state_t;

typedef struct {
    int fd;
    char nick[NICK_MAX+1];
    char token[TOKEN_LEN];
    client_state_t state;
    int room_id;
    time_t last_seen;
    pthread_t thread;
} client_t;

typedef struct {
    int id;
    char name[ROOM_NAME_MAX+1];
    client_t* player1;
    client_t* player2;
    int player_count;
    room_state_t state;
} room_t;

#endif //RPS_09_SERVER_H