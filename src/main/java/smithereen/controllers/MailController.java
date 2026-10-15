package smithereen.controllers;

import com.google.gson.JsonArray;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.SQLException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import smithereen.ApplicationContext;
import smithereen.Utils;
import smithereen.model.objects.StoredObject;
import smithereen.model.objects.Actor;
import smithereen.model.media.MediaFileRef;
import smithereen.exceptions.BadRequestException;
import smithereen.exceptions.InternalServerErrorException;
import smithereen.exceptions.ObjectNotFoundException;
import smithereen.exceptions.UserActionNotAllowedException;
import smithereen.model.MailDialog;
import smithereen.model.MailMessage;
import smithereen.model.MessagesPrivacyGrant;
import smithereen.model.PaginatedList;
import smithereen.model.Post;
import smithereen.model.User;
import smithereen.model.UserPrivacySettingKey;
import smithereen.model.media.MediaFileReferenceType;
import smithereen.model.notifications.RealtimeNotification;
import smithereen.model.notifications.UserNotifications;
import smithereen.storage.MailStorage;
import smithereen.storage.MediaStorage;
import smithereen.storage.MediaStorageUtils;
import smithereen.storage.NotificationsStorage;
import smithereen.text.FormattedTextFormat;
import smithereen.text.TextProcessor;
import smithereen.util.BackgroundTaskRunner;
import spark.utils.StringUtils;

public class MailController{
	private static final Logger LOG=LoggerFactory.getLogger(MailController.class);
	private final ApplicationContext context;
	private static final Pattern AP_ID_PATTERN=Pattern.compile("^/activitypub/objects/messages/([a-zA-Z0-9_-]+)$");
	private final HashMap<String, MessageGuidInfo> guids=new HashMap<>();

	public MailController(ApplicationContext context){
		this.context=context;
	}

	public PaginatedList<MailMessage> getInbox(User self, int offset, int count){
		try{
			return MailStorage.getInbox(self.id, offset, count);
		}catch(SQLException x){
			throw new InternalServerErrorException(x);
		}
	}

	public PaginatedList<MailDialog> getDialogs(User self, int offset, int count){
		try{
			return MailStorage.getDialogs(self.id, offset, count);
		}catch(SQLException x){
			throw new InternalServerErrorException(x);
		}
	}

	public PaginatedList<MailMessage> getOutbox(User self, int offset, int count){
		try{
			return MailStorage.getOutbox(self.id, offset, count);
		}catch(SQLException x){
			throw new InternalServerErrorException(x);
		}
	}

	public MailMessage getMessage(User self, long id, boolean wantDeleted){
		try{
			MailMessage msg=MailStorage.getMessage(self.id, id, wantDeleted);
			if(msg==null)
				throw new ObjectNotFoundException();
			return msg;
		}catch(SQLException x){
			throw new InternalServerErrorException(x);
		}
	}

	public Map<Long, MailMessage> getMessagesByIDs(User self, Collection<Long> ids, boolean wantDeleted){
		if(ids.isEmpty())
			return Map.of();
		try{
			return MailStorage.getMessagesByIDs(self.id, ids, wantDeleted);
		}catch(SQLException x){
			throw new InternalServerErrorException(x);
		}
	}

