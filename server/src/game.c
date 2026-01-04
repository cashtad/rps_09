#include "../include/game.h"


void start_game(room_t *r) {
    // Initialize the round tracking state
    r->state = RM_PLAYING;
    r->round_number = 0;
    r->score_p1 = 0;
    r->score_p2 = 0;

    // Inform both players that gameplay is starting
    send_line(r->player1->fd, "G_ST");
    send_line(r->player2->fd, "G_ST");
    r->player1->state = ST_PLAYING;
    r->player2->state = ST_PLAYING;

    // Trigger the first round immediately
    start_next_round(r);
}

void start_next_round(room_t *r) {
    r->round_number++;
    r->move_p1 = '\0';
    r->move_p2 = '\0';
    r->round_start_time = time(NULL);
    r->awaiting_moves = 1;

    send_line(r->player1->fd, "R_ST %d", r->round_number);
    send_line(r->player2->fd, "R_ST %d", r->round_number);
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

    send_line(r->player1->fd, "R_RE %s %c %c %d %d",
              winner_str, r->move_p1, r->move_p2, r->score_p1, r->score_p2);
    send_line(r->player2->fd, "R_RE %s %c %c %d %d",
              winner_str, r->move_p2, r->move_p1, r->score_p2, r->score_p1);

    printf("Player1 <%s>: %d\n", r->player1->nick, r->score_p1);
    printf("Player2 <%s>: %d\n", r->player2->nick, r->score_p2);

    // Check whether someone has already won
    if (r->score_p1 >= 5 || r->score_p2 >= 5) {
        end_game(r);
    } else {
        start_next_round(r);
    }
}

void end_game(room_t *r) {
    char *winner = r->score_p1 >= 5 ? r->player1->nick : r->player2->nick;

    send_line(r->player1->fd, "G_END %s", winner);
    send_line(r->player2->fd, "G_END %s", winner);

    // Reset player state after the match
    r->player1->state = ST_AUTH;
    r->player2->state = ST_AUTH;
    r->player1->room_id = -1;
    r->player2->room_id = -1;

    // Reset the room so it can be reused
    r->id = 0;
    r->name[0] = '\0';
    r->player1 = r->player2 = NULL;
    r->player_count = 0;
    r->state = RM_OPEN;

}



void handle_round_timeout(room_t *r) {
    // Skip timeouts while the game is paused
    printf("Handling timeout for moves\n");
    if (r->state == RM_PAUSED) {
        return;
    }

    r->awaiting_moves = 0;

    // Award the round to the opponent if a player missed the move
    if (r->move_p1 == '\0' && r->move_p2 != '\0') {
        r->score_p2++;
    } else if (r->move_p2 == '\0' && r->move_p1 != '\0') {
        r->score_p1++;
    }
    // Treat a double-miss as a draw

    const char m1 = r->move_p1 == '\0' ? 'X' : r->move_p1;
    const char m2 = r->move_p2 == '\0' ? 'X' : r->move_p2;

    send_line(r->player1->fd, "R_RE T %c %c %d %d",
              m1, m2, r->score_p1, r->score_p2);
    send_line(r->player2->fd, "R_RE T %c %c %d %d",
              m2, m1, r->score_p2, r->score_p1);

    if (r->score_p1 >= 5 || r->score_p2 >= 5) {
        end_game(r);
    } else {
        start_next_round(r);
    }
}