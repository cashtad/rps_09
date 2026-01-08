#ifndef RPS_09_GAME_H
#define RPS_09_GAME_H

#include "server.h"
#include "send_line.h"

/**
 * @brief Starts a best-of series for the supplied room.
 * @param r Input pointer to the room; output state transitions to RM_PLAYING.
 * @return void
 */
void start_game(room_t *r);
/**
 * @brief Initializes the next round within an ongoing match.
 * @param r Input pointer to the room; output fields like round_number are updated.
 * @return void
 */
void start_next_round(room_t *r);
/**
 * @brief Resolves a round once both moves are available.
 * @param r Input pointer to the room holding the moves.
 * @return void
 */
void process_round_result(room_t *r);
/**
 * @brief Ends the game, announces the winner, and resets the room.
 * @param r Input pointer to the finished room.
 * @return void
 */
void end_game(room_t *r);
/**
 * @brief Handles round expiration when players run out of time.
 * @param r Input pointer to the room whose timer fired.
 * @return void
 */
void handle_round_timeout(room_t *r);

#endif //RPS_09_GAME_H