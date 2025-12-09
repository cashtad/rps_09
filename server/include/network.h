#ifndef RPS_09_NETWORK_H
#define RPS_09_NETWORK_H

// Network utility functions
void trim_crlf(char *s);
int send_line(int fd, const char *fmt, ...);
int send_broadcast_about_new_room(const char *text);
int send_room_list(int fd);

#endif //RPS_09_NETWORK_H

