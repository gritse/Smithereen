package smithereen.storage;

import org.jetbrains.annotations.Nullable;

import java.net.URI;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import smithereen.Utils;
import smithereen.activitypub.objects.ActivityPubObject;
import smithereen.activitypub.objects.LocalImage;
import smithereen.model.MailDialog;
import smithereen.model.MailMessage;
import smithereen.model.MessagesPrivacyGrant;
import smithereen.model.PaginatedList;
import smithereen.model.media.MediaFileRecord;
import smithereen.storage.sql.DatabaseConnection;
import smithereen.storage.sql.DatabaseConnectionManager;
import smithereen.storage.sql.SQLQueryBuilder;
import smithereen.storage.utils.Pair;

public class MailStorage{
	public static PaginatedList<MailMessage> getInbox(int ownerID, int offset, int count) throws SQLException{
		try(DatabaseConnection conn=DatabaseConnectionManager.getConnection()){
			int total=new SQLQueryBuilder(conn)
					.selectFrom("mail_messages")
					.count()
					.where("owner_id=? AND sender_id<>? AND deleted_at IS NULL", ownerID, ownerID)
					.executeAndGetInt();
			if(total==0)
				return PaginatedList.emptyList(count);
			List<MailMessage> messages=new SQLQueryBuilder(conn)
					.selectFrom("mail_messages")
					.allColumns()
					.where("owner_id=? AND sender_id<>? AND deleted_at IS NULL", ownerID, ownerID)
					.limit(count, offset)
					.orderBy("created_at DESC")
					.executeAsStream(MailMessage::fromResultSet)
					.toList();
			postprocessMessages(messages);
			return new PaginatedList<>(messages, total, offset, count);
		}
	}

	public static PaginatedList<MailMessage> getOutbox(int ownerID, int offset, int count) throws SQLException{
		try(DatabaseConnection conn=DatabaseConnectionManager.getConnection()){
			int total=new SQLQueryBuilder(conn)
					.selectFrom("mail_messages")
					.count()
					.where("owner_id=? AND sender_id=? AND deleted_at IS NULL", ownerID, ownerID)
					.executeAndGetInt();
			if(total==0)
				return PaginatedList.emptyList(count);
			List<MailMessage> messages=new SQLQueryBuilder(conn)
					.selectFrom("mail_messages")
					.allColumns()
					.where("owner_id=? AND sender_id=? AND deleted_at IS NULL", ownerID, ownerID)
					.limit(count, offset)
					.orderBy("created_at DESC")
					.executeAsStream(MailMessage::fromResultSet)
					.toList();
			postprocessMessages(messages);
			return new PaginatedList<>(messages, total, offset, count);
		}
	}

	/**
	 * One row per person the user has exchanged messages with, newest dialog first.
	 * The row carries the latest non-deleted message in either direction and the number of unread incoming ones.
	 */
	public static PaginatedList<MailDialog> getDialogs(int ownerID, int offset, int count) throws SQLException{
		try(DatabaseConnection conn=DatabaseConnectionManager.getConnection()){
			PreparedStatement stmt=SQLQueryBuilder.prepareStatement(conn, "SELECT COUNT(DISTINCT peer_id) FROM mail_messages_peers JOIN mail_messages ON message_id=mail_messages.id"+
					" WHERE mail_messages_peers.owner_id=? AND mail_messages.deleted_at IS NULL", ownerID);
			int total=DatabaseUtils.oneFieldToInt(stmt.executeQuery());
			if(total==0)
				return PaginatedList.emptyList(count);
			stmt=SQLQueryBuilder.prepareStatement(conn, "SELECT peer_id, MAX(message_id) AS last_message_id,"+
					" SUM(mail_messages.sender_id<>mail_messages_peers.owner_id AND mail_messages.read_receipts IS NULL) AS unread_count"+
					" FROM mail_messages_peers JOIN mail_messages ON message_id=mail_messages.id"+
					" WHERE mail_messages_peers.owner_id=? AND mail_messages.deleted_at IS NULL"+
					" GROUP BY peer_id ORDER BY last_message_id DESC LIMIT ? OFFSET ?", ownerID, count, offset);
			record DialogRow(int peerID, long lastMessageID, int unreadCount){}
			List<DialogRow> rows=new ArrayList<>();
			try(ResultSet res=stmt.executeQuery()){
				while(res.next()){
					rows.add(new DialogRow(res.getInt("peer_id"), res.getLong("last_message_id"), res.getInt("unread_count")));
				}
			}
			if(rows.isEmpty())
				return new PaginatedList<>(List.of(), total, offset, count);
			List<MailMessage> messages=new SQLQueryBuilder(conn)
					.selectFrom("mail_messages")
					.allColumns()
					.whereIn("id", rows.stream().map(DialogRow::lastMessageID).collect(Collectors.toSet()))
					.executeAsStream(MailMessage::fromResultSet)
					.toList();
			postprocessMessages(messages);
			Map<Long, MailMessage> messagesByID=messages.stream().collect(Collectors.toMap(m->m.id, Function.identity()));
			List<MailDialog> dialogs=rows.stream()
					.filter(r->messagesByID.containsKey(r.lastMessageID()))
					.map(r->new MailDialog(r.peerID(), messagesByID.get(r.lastMessageID()), r.unreadCount()))
					.toList();
			return new PaginatedList<>(dialogs, total, offset, count);
		}
	}