	public long sendMessage(User self, @NotNull Set<User> _to, @Nullable String text, @NotNull FormattedTextFormat textFormat, @Nullable String subject, @NotNull List<String> attachmentIDs, @NotNull Map<String, String> attachAltTexts, @Nullable MailMessage inReplyTo, @Nullable String guid){
		if(StringUtils.isNotEmpty(guid)){
			MessageGuidInfo guidInfo=guids.get(guid);
			if(guidInfo!=null && guidInfo.time.isAfter(Instant.now().minus(1, ChronoUnit.HOURS))){
				try{
					return getMessage(self, guidInfo.msgID, false).id;
				}catch(ObjectNotFoundException ignore){}
			}
		}
		try{
			if(StringUtils.isEmpty(text) && attachmentIDs.isEmpty())
				throw new BadRequestException();
			Set<User> to=_to.stream().filter(u->context.getPrivacyController().checkUserPrivacy(self, u, UserPrivacySettingKey.PRIVATE_MESSAGES)).collect(Collectors.toSet());
			if(to.isEmpty())
				throw new UserActionNotAllowedException();
			if(StringUtils.isNotEmpty(text)){
				text=TextProcessor.preprocessPostText(text, null, textFormat);
			}
			int maxAttachments=10;
			String attachments=null;
			ArrayList<MediaFileRef> attachObjects=new ArrayList<>();
			if(!attachmentIDs.isEmpty()){
				MediaStorageUtils.fillAttachmentObjects(context, self, attachObjects, attachmentIDs, attachAltTexts, 0, maxAttachments, false);
				if(!attachObjects.isEmpty()){
					if(attachObjects.size()==1){
						attachments=attachObjects.get(0).toJson().toString();
					}else{
						JsonArray ar=new JsonArray();
						for(MediaFileRef o:attachObjects){
							ar.add(o.toJson());
						}
						attachments=ar.toString();
					}
				}
			}
			Map<Integer, MailMessage.ReplyInfo> replyInfos;
			if(inReplyTo==null){
				replyInfos=Map.of();
			}else{
				replyInfos=new HashMap<>();
				replyInfos.put(self.id, new MailMessage.ReplyInfo(MailMessage.ParentObjectType.MESSAGE, inReplyTo.id));
				if(!inReplyTo.relatedMessageIDs.isEmpty()){
					for(MailMessage msg:MailStorage.getMessages(inReplyTo.relatedMessageIDs)){
						replyInfos.put(msg.ownerID, new MailMessage.ReplyInfo(MailMessage.ParentObjectType.MESSAGE, msg.id));
					}
				}
			}
			Set<Integer> localOwners=new HashSet<>();
			localOwners.add(self.id);
			boolean hasForeignRecipients=false;
			for(User user:to){
				localOwners.add(user.id);
				UserNotifications un=NotificationsStorage.getNotificationsFromCache(user.id);
				if(un!=null)
					un.incUnreadMailCount(1);
			}
			HashMap<Integer, Long> allMessageIDs=new HashMap<>();
			long id=MailStorage.createMessage(text, Objects.requireNonNullElse(subject, ""), attachments, self.id, to.stream().map(u->u.id).collect(Collectors.toSet()), null, localOwners, replyInfos, allMessageIDs);

			if(StringUtils.isNotEmpty(guid)){
				synchronized(guids){
					guids.put(guid, new MessageGuidInfo(id, Instant.now()));
				}
			}

			for(MediaFileRef li:attachObjects){
				for(Map.Entry<Integer, Long> mid:allMessageIDs.entrySet()){
					MediaStorage.createMediaFileReference(li.fileID, mid.getValue(), MediaFileReferenceType.MAIL_ATTACHMENT, mid.getKey());
				}
			}
			for(User user:to){
				MessagesPrivacyGrant grant=MailStorage.getPrivacyGrant(user.id, self.id);
				if(grant!=null && grant.isValid()){
					MailStorage.consumePrivacyGrant(user.id, self.id);
				}
				MailMessage msg=MailStorage.getMessage(user.id, allMessageIDs.get(user.id), false);
				context.getNotificationsController().sendRealtimeNotifications(user, "msg"+msg.getIdString(), RealtimeNotification.Type.MAIL_MESSAGE, msg, null, self);

				context.getFriendsController().incrementHintsRank(self, user, 5);
			}
			if(hasForeignRecipients){
				MailMessage msg=MailStorage.getMessage(self.id, id, false);
			}
			BackgroundTaskRunner.getInstance().submit(()->{
				Set<User> usersToGrant=to.stream()
						.filter(u->!context.getPrivacyController().checkUserPrivacy(u, self, self.getPrivacySetting(UserPrivacySettingKey.PRIVATE_MESSAGES)))
						.collect(Collectors.toSet());
				if(usersToGrant.isEmpty())
					return;
				try{
					for(User user:usersToGrant){
						MailStorage.createOrRenewPrivacyGrant(self.id, user.id, 10);
					}
				}catch(SQLException x){
					LOG.warn("Failed to update privacy grants", x);
				}
			});
			return id;
		}catch(SQLException x){
			throw new InternalServerErrorException(x);
		}
	}

	public void markMessageRead(User self, MailMessage message){
		if(message.readReceipts.contains(self.id))
			return;
		if(message.senderID==self.id)
			throw new BadRequestException("Can't mark outgoing messages as read");
		if(!message.to.contains(self.id) && !message.cc.contains(self.id))
			throw new UserActionNotAllowedException("Not a recipient of this message");
		try{
			HashSet<Long> ids=new HashSet<>(message.relatedMessageIDs);
			// User's own copy
			MailStorage.addMessageReadReceipt(self.id, Set.of(message.id), self.id);
			UserNotifications un=NotificationsStorage.getNotificationsFromCache(self.id);
			if(un!=null)
				un.incUnreadMailCount(-1);
			context.getNotificationsController().sendRealtimeCountersUpdates(self);

			// Sender's copy
			if(!ids.isEmpty()){
				MailStorage.addMessageReadReceipt(message.senderID, ids, self.id);
			}

		}catch(SQLException x){
			throw new InternalServerErrorException(x);
		}
	}

