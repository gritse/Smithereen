package smithereen.controllers;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.BitSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import smithereen.ApplicationContext;
import smithereen.Utils;
import smithereen.activitypub.objects.ActivityPubObject;
import smithereen.activitypub.objects.Actor;
import smithereen.activitypub.objects.LocalImage;
import smithereen.exceptions.InternalServerErrorException;
import smithereen.model.Account;
import smithereen.model.Group;
import smithereen.model.LikedObjectID;
import smithereen.model.MailMessage;
import smithereen.model.PaginatedList;
import smithereen.model.Poll;
import smithereen.model.PollOption;
import smithereen.model.Post;
import smithereen.model.PostLikeObject;
import smithereen.model.User;
import smithereen.model.UserDataExport;
import smithereen.model.comments.Comment;
import smithereen.model.friends.FriendList;
import smithereen.model.media.MediaFileID;
import smithereen.model.media.MediaFileRecord;
import smithereen.model.media.MediaFileReferenceType;
import smithereen.model.media.MediaFileType;
import smithereen.model.media.UserDataArchiveMetadata;
import smithereen.model.notifications.RealtimeNotification;
import smithereen.model.photos.Photo;
import smithereen.model.photos.PhotoAlbum;
import smithereen.storage.MediaStorage;
import smithereen.storage.PostStorage;
import smithereen.storage.UserStorage;
import smithereen.storage.media.MediaFileStorageDriver;
import smithereen.util.JsonArrayBuilder;
import smithereen.util.JsonObjectBuilder;

/**
 * Builds a zip archive with all of a user's data as plain JSON files plus the referenced media files.
 */
public class UserDataExportWorker{
	private static final Logger LOG=LoggerFactory.getLogger(UserDataExportWorker.class);

	private final ApplicationContext context;
	private ExecutorService executor=new ThreadPoolExecutor(0, 1, 60, TimeUnit.SECONDS, new LinkedBlockingQueue<>(),
			Thread.ofPlatform().name("UserDataExportWorker-", 0).factory());

	public UserDataExportWorker(ApplicationContext context){
		this.context=context;
	}

	public void startExport(Account account){
		try{
			long id=UserStorage.createUserDataExport(account.user.id);
			executor.submit(()->doExport(account, id));
		}catch(SQLException x){
			throw new InternalServerErrorException(x);
		}
	}

	public void expireExports(){
		try{
			for(UserDataExport export:UserStorage.getUserDataExportsToExpire()){
				MediaStorage.deleteMediaFileReference(export.id, MediaFileReferenceType.USER_EXPORT_ARCHIVE, export.fileID);
				UserStorage.updateUserDataExport(export.id, 0, UserDataExport.State.EXPIRED, export.size);
			}
		}catch(Throwable x){
			LOG.error("Failed to expire user data exports", x);
		}
	}

