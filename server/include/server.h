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
typedef enum { RM_OPEN, RM_FULL, RM_PLAYING, RM_PAUSED, RM_FINISHED } room_state_t;

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
    int round_number;           // текущий раунд (1-9)
    int score_p1;              // счет игрока 1
    int score_p2;              // счет игрока 2
    char move_p1;              // 'R', 'P', 'S' или '\0'
    char move_p2;              // 'R', 'P', 'S' или '\0'
    time_t round_start_time;   // время начала раунда для таймаута
    int awaiting_moves;        // 1 если ждем ходы, 0 иначе
} room_t;

#endif //RPS_09_SERVER_H