	public void deleteMessage(User self, MailMessage message){
		try{
			MailStorage.deleteMessage(self.id, message.id);
			if(message.isUnread() && message.senderID!=self.id){
				UserNotifications un=NotificationsStorage.getNotificationsFromCache(self.id);
				if(un!=null)
					un.incUnreadMailCount(-1);
				context.getNotificationsController().sendRealtimeCountersUpdates(self);
			}
		}catch(SQLException x){
			throw new InternalServerErrorException(x);
		}
	}

	public void actuallyDeleteMessage(User self, MailMessage message, boolean deleteRelated){
		try{
			if(message.ownerID!=self.id || message.senderID!=self.id){
				throw new UserActionNotAllowedException("This user can't delete this message");
			}
			Set<Long> idsToDelete=new HashSet<>();
			idsToDelete.add(message.id);
			if(message.attachments!=null && !message.attachments.isEmpty()){
				MediaStorage.deleteMediaFileReferences(message.id, MediaFileReferenceType.MAIL_ATTACHMENT);
			}
			HashSet<Integer> affectedUsers=new HashSet<>();
			if(message.ownerID!=self.id){
				UserNotifications un=NotificationsStorage.getNotificationsFromCache(message.ownerID);
				if(un!=null)
					un.incUnreadMailCount(-1);
				affectedUsers.add(message.ownerID);
			}
			if(deleteRelated && !message.relatedMessageIDs.isEmpty()){
				for(MailMessage msg:MailStorage.getMessages(message.relatedMessageIDs)){
					if(msg.isUnread()){
						idsToDelete.add(msg.id);
						if(message.attachments!=null && !message.attachments.isEmpty()){
							MediaStorage.deleteMediaFileReferences(msg.id, MediaFileReferenceType.MAIL_ATTACHMENT);
						}
						UserNotifications un=NotificationsStorage.getNotificationsFromCache(msg.ownerID);
						if(un!=null)
							un.incUnreadMailCount(-1);
						affectedUsers.add(msg.ownerID);
					}
				}
			}
			MailStorage.actuallyDeleteMessages(idsToDelete);
			Map<Integer, User> users=context.getUsersController().getUsers(affectedUsers);
			for(User user:users.values()){
				context.getNotificationsController().sendRealtimeCountersUpdates(user);
			}
		}catch(SQLException x){
			throw new InternalServerErrorException(x);
		}
	}

	public void restoreMessage(User self, MailMessage message){
		try{
			MailStorage.restoreMessage(self.id, message.id);
			if(message.isUnread() && message.senderID!=self.id){
				UserNotifications un=NotificationsStorage.getNotificationsFromCache(self.id);
				if(un!=null)
					un.incUnreadMailCount(1);
				context.getNotificationsController().sendRealtimeCountersUpdates(self);
			}
		}catch(SQLException x){
			throw new InternalServerErrorException(x);
		}
	}

	public static void deleteRestorableMessages(){
		try{
			List<MailMessage> messages=MailStorage.getRecentlyDeletedMessages(Instant.now().minus(10, ChronoUnit.MINUTES));
			if(messages.isEmpty())
				return;
			MailStorage.actuallyDeleteMessagesByObjects(messages);
			for(MailMessage message:messages){
				if(message.attachments!=null && !message.attachments.isEmpty()){
					MediaStorage.deleteMediaFileReferences(message.id, MediaFileReferenceType.MAIL_ATTACHMENT);
				}
			}
		}catch(SQLException x){
			LOG.warn("Failed to permanently delete restorable messages", x);
		}
	}

	public PaginatedList<MailMessage> getHistory(User self, User peer, int offset, int count){
		try{
			return MailStorage.getHistory(self.id, peer.id, offset, count);
		}catch(SQLException x){
			throw new InternalServerErrorException(x);
		}
	}

	public List<MailMessage> getHistoryAfter(User self, User peer, long afterID, int count){
		try{
			return MailStorage.getHistoryAfter(self.id, peer.id, afterID, count);
		}catch(SQLException x){
			throw new InternalServerErrorException(x);
		}
	}

	/**
	 * Marks every unread incoming message in the list as read.
	 */
	public void markIncomingMessagesRead(User self, List<MailMessage> messages){
		for(MailMessage msg:messages){
			if(msg.senderID!=self.id && msg.isUnread() && (msg.to.contains(self.id) || msg.cc.contains(self.id)))
				markMessageRead(self, msg);
		}
	}

	public Map<Long, MailMessage> getMessagesAsModerator(Collection<Long> ids){
		if(ids.isEmpty())
			return Map.of();
		try{
			return MailStorage.getMessagesAsModerator(ids);
		}catch(SQLException x){
			throw new InternalServerErrorException(x);
		}
	}

	public void removeExpiredGuids(){
		synchronized(guids){
			Instant hourAgo=Instant.now().minus(1, ChronoUnit.HOURS);
			guids.entrySet().removeIf(e->e.getValue().time.isBefore(hourAgo));
		}
	}

	private record MessageGuidInfo(long msgID, Instant time){}
}