	private void doExport(Account account, long id){
		User user=account.user;
		LOG.debug("Starting data export {} for user {}", id, user.id);
		try{
			File archive=File.createTempFile("TusovochkaUserExport", ".zip");
			HashSet<MediaFileID> filesToInclude=new HashSet<>();
			try(ZipOutputStream zos=new ZipOutputStream(new FileOutputStream(archive), StandardCharsets.UTF_8)){

				// Profile
				writeJsonElement(serializeProfile(user, filesToInclude), zos, "profile");

				// Friend lists
				List<FriendList> friendLists=context.getFriendsController().getFriendLists(user);
				writeJsonElement(friendLists.stream().map(fl->new JsonObjectBuilder().add("id", fl.id()).add("name", fl.name()).build()).collect(JsonArrayBuilder.COLLECTOR),
						zos, "friendLists");

				// Friends
				writePaginated(offset->{
					PaginatedList<User> users=context.getFriendsController().getFriends(user, offset, 500, FriendsController.SortOrder.HINTS);
					Map<Integer, BitSet> lists=context.getFriendsController().getFriendListsForUsers(user, user, users.list.stream().map(u->u.id).toList());
					return new PaginatedList<>(users, users.list.stream().map(u->{
						BitSet userLists=lists.get(u.id);
						JsonObject o=serializeActorLink(u);
						o.add("lists", userLists==null ? new JsonArrayBuilder().build() : userLists.stream().map(i->i+1).boxed().collect(JsonArrayBuilder.COLLECTOR));
						return o;
					}).toList());
				}, zos, "friends");

				// Followers & following
				writePaginated(offset->mapActors(context.getFriendsController().getFollowers(user, offset, 500)), zos, "followers");
				writePaginated(offset->mapActors(context.getFriendsController().getFollows(user, offset, 500)), zos, "following");

				// Groups & events
				writePaginated(offset->mapActors(context.getGroupsController().getUserGroups(user, user, offset, 500)), zos, "groups");
				writePaginated(offset->mapActors(context.getGroupsController().getUserEvents(user, GroupsController.EventsType.ALL, offset, 500)), zos, "events");

				// Bookmarks
				writePaginated(offset->{
					PaginatedList<Integer> ids=context.getBookmarksController().getBookmarkedUsers(user, offset, 500);
					Map<Integer, User> users=context.getUsersController().getUsers(ids.list);
					return new PaginatedList<>(ids, ids.list.stream().map(users::get).filter(Objects::nonNull).map(this::serializeActorLink).toList());
				}, zos, "bookmarkedUsers");
				writePaginated(offset->{
					PaginatedList<Integer> ids=context.getBookmarksController().getBookmarkedGroups(user, offset, 500);
					return new PaginatedList<>(ids, context.getGroupsController().getGroupsByIdAsList(ids.list).stream().map(this::serializeActorLink).toList());
				}, zos, "bookmarkedGroups");

				// Blocked users
				writePaginated(offset->mapActors(context.getPrivacyController().getBlockedUsers(user, offset, 500)), zos, "blockedUsers");

				// Word filters
				writeJsonElement(context.getNewsfeedController().getWordFilters(user, true).stream().map(wf->{
					return new JsonObjectBuilder()
							.add("id", wf.id)
							.add("name", wf.name)
							.add("contexts", wf.contexts.stream().map(c->c.toString().toLowerCase()).collect(JsonArrayBuilder.COLLECTOR))
							.add("words", wf.words.stream().collect(JsonArrayBuilder.COLLECTOR))
							.add("expires", wf.expiresAt==null ? 0 : wf.expiresAt.getEpochSecond())
							.build();
				}).collect(JsonArrayBuilder.COLLECTOR), zos, "wordFilters");

				// Wall posts & comments
				writePaginated(offset->{
					PaginatedList<Post> posts=context.getWallController().getWallPosts(user, user, WallController.WallMode.ALL, offset, 100);
					return new PaginatedList<>(posts, posts.list.stream().map(p->serializePost(p, filesToInclude)).toList());
				}, zos, "wall");
				writePaginated(offset->{
					try{
						PaginatedList<Post> posts=PostStorage.getWallComments(user.id, offset, 100);
						return new PaginatedList<>(posts, posts.list.stream().map(p->serializePost(p, filesToInclude)).toList());
					}catch(SQLException x){
						throw new InternalServerErrorException(x);
					}
				}, zos, "wallComments");

				// Private messages
				writePaginated(offset->{
					PaginatedList<MailMessage> msgs=context.getMailController().getInbox(user, offset, 100);
					return new PaginatedList<>(msgs, msgs.list.stream().map(m->serializeMessage(m, filesToInclude)).toList());
				}, zos, "messagesInbox");
				writePaginated(offset->{
					PaginatedList<MailMessage> msgs=context.getMailController().getOutbox(user, offset, 100);
					return new PaginatedList<>(msgs, msgs.list.stream().map(m->serializeMessage(m, filesToInclude)).toList());
				}, zos, "messagesOutbox");

				// Photo albums
				List<PhotoAlbum> albums=context.getPhotosController().getAllAlbumsIgnoringPrivacy(user);
				writeJsonElement(albums.stream().map(this::serializeAlbum).collect(JsonArrayBuilder.COLLECTOR), zos, "photoAlbums");
				for(PhotoAlbum album:albums){
					writePaginated(albumOffset->{
						PaginatedList<Photo> photos=context.getPhotosController().getAlbumPhotos(user, album, albumOffset, 100, false);
						return new PaginatedList<>(photos, photos.list.stream().map(p->serializePhoto(p, filesToInclude)).toList());
					}, zos, "photoAlbum_"+album.getIdString());
					writePaginated(commentsOffset->{
						PaginatedList<Comment> comments=context.getCommentsController().getPhotoAlbumComments(album, commentsOffset, 100);
						return new PaginatedList<>(comments, comments.list.stream().map(c->serializeComment(c, filesToInclude)).toList());
					}, zos, "photoAlbum_"+album.getIdString()+"_comments");
				}

				// Tagged photos
				writePaginated(offset->{
					PaginatedList<Photo> photos=context.getPhotosController().getUserTaggedPhotos(user, user, offset, 100, false);
					return new PaginatedList<>(photos, photos.list.stream().map(p->serializePhoto(p, filesToInclude)).toList());
				}, zos, "taggedPhotos");

				// Likes
				writePaginated(offset->{
					PaginatedList<LikedObjectID> ids=context.getUserInteractionsController().getLikedObjects(user, offset, 500);
					return new PaginatedList<>(ids, ids.list.stream().map(lid->new JsonObjectBuilder()
							.add("type", lid.type().toString().toLowerCase())
							.add("id", lid.id())
							.build()).toList());
				}, zos, "likes");

				for(MediaFileID fid:filesToInclude){
					try(InputStream in=MediaFileStorageDriver.getInstance().openStream(fid)){
						zos.putNextEntry(new ZipEntry(getMediaFileName(fid)));
						Utils.copyBytes(in, zos);
						zos.closeEntry();
					}catch(IOException x){
						LOG.warn("Failed to copy file {} to export {} for user {}", fid, id, user.id, x);
					}
				}
			}
			MediaFileRecord record=MediaStorage.createMediaFileRecord(MediaFileType.USER_EXPORT_ARCHIVE, archive.length(), user.id, new UserDataArchiveMetadata());
			UserDataExport export=UserStorage.getUserDataExport(id);
			MediaFileStorageDriver.getInstance().storeFile(archive, record.id(), false, export.getUserFriendlyFileName(account));
			UserStorage.updateUserDataExport(id, record.id().id(), UserDataExport.State.READY, record.size());
			MediaStorage.createMediaFileReference(record.id().id(), id, MediaFileReferenceType.USER_EXPORT_ARCHIVE, user.id);
			export=UserStorage.getUserDataExport(id);
			context.getNotificationsController().sendRealtimeNotifications(user, "exportReady"+System.currentTimeMillis(), RealtimeNotification.Type.EXPORT_READY, export, null, null);
			LOG.debug("Data export {} for user {} finished", id, user.id);
		}catch(Throwable x){
			LOG.error("Failed to generate data export {} for user {}", id, user.id, x);
			try{
				UserStorage.updateUserDataExport(id, 0, UserDataExport.State.FAILED, 0);
			}catch(SQLException xx){
				LOG.error("Failed to update data export {} status", id, xx);
			}
		}
	}

