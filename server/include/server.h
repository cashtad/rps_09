#ifndef RPS_09_SERVER_H
#define RPS_09_SERVER_H

#include <pthread.h>
#include <stdlib.h>
#include <sys/socket.h>
#include <netinet/in.h>
#include <stdio.h>
#include <string.h>
#include <arpa/inet.h>



/**
 * @brief Maximum backlog for pending TCP connections supplied to listen().
 */
#define LISTEN_BACKLOG 16
/**
 * @brief Maximum length of a protocol line handled by the server.
 */
#define LINE_BUF 512
/**
 * @brief Upper bound on concurrently connected clients.
 */
#define MAX_CLIENTS 128
/**
 * @brief Maximum number of rooms managed simultaneously.
 */
#define MAX_ROOMS 64
/**
 * @brief Maximum number of characters allowed in a nickname.
 */
#define NICK_MAX 32
/**
 * @brief Maximum number of characters allowed in a room name.
 */
#define ROOM_NAME_MAX 32
/**
 * @brief Maximum number of characters stored in a session token.
 */
#define TOKEN_LEN 64
/**
 * @brief Number of seconds a player has to submit a move.
 */
#define ROUND_TIMEOUT 10
/**
 * @brief Interval in seconds between heartbeat pings.
 */
#define PING_INTERVAL 3
/**
 * @brief Seconds to wait before declaring a soft timeout.
 */
#define CLIENT_TIMEOUT_SOFT 6
/**
 * @brief Seconds to wait before enforcing a hard timeout.
 */
#define CLIENT_TIMEOUT_HARD 45
/**
 * @brief Default IP address used when no bind IP is supplied.
 */
#define DEFAULT_BIND_IP "0.0.0.0"
/**
 * @brief Default TCP port used when no port is supplied.
 */
#define DEFAULT_BIND_PORT 2500
/**
 * @brief Maximum count of consecutive invalid messages tolerated.
 */
#define MAX_INVALID_MSG_STREAK 3

/**
 * @brief Represents the lifecycle stage of a client session.
 */
typedef enum {
    ST_CONNECTED, // Initial state immediately after a TCP connection is accepted
    ST_AUTH,      // Client has authenticated and can view lobbies
    ST_IN_LOBBY,  // Client sits in a lobby but is not ready
    ST_READY,     // Client remains in lobby but marked as ready
    ST_PLAYING    // Client is currently engaged in a match
} client_state_t;

/**
 * @brief Describes the lifecycle state of a room.
 */
typedef enum { RM_OPEN, RM_FULL, RM_PLAYING, RM_PAUSED } room_state_t;

/**
 * @brief Represents the heartbeat/timeout progression for a client.
 */
typedef enum { CONNECTED, SOFT_TIMEOUT, HARD_TIMEOUT } client_timeout_t;

/**
 * @brief Describes all mutable attributes of a connected client.
 */
typedef struct {
    int fd;
    char nick[NICK_MAX+1];
    char token[TOKEN_LEN];
    client_state_t state;
    int room_id;
    time_t last_seen;        // Timestamp of the last inbound message
    time_t last_ping_sent;   // Timestamp when the latest PING was emitted
    client_timeout_t timeout_state; // Current timeout milestone for the client
    int is_replaced;
    int invalid_msg_streak;
    pthread_t thread;
} client_t;

/**
 * @brief Captures the state of a single room instance.
 */
typedef struct {
    int id;
    char name[ROOM_NAME_MAX+1];
    client_t* player1;
    client_t* player2;
    int player_count;
    room_state_t state;
    int round_number;           // Current round index starting at 1
    int score_p1;               // Score for player 1
    int score_p2;               // Score for player 2
    char move_p1;               // Last move by player 1 ('R','P','S','\0')
    char move_p2;               // Last move by player 2 ('R','P','S','\0')
    time_t round_start_time;    // Timestamp when the round began
    int awaiting_moves;         // Non-zero if new moves are still expected
} room_t;

/**
 * @brief Evaluates connected clients for ping/timeout handling.
 * @details Input: none. Output: client records are updated or dropped.
 * @return void
 */
void check_clients(void);
/**
 * @brief Thread routine used to monitor room and client timeouts.
 * @param arg Input pointer (unused, expected NULL). Output is unused as well.
 * @return void* Always NULL when the thread exits.
 */
void *room_timeout_worker(void *arg);
/**
 * @brief Handles all I/O for a particular client connection.
 * @param arg Input pointer to the client object; the pointed data is updated as output.
 * @return void* Always NULL when the thread terminates.
 */
void *client_worker(void *arg);

#endif //RPS_09_SERVER_H

