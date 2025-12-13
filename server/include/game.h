//
// Created by leonid on 11/2/25.
//

#ifndef RPS_09_GAME_H
#define RPS_09_GAME_H

#include "server.h"
#include "../include/network.h"
void start_game(room_t *r);
void start_next_round(room_t *r);
void process_round_result(room_t *r);
void end_game(room_t *r);
void handle_round_timeout(room_t *r);

#endif //RPS_09_GAME_H