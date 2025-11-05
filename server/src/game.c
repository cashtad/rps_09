#include "../include/game.h"

void start_next_round(room_t *r) {
    r->round_number++;
    r->move_p1 = '\0';
    r->move_p2 = '\0';
    r->round_start_time = time(NULL);
    r->awaiting_moves = 1;

    send_line(r->player1->fd, "ROUND_START %d", r->round_number);
    send_line(r->player2->fd, "ROUND_START %d", r->round_number);
}

void process_round_result(room_t *r) {
    char *winner_str;

    if (r->move_p1 == r->move_p2) {
        winner_str = "DRAW";
    } else if ((r->move_p1 == 'R' && r->move_p2 == 'S') ||
               (r->move_p1 == 'P' && r->move_p2 == 'R') ||
               (r->move_p1 == 'S' && r->move_p2 == 'P')) {
        r->score_p1++;
        winner_str = r->player1->nick;
               } else {
                   r->score_p2++;
                   winner_str = r->player2->nick;
               }

    send_line(r->player1->fd, "ROUND_RESULT %s %c %c %d %d",
              winner_str, r->move_p1, r->move_p2, r->score_p1, r->score_p2);
    send_line(r->player2->fd, "ROUND_RESULT %s %c %c %d %d",
              winner_str, r->move_p1, r->move_p2, r->score_p1, r->score_p2);

    // Проверяем конец игры
    if (r->score_p1 >= 5 || r->score_p2 >= 5) {
        end_game(r);
    } else {
        start_next_round(r);
    }
}

void end_game(room_t *r) {
    char *winner = (r->score_p1 >= 5) ? r->player1->nick : r->player2->nick;

    send_line(r->player1->fd, "GAME_END %s", winner);
    send_line(r->player2->fd, "GAME_END %s", winner);

    r->state = RM_FINISHED;
    r->player1->state = ST_AUTH;
    r->player2->state = ST_AUTH;
    r->player1->room_id = -1;
    r->player2->room_id = -1;
}



void handle_round_timeout(room_t *r) {
    r->awaiting_moves = 0;

    // Игрок, не сделавший ход, проигрывает раунд
    if (r->move_p1 == '\0' && r->move_p2 != '\0') {
        r->score_p2++;
    } else if (r->move_p2 == '\0' && r->move_p1 != '\0') {
        r->score_p1++;
    }
    // Если оба не сделали ход - ничья

    char m1 = (r->move_p1 == '\0') ? 'X' : r->move_p1;
    char m2 = (r->move_p2 == '\0') ? 'X' : r->move_p2;

    send_line(r->player1->fd, "ROUND_RESULT TIMEOUT %c %c %d %d",
              m1, m2, r->score_p1, r->score_p2);
    send_line(r->player2->fd, "ROUND_RESULT TIMEOUT %c %c %d %d",
              m1, m2, r->score_p1, r->score_p2);

    if (r->score_p1 >= 5 || r->score_p2 >= 5) {
        end_game(r);
    } else {
        start_next_round(r);
    }
}