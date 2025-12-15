#ifndef RPS_09_NETWORK_H
#define RPS_09_NETWORK_H

/**
 * @brief Strips trailing CR/LF characters from a mutable string.
 * @param s Input/output buffer to sanitize in place.
 * @return void
 */
void trim_crlf(char *s);
/**
 * @brief Sends a formatted line terminated with CRLF to a socket.
 * @param fd Input destination descriptor.
 * @param fmt Input printf-style format string followed by variadic args.
 * @return int Output number of bytes transmitted or -1 on error.
 */
int send_line(int fd, const char *fmt, ...);
/**
 * @brief Broadcasts a notification about a newly created room to all clients.
 * @param text Input message body to forward.
 * @return int Output count of clients that received the broadcast.
 */
int send_broadcast_about_new_room(const char *text);
/**
 * @brief Sends the current room list to a client.
 * @param fd Input destination descriptor.
 * @return int Output number of bytes transmitted or -1 on error.
 */
int send_room_list(int fd);

#endif //RPS_09_NETWORK_H