	private static String getMediaFileName(MediaFileID fid){
		return "media/"+fid.getEncodedID()+"."+fid.type().getFileExtension();
	}

	private void writeJsonElement(JsonElement el, ZipOutputStream zos, String name) throws IOException{
		zos.putNextEntry(new ZipEntry(name+".json"));
		zos.write(el.toString().getBytes(StandardCharsets.UTF_8));
		zos.closeEntry();
	}

	/**
	 * Writes a paginated list as a single JSON array file, fetching all pages.
	 */
	private void writePaginated(PaginatedListGetter listGetter, ZipOutputStream zos, String name) throws IOException{
		JsonArrayBuilder items=new JsonArrayBuilder();
		int offset=0, total;
		do{
			PaginatedList<JsonObject> list=listGetter.get(offset);
			total=list.total;
			offset+=Math.max(list.perPage, 1);
			for(JsonObject item:list.list){
				items.add(item);
			}
			if(list.list.isEmpty())
				break;
		}while(offset<total);
		writeJsonElement(items.build(), zos, name);
	}

	private PaginatedList<JsonObject> mapActors(PaginatedList<? extends Actor> list){
		return new PaginatedList<>(list, list.list.stream().map(this::serializeActorLink).toList());
	}

	private JsonObject serializeActorLink(Actor actor){
		JsonObjectBuilder b=new JsonObjectBuilder()
				.add("id", actor.getOwnerID())
				.add("username", actor.username)
				.add("name", actor.getName())
				.add("url", actor.getAbsoluteProfileURL());
		if(actor instanceof Group g)
			b.add("isEvent", g.isEvent());
		return b.build();
	}

	private JsonObject serializeProfile(User user, Set<MediaFileID> filesToInclude){
		JsonObjectBuilder b=new JsonObjectBuilder()
				.add("id", user.id)
				.add("username", user.username)
				.add("url", user.getAbsoluteProfileURL())
				.add("firstName", user.firstName)
				.add("lastName", user.lastName)
				.add("middleName", user.middleName)
				.add("maidenName", user.maidenName)
				.add("gender", user.gender==null ? null : user.gender.toString().toLowerCase())
				.add("birthDate", user.birthDate==null ? null : user.birthDate.toString())
				.add("about", user.summary)
				.add("aboutSource", user.aboutSource)
				.add("profileFields", user.serializeProfileFields())
				.add("privacySettings", Utils.gson.toJsonTree(user.privacySettings));
		if(user.status!=null && !user.status.isExpired())
			b.add("status", user.status.text());
		if(user.getAvatarImage() instanceof LocalImage li)
			b.add("avatar", serializeAttachment(li, filesToInclude));
		return b.build();
	}

	private JsonObject serializeAttachment(ActivityPubObject att, Set<MediaFileID> filesToInclude){
		JsonObjectBuilder b=new JsonObjectBuilder()
				.add("type", att.getType())
				.add("description", att.name)
				.add("mediaType", att.mediaType);
		if(att instanceof LocalImage li){
			b.add("width", li.width).add("height", li.height);
			if(li.fileRecord!=null){
				filesToInclude.add(li.fileRecord.id());
				b.add("file", getMediaFileName(li.fileRecord.id()));
			}
			if(li.photoID!=0)
				b.add("photoID", li.photoID);
		}else if(att.url!=null){
			b.add("url", att.url.toString());
		}
		return b.build();
	}

	private JsonArrayBuilder serializeAttachments(List<ActivityPubObject> attachments, Set<MediaFileID> filesToInclude){
		JsonArrayBuilder ab=new JsonArrayBuilder();
		if(attachments!=null){
			for(ActivityPubObject att:attachments){
				ab.add(serializeAttachment(att, filesToInclude));
			}
		}
		return ab;
	}

	private JsonObjectBuilder serializePostLikeObject(PostLikeObject p, Set<MediaFileID> filesToInclude){
		JsonObjectBuilder b=new JsonObjectBuilder()
				.add("authorID", p.authorID)
				.add("ownerID", p.ownerID)
				.add("text", p.text)
				.add("contentWarning", p.contentWarning)
				.add("createdAt", p.createdAt==null ? 0 : p.createdAt.getEpochSecond())
				.add("mentionedUserIDs", p.mentionedUserIDs.stream().collect(JsonArrayBuilder.COLLECTOR))
				.add("attachments", serializeAttachments(p.attachments, filesToInclude));
		if(p.updatedAt!=null)
			b.add("updatedAt", p.updatedAt.getEpochSecond());
		return b;
	}

	private JsonObject serializePost(Post p, Set<MediaFileID> filesToInclude){
		JsonObjectBuilder b=serializePostLikeObject(p, filesToInclude)
				.add("id", p.id)
				.add("url", p.getInternalURL().toString())
				.add("replyKey", p.replyKey.stream().collect(JsonArrayBuilder.COLLECTOR))
				.add("privacy", p.privacy.toString().toLowerCase());
		if(p.repostOf!=0)
			b.add("repostOf", p.repostOf);
		if(p.poll!=null)
			b.add("poll", serializePoll(p.poll));
		return b.build();
	}

	private JsonObject serializePoll(Poll poll){
		JsonArrayBuilder options=new JsonArrayBuilder();
		for(PollOption opt:poll.options){
			options.add(new JsonObjectBuilder().add("id", opt.id).add("text", opt.text).add("votes", opt.numVotes));
		}
		JsonObjectBuilder b=new JsonObjectBuilder()
				.add("question", poll.question)
				.add("multipleChoice", poll.multipleChoice)
				.add("anonymous", poll.anonymous)
				.add("options", options);
		if(poll.endTime!=null)
			b.add("endTime", poll.endTime.getEpochSecond());
		return b.build();
	}

	private JsonObject serializeComment(Comment c, Set<MediaFileID> filesToInclude){
		return serializePostLikeObject(c, filesToInclude)
				.add("id", c.getIDString())
				.add("url", c.getInternalURL().toString())
				.add("parentType", c.parentObjectID.type().toString().toLowerCase())
				.add("parentID", c.parentObjectID.getIdString())
				.add("replyKey", c.getReplyKeyAsStrings().stream().collect(JsonArrayBuilder.COLLECTOR))
				.build();
	}

	private JsonObject serializeMessage(MailMessage m, Set<MediaFileID> filesToInclude){
		JsonObjectBuilder b=new JsonObjectBuilder()
				.add("id", m.getIdString())
				.add("senderID", m.senderID)
				.add("to", m.to.stream().collect(JsonArrayBuilder.COLLECTOR))
				.add("cc", m.cc.stream().collect(JsonArrayBuilder.COLLECTOR))
				.add("subject", m.subject)
				.add("text", m.text)
				.add("createdAt", m.createdAt.getEpochSecond())
				.add("attachments", serializeAttachments(m.attachments, filesToInclude));
		if(m.updatedAt!=null)
			b.add("updatedAt", m.updatedAt.getEpochSecond());
		if(m.replyInfo!=null)
			b.add("replyInfo", Utils.gson.toJsonTree(m.replyInfo));
		return b.build();
	}

	private JsonObject serializeAlbum(PhotoAlbum album){
		JsonObjectBuilder b=new JsonObjectBuilder()
				.add("id", album.getIdString())
				.add("ownerID", album.ownerID)
				.add("title", album.title)
				.add("description", album.description)
				.add("numPhotos", album.numPhotos)
				.add("createdAt", album.createdAt==null ? 0 : album.createdAt.getEpochSecond())
				.add("updatedAt", album.updatedAt==null ? 0 : album.updatedAt.getEpochSecond());
		if(album.systemType!=null)
			b.add("systemType", album.systemType.toString().toLowerCase());
		if(album.viewPrivacy!=null)
			b.add("viewPrivacy", Utils.gson.toJsonTree(album.viewPrivacy));
		if(album.commentPrivacy!=null)
			b.add("commentPrivacy", Utils.gson.toJsonTree(album.commentPrivacy));
		return b.build();
	}

	private JsonObject serializePhoto(Photo photo, Set<MediaFileID> filesToInclude){
		JsonObjectBuilder b=new JsonObjectBuilder()
				.add("id", photo.getIdString())
				.add("url", photo.getAbsoluteURL())
				.add("ownerID", photo.ownerID)
				.add("authorID", photo.authorID)
				.add("albumID", Utils.encodeLong(smithereen.util.XTEA.obfuscateObjectID(photo.albumID, smithereen.model.ObfuscatedObjectIDType.PHOTO_ALBUM)))
				.add("description", photo.description)
				.add("createdAt", photo.createdAt==null ? 0 : photo.createdAt.getEpochSecond());
		if(photo.image instanceof LocalImage li)
			b.add("file", serializeAttachment(li, filesToInclude));
		return b.build();
	}

	private interface PaginatedListGetter{
		PaginatedList<JsonObject> get(int offset) throws IOException;
	}
}