	public static long createMessage(String text, String subject, String attachments, int senderID, Set<Integer> to, Set<Integer> cc, Set<Integer> localOwners, Map<Integer, MailMessage.ReplyInfo> replyInfos, Map<Integer, Long> allIDs) throws SQLException{
		try(DatabaseConnection conn=DatabaseConnectionManager.getConnection()){
			long[] _id={0};
			DatabaseUtils.doWithTransaction(conn, ()->{
				// Step 1. Insert messages and collect their IDs
				Set<Long> messageIDs=new HashSet<>();
				for(int ownerID:localOwners){
					String replyInfo=null;
					if(replyInfos.containsKey(ownerID)){
						replyInfo=Utils.gson.toJson(replyInfos.get(ownerID));
					}
					long id=new SQLQueryBuilder(conn)
							.insertInto("mail_messages")
							.value("owner_id", ownerID)
							.value("sender_id", senderID)
							.value("to", Utils.serializeIntList(to))
							.value("cc", Utils.serializeIntList(cc))
							.value("text", text)
							.value("subject", subject)
							.value("attachments", attachments)
							.value("reply_info", replyInfo)
							.executeAndGetIDLong();
					if(allIDs!=null)
						allIDs.put(ownerID, id);
					if(ownerID==senderID)
						_id[0]=id;
					messageIDs.add(id);
					Set<Integer> peerIDs=new HashSet<>();
					peerIDs.add(senderID);
					peerIDs.addAll(to);
					if(cc!=null)
						peerIDs.addAll(cc);
					peerIDs.remove(ownerID);
					for(int peerID: peerIDs){
						new SQLQueryBuilder(conn)
								.insertInto("mail_messages_peers")
								.value("owner_id", ownerID)
								.value("peer_id", peerID)
								.value("message_id", id)
								.executeNoResult();
					}
				}
				// Step 2. Update `related_message_ids` so they all point to each other
				if(messageIDs.size()>1){
					for(long id:messageIDs){
						Set<Long> relatedIDs=new HashSet<>(messageIDs);
						relatedIDs.remove(id);
						new SQLQueryBuilder(conn)
								.update("mail_messages")
								.where("id=?", id)
								.value("related_message_ids", Utils.serializeLongCollection(relatedIDs))
								.executeNoResult();
					}
				}
			});
			if(localOwners.contains(senderID))
				return _id[0];
			return 0;
		}
	}

	public static void deleteMessage(int ownerID, long messageID) throws SQLException{
		new SQLQueryBuilder()
				.update("mail_messages")
				.valueExpr("deleted_at", "CURRENT_TIMESTAMP()")
				.where("id=? AND owner_id=?", messageID, ownerID)
				.executeNoResult();
	}

	public static void restoreMessage(int ownerID, long messageID) throws SQLException{
		new SQLQueryBuilder()
				.update("mail_messages")
				.value("deleted_at", null)
				.where("id=? AND owner_id=?", messageID, ownerID)
				.executeNoResult();
	}

	public static void actuallyDeleteMessages(Collection<Long> ids) throws SQLException{
		new SQLQueryBuilder()
				.deleteFrom("mail_messages")
				.whereIn("id", ids)
				.executeNoResult();
	}

	public static void actuallyDeleteMessagesByObjects(Collection<MailMessage> msgs) throws SQLException{
		try(DatabaseConnection conn=DatabaseConnectionManager.getConnection()){
			new SQLQueryBuilder(conn)
					.deleteFrom("mail_messages")
					.whereIn("id", msgs.stream().map(m->m.id).collect(Collectors.toSet()))
					.executeNoResult();
		}
	}

	public static @Nullable MailMessage getMessage(int ownerID, long messageID, boolean wantDeleted) throws SQLException{
		String where="id=? AND owner_id=?";
		if(!wantDeleted)
			where+=" AND deleted_at IS NULL";
		MailMessage msg=new SQLQueryBuilder()
				.selectFrom("mail_messages")
				.where(where, messageID, ownerID)
				.executeAndGetSingleObject(MailMessage::fromResultSet);
		if(msg==null)
			return null;
		postprocessMessages(Set.of(msg));
		return msg;
	}

	public static Map<Long, MailMessage> getMessagesByIDs(int ownerID, Collection<Long> ids, boolean wantDeleted) throws SQLException{
		String where="owner_id=?";
		if(!wantDeleted)
			where+=" AND deleted_at IS NULL";
		Map<Long, MailMessage> msgs=new SQLQueryBuilder()
				.selectFrom("mail_messages")
				.whereIn("id", ids)
				.andWhere(where, ownerID)
				.executeAsStream(MailMessage::fromResultSet)
				.collect(Collectors.toMap(m->m.id, Function.identity()));
		postprocessMessages(msgs.values());
		return msgs;
	}

	public static void addMessageReadReceipt(int ownerID, Collection<Long> messageIDs, int readByUserID) throws SQLException{
		new SQLQueryBuilder()
				.update("mail_messages")
				.valueExpr("read_receipts", "CONCAT(IFNULL(read_receipts, ''), ?)", (Object)Utils.serializeIntArray(new int[]{readByUserID}))
				.whereIn("id", messageIDs.stream().map(id->id).collect(Collectors.toSet()))
				.andWhere("owner_id=? AND deleted_at IS NULL", ownerID)
				.executeNoResult();
	}

	public static int getUnreadMessagesCount(int ownerID) throws SQLException{
		return new SQLQueryBuilder()
				.selectFrom("mail_messages")
				.count()
				.where("owner_id=? AND sender_id<>owner_id AND read_receipts IS NULL", ownerID)
				.executeAndGetInt();
	}

	public static List<MailMessage> getMessages(Collection<Long> ids) throws SQLException{
		List<MailMessage> msgs=new SQLQueryBuilder()
				.selectFrom("mail_messages")
				.allColumns()
				.whereIn("id", ids.stream().map(id->id).collect(Collectors.toSet()))
				.executeAsStream(MailMessage::fromResultSet)
				.toList();
		postprocessMessages(msgs);
		return msgs;
	}

	public static List<MailMessage> getRecentlyDeletedMessages(Instant before) throws SQLException{
		List<MailMessage> msgs=new SQLQueryBuilder()
				.selectFrom("mail_messages")
				.allColumns()
				.where("deleted_at IS NOT NULL AND deleted_at<?", before)
				.executeAsStream(MailMessage::fromResultSet)
				.toList();
		postprocessMessages(msgs);
		return msgs;
	}

	public static void createOrRenewPrivacyGrant(int ownerID, int userID, int msgCount) throws SQLException{
		new SQLQueryBuilder()
				.insertInto("mail_privacy_grants")
				.value("owner_id", ownerID)
				.value("user_id", userID)
				.value("messages_remain", msgCount)
				.valueExpr("created_at", "CURRENT_TIMESTAMP()")
				.onDuplicateKeyUpdate()
				.executeNoResult();
	}

	public static MessagesPrivacyGrant getPrivacyGrant(int ownerID, int userID) throws SQLException{
		return new SQLQueryBuilder()
				.selectFrom("mail_privacy_grants")
				.where("owner_id=? AND user_id=?", ownerID, userID)
				.executeAndGetSingleObject(MessagesPrivacyGrant::fromResultSet);
	}

