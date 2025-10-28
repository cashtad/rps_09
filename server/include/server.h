//
// Created by leonid on 10/26/25.
//

#ifndef RPS_09_SERVER_H
#define RPS_09_SERVER_H
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <errno.h>
#include <time.h>
#include <unistd.h>
#include <arpa/inet.h>
#include <pthread.h>
#include <stdint.h>
#include <sys/socket.h>
#include <stdarg.h>

#define LISTEN_BACKLOG 16
#define LINE_BUF 512
#define MAX_CLIENTS 128
#define MAX_ROOMS 64
#define NICK_MAX 32
#define ROOM_NAME_MAX 64


typedef enum { ST_CONNECTED, ST_AUTH, ST_IN_LOBBY, ST_IN_ROOM, ST_READY, ST_PLAYING } client_state_t;
typedef enum { RM_OPEN, RM_FULL, RM_PLAYING, RM_PAUSED } room_state_t;

typedef struct {
    int id;
    char name[ROOM_NAME_MAX+1];
    int players[2]; // client_fds (or -1)
    int player_count;
    room_state_t state;
} room_t;

typedef struct {
    int fd; //file descriptor
    char nick[NICK_MAX+1]; //name
    char token[64]; //session token
    client_state_t state; //connection state
    int room_id; // -1 if none
    time_t last_seen; //time of last activity
    pthread_t thread;
} client_t;

#endif //RPS_09_SERVER_H