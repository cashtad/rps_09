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
#define ROOM_NAME_MAX 32
#define TOKEN_LEN 64
#define ROUND_TIMEOUT 10  // секунд на ход

#define PING_INTERVAL 3      // раз в 5 секунд
#define CLIENT_TIMEOUT_SOFT 6    // если 15 секунд нет PONG → кик
#define CLIENT_TIMEOUT_HARD 45    // если 15 секунд нет PONG → кик

typedef enum {
    ST_CONNECTED, // начальное состояние при подключении
    ST_AUTH, // присвоен токен, ник и тд. смотрит на список лобби
    ST_IN_LOBBY, // сидит в лобби, не готов
    ST_READY, // сидит в лобби готовый
    ST_PLAYING // играет
} client_state_t;
typedef enum { RM_OPEN, RM_FULL, RM_PLAYING, RM_PAUSED, RM_FINISHED } room_state_t;
typedef enum { CONNECTED, SOFT_TIMEOUT, HARD_TIMEOUT } client_timeout_t;

typedef struct {
    int fd;
    char nick[NICK_MAX+1];
    char token[TOKEN_LEN];
    client_state_t state;
    int room_id;
    time_t last_seen;        // обновляется при любом входящем сообщении
    time_t last_ping_sent;   // когда отправили последний PING
    client_timeout_t timeout_state; // для статуса подключения
    int is_replaced;
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

/* Checks rooms for timeouts and states. If there are any rooms with FINISHED state, they will be cleared. If some players did not make their moves within ROUND_TIMEOUT, the round will be processed accordingly.
 */
void check_rooms(void);
void check_clients(void);
void process_client_hard_disconnection(client_t *c);
void process_client_timeout(client_t *c);
void *room_timeout_worker(void *arg);
void *client_worker(void *arg);

#endif //RPS_09_SERVER_H