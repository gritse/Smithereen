package smithereen.model;

/**
 * One row of the dialog list: a person the user has exchanged messages with,
 * the latest message in either direction, and how many of their messages are still unread.
 */
public record MailDialog(int peerID, MailMessage lastMessage, int unreadCount){
}