	public static void consumePrivacyGrant(int ownerID, int userID) throws SQLException{
		new SQLQueryBuilder()
				.update("mail_privacy_grants")
				.valueExpr("messages_remain", "GREATEST(messages_remain-1, 0)")
				.where("owner_id=? AND user_id=?", ownerID, userID)
				.executeNoResult();
	}

	public static PaginatedList<MailMessage> getHistory(int ownerID, int peerID, int offset, int count) throws SQLException{
		try(DatabaseConnection conn=DatabaseConnectionManager.getConnection()){
			PreparedStatement stmt=SQLQueryBuilder.prepareStatement(conn, "SELECT COUNT(*) FROM mail_messages_peers JOIN mail_messages ON message_id=mail_messages.id" +
					" WHERE mail_messages_peers.owner_id=? AND mail_messages_peers.peer_id=? AND mail_messages.deleted_at IS NULL", ownerID, peerID);
			int total=DatabaseUtils.oneFieldToInt(stmt.executeQuery());
			if(total==0)
				return PaginatedList.emptyList(count);
			stmt=SQLQueryBuilder.prepareStatement(conn, "SELECT mail_messages.* FROM mail_messages_peers JOIN mail_messages ON message_id=mail_messages.id" +
					" WHERE mail_messages_peers.owner_id=? AND mail_messages_peers.peer_id=? AND mail_messages.deleted_at IS NULL ORDER BY message_id DESC LIMIT ? OFFSET ?", ownerID, peerID, count, offset);
			List<MailMessage> msgs=DatabaseUtils.resultSetToObjectStream(stmt.executeQuery(), MailMessage::fromResultSet, null).toList();
			postprocessMessages(msgs);
			return new PaginatedList<>(msgs, total, offset, count);
		}
	}

	/**
	 * Messages exchanged with a peer that are newer than the given message ID, oldest first.
	 */
	public static List<MailMessage> getHistoryAfter(int ownerID, int peerID, long afterID, int count) throws SQLException{
		try(DatabaseConnection conn=DatabaseConnectionManager.getConnection()){
			PreparedStatement stmt=SQLQueryBuilder.prepareStatement(conn, "SELECT mail_messages.* FROM mail_messages_peers JOIN mail_messages ON message_id=mail_messages.id" +
					" WHERE mail_messages_peers.owner_id=? AND mail_messages_peers.peer_id=? AND mail_messages.deleted_at IS NULL AND message_id>? ORDER BY message_id ASC LIMIT ?", ownerID, peerID, afterID, count);
			List<MailMessage> msgs=DatabaseUtils.resultSetToObjectStream(stmt.executeQuery(), MailMessage::fromResultSet, null).toList();
			postprocessMessages(msgs);
			return msgs;
		}
	}

	public static Map<Long, MailMessage> getMessagesAsModerator(Collection<Long> ids) throws SQLException{
		Map<Long, MailMessage> msgs=new SQLQueryBuilder()
				.selectFrom("mail_messages")
				.whereIn("id", ids)
				.executeAsStream(MailMessage::fromResultSet)
				.collect(Collectors.toMap(m->m.id, Function.identity()));
		postprocessMessages(msgs.values());
		return msgs;
	}

	private static void postprocessMessages(Collection<MailMessage> messages) throws SQLException{
		Set<Long> needFileIDs=messages.stream()
				.filter(p->p.attachments!=null && !p.attachments.isEmpty())
				.flatMap(p->p.attachments.stream())
				.map(att->att instanceof LocalImage li ? li.fileID : 0L)
				.filter(id->id!=0)
				.collect(Collectors.toSet());
		if(needFileIDs.isEmpty())
			return;
		Map<Long, MediaFileRecord> mediaFiles=MediaStorage.getMediaFileRecords(needFileIDs);
		for(MailMessage msg:messages){
			if(msg.attachments!=null){
				for(ActivityPubObject attachment:msg.attachments){
					if(attachment instanceof LocalImage li){
						MediaFileRecord mfr=mediaFiles.get(li.fileID);
						if(mfr!=null)
							li.fillIn(mfr);
					}
				}
			}
		}
	}
}
