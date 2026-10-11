package smithereen.controllers;

import java.net.URI;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import smithereen.ApplicationContext;
import smithereen.Config;
import smithereen.exceptions.InternalServerErrorException;
import smithereen.exceptions.ObjectNotFoundException;
import smithereen.model.Group;
import smithereen.model.MailMessage;
import smithereen.model.ObfuscatedObjectIDType;
import smithereen.model.Post;
import smithereen.model.User;
import smithereen.model.board.BoardTopic;
import smithereen.model.comments.Comment;
import smithereen.model.photos.Photo;
import smithereen.model.photos.PhotoAlbum;
import smithereen.storage.MailStorage;
import smithereen.storage.PostStorage;
import smithereen.util.XTEA;

import static smithereen.Utils.*;

/**
 * Maps URLs on this server to the objects they point to. Only local URLs are ever resolved.
 */
public class ObjectLinkResolver{

	private static final Pattern POSTS=Pattern.compile("^/posts/(\\d+)$");
	private static final Pattern USERS=Pattern.compile("^/users/(\\d+)$");
	private static final Pattern GROUPS=Pattern.compile("^/groups/(\\d+)$");
	private static final Pattern MESSAGES=Pattern.compile("^/mail/messages/([a-zA-Z0-9_-]+)$");
	private static final Pattern ALBUMS=Pattern.compile("^/albums/([a-zA-Z0-9_-]+)$");
	private static final Pattern PHOTOS=Pattern.compile("^/photos/([a-zA-Z0-9_-]+)$");
	private static final Pattern COMMENTS=Pattern.compile("^/comments/([a-zA-Z0-9_-]+)$");
	private static final Pattern TOPICS=Pattern.compile("^/topics/([a-zA-Z0-9_-]+)$");
	private static final Pattern APPS=Pattern.compile("^/apps/(\\d+)$");
	private static final Pattern LOCAL_USERNAME=Pattern.compile("^/([a-zA-Z][a-zA-Z0-9._-]+)$");

	private final ApplicationContext context;

	public ObjectLinkResolver(ApplicationContext context){
		this.context=context;
	}

	private Post getPost(String _id) throws SQLException{
		int id=parseIntOrDefault(_id, 0);
		if(id==0)
			throw new ObjectNotFoundException("Invalid local post ID");
		Post post=PostStorage.getPostByID(id, false);
		if(post==null)
			throw new ObjectNotFoundException("Post with ID "+id+" not found");
		return post;
	}

	private User getUser(String _id){
		int id=parseIntOrDefault(_id, 0);
		if(id==0)
			throw new ObjectNotFoundException();
		return context.getUsersController().getLocalUserOrThrow(id);
	}

	private Group getGroup(String _id){
		int id=parseIntOrDefault(_id, 0);
		if(id==0)
			throw new ObjectNotFoundException();
		return context.getGroupsController().getLocalGroupOrThrow(id);
	}

	private static <T> T ensureTypeAndCast(Object obj, Class<T> type){
		if(type.isInstance(obj))
			return type.cast(obj);
		throw new IllegalStateException("Expected object of type "+type.getName()+", but got "+obj.getClass().getName()+" instead");
	}

	public <T> T resolveLocally(URI link, Class<T> expectedType){
		try{
			if(Config.isLocal(link)){
				Matcher matcher=POSTS.matcher(link.getPath());
				if(matcher.find()){
					return ensureTypeAndCast(getPost(matcher.group(1)), expectedType);
				}

				matcher=USERS.matcher(link.getPath());
				if(matcher.find()){
					return ensureTypeAndCast(getUser(matcher.group(1)), expectedType);
				}

				matcher=GROUPS.matcher(link.getPath());
				if(matcher.find()){
					return ensureTypeAndCast(getGroup(matcher.group(1)), expectedType);
				}

				matcher=MESSAGES.matcher(link.getPath());
				if(matcher.find()){
					long id=decodeLong(matcher.group(1));
					List<MailMessage> msgs=MailStorage.getMessages(Set.of(id));
					if(!msgs.isEmpty())
						return ensureTypeAndCast(msgs.getFirst(), expectedType);
				}

				matcher=ALBUMS.matcher(link.getPath());
				if(matcher.find()){
					long id=XTEA.deobfuscateObjectID(decodeLong(matcher.group(1)), ObfuscatedObjectIDType.PHOTO_ALBUM);
					return ensureTypeAndCast(context.getPhotosController().getAlbumIgnoringPrivacy(id), expectedType);
				}

				matcher=PHOTOS.matcher(link.getPath());
				if(matcher.find()){
					long id=XTEA.deobfuscateObjectID(decodeLong(matcher.group(1)), ObfuscatedObjectIDType.PHOTO);
					return ensureTypeAndCast(context.getPhotosController().getPhotoIgnoringPrivacy(id), expectedType);
				}

				matcher=COMMENTS.matcher(link.getPath());
				if(matcher.find()){
					long id=XTEA.deobfuscateObjectID(decodeLong(matcher.group(1)), ObfuscatedObjectIDType.COMMENT);
					return ensureTypeAndCast(context.getCommentsController().getCommentIgnoringPrivacy(id), expectedType);
				}

				matcher=TOPICS.matcher(link.getPath());
				if(matcher.find()){
					return ensureTypeAndCast(context.getBoardController().getTopicIgnoringPrivacy(XTEA.decodeObjectID(matcher.group(1), ObfuscatedObjectIDType.BOARD_TOPIC)), expectedType);
				}

				matcher=LOCAL_USERNAME.matcher(link.getPath());
				if(matcher.find()){
					String username=matcher.group(1);
					UsernameResolutionResult res=resolveUsernameLocally(username);
					return ensureTypeAndCast(switch(res.type){
						case USER -> context.getUsersController().getUserOrThrow(res.localID);
						case GROUP -> context.getGroupsController().getGroupOrThrow(res.localID);
						case APPLICATION -> context.getAppsController().getAppByID(res.localID);
					}, expectedType);
				}

				matcher=APPS.matcher(link.getPath());
				if(matcher.find()){
					return ensureTypeAndCast(context.getAppsController().getAppByID(safeParseLong(matcher.group(1))), expectedType);
				}
			}
		}catch(SQLException x){
			throw new InternalServerErrorException(x);
		}
		throw new ObjectNotFoundException("Can't resolve object link locally: "+link);
	}

	public UsernameResolutionResult resolveUsernameLocally(String username){
		return resolveUsername(username, EnumSet.allOf(UsernameOwnerType.class));
	}

	public UsernameResolutionResult resolveUsername(String username, EnumSet<UsernameOwnerType> allowedTypes){
		if(allowedTypes.isEmpty())
			throw new IllegalArgumentException("allowedTypes can't be empty");

		// Accept "name@this.server" and treat it as a plain local username
		if(username.contains("@")){
			String[] parts=username.split("@", 2);
			if(!parts[1].equalsIgnoreCase(Config.domain))
				throw new ObjectNotFoundException();
			username=parts[0];
		}

		if(allowedTypes.contains(UsernameOwnerType.USER)){
			int user=context.getUsersController().tryGetUserIdByUsername(username);
			if(user>0)
				return new UsernameResolutionResult(UsernameOwnerType.USER, user);
		}

		if(allowedTypes.contains(UsernameOwnerType.GROUP)){
			int group=context.getGroupsController().tryGetGroupIdForUsername(username);
			if(group>0)
				return new UsernameResolutionResult(UsernameOwnerType.GROUP, group);
		}

		if(allowedTypes.contains(UsernameOwnerType.APPLICATION)){
			long app=context.getAppsController().tryGetAppIdForUsername(username);
			if(app>0)
				return new UsernameResolutionResult(UsernameOwnerType.APPLICATION, (int)app);
		}

		throw new ObjectNotFoundException();
	}

	public static int getUserIDFromLocalURL(URI url){
		if(!Config.isLocal(url))
			return 0;
		Matcher matcher=USERS.matcher(url.getPath());
		if(!matcher.find())
			return 0;
		return Integer.parseInt(matcher.group(1));
	}

	public static ObjectTypeAndID getObjectIdFromLocalURL(URI uri){
		if(!Config.isLocal(uri))
			throw new IllegalArgumentException("Not a local URL");

		String path=uri.getPath();
		Matcher matcher=POSTS.matcher(path);
		if(matcher.find()){
			return new ObjectTypeAndID(ObjectType.POST, Integer.parseInt(matcher.group(1)));
		}

		matcher=USERS.matcher(path);
		if(matcher.find()){
			return new ObjectTypeAndID(ObjectType.USER, Integer.parseInt(matcher.group(1)));
		}

		matcher=GROUPS.matcher(path);
		if(matcher.find()){
			return new ObjectTypeAndID(ObjectType.GROUP, Integer.parseInt(matcher.group(1)));
		}

		matcher=MESSAGES.matcher(path);
		if(matcher.find()){
			return new ObjectTypeAndID(ObjectType.MESSAGE, decodeLong(matcher.group(1)));
		}

		matcher=ALBUMS.matcher(path);
		if(matcher.find()){
			return new ObjectTypeAndID(ObjectType.PHOTO_ALBUM, XTEA.deobfuscateObjectID(decodeLong(matcher.group(1)), ObfuscatedObjectIDType.PHOTO_ALBUM));
		}

		matcher=PHOTOS.matcher(path);
		if(matcher.find()){
			return new ObjectTypeAndID(ObjectType.PHOTO, XTEA.deobfuscateObjectID(decodeLong(matcher.group(1)), ObfuscatedObjectIDType.PHOTO));
		}

		matcher=COMMENTS.matcher(path);
		if(matcher.find()){
			return new ObjectTypeAndID(ObjectType.COMMENT, XTEA.deobfuscateObjectID(decodeLong(matcher.group(1)), ObfuscatedObjectIDType.COMMENT));
		}

		matcher=TOPICS.matcher(path);
		if(matcher.find()){
			return new ObjectTypeAndID(ObjectType.BOARD_TOPIC, XTEA.deobfuscateObjectID(decodeLong(matcher.group(1)), ObfuscatedObjectIDType.BOARD_TOPIC));
		}

		matcher=APPS.matcher(path);
		if(matcher.find()){
			return new ObjectTypeAndID(ObjectType.API_APPLICATION, safeParseLong(matcher.group(1)));
		}

		return null;
	}

	public static URI getLocalURLForObjectID(ObjectTypeAndID id){
		return switch(id.type){
			case USER -> Config.localURI("/users/"+id.id);
			case GROUP -> Config.localURI("/groups/"+id.id);
			case POST -> Config.localURI("/posts/"+id.id);
			case MESSAGE -> Config.localURI("/mail/messages/"+encodeLong(id.id));
			case PHOTO_ALBUM -> Config.localURI("/albums/"+XTEA.encodeObjectID(id.id, ObfuscatedObjectIDType.PHOTO_ALBUM));
			case PHOTO -> Config.localURI("/photos/"+XTEA.encodeObjectID(id.id, ObfuscatedObjectIDType.PHOTO));
			case COMMENT -> Config.localURI("/comments/"+XTEA.encodeObjectID(id.id, ObfuscatedObjectIDType.COMMENT));
			case USER_STATUS -> null;
			case GROUP_STATUS -> null;
			case BOARD_TOPIC -> Config.localURI("/topics/"+XTEA.encodeObjectID(id.id, ObfuscatedObjectIDType.BOARD_TOPIC));
			case API_APPLICATION -> Config.localURI("/apps/"+id.id);
		};
	}

	public static ObjectTypeAndID getObjectIdFromObject(Object obj){
		return switch(obj){
			case User user -> new ObjectTypeAndID(ObjectType.USER, user.id);
			case Group group -> new ObjectTypeAndID(ObjectType.GROUP, group.id);
			case Post post -> new ObjectTypeAndID(ObjectType.POST, post.id);
			case MailMessage msg -> new ObjectTypeAndID(ObjectType.MESSAGE, msg.id);
			case PhotoAlbum album -> new ObjectTypeAndID(ObjectType.PHOTO_ALBUM, album.id);
			case Photo photo -> new ObjectTypeAndID(ObjectType.PHOTO, photo.id);
			case Comment comment -> new ObjectTypeAndID(ObjectType.COMMENT, comment.id);
			case BoardTopic topic -> new ObjectTypeAndID(ObjectType.BOARD_TOPIC, topic.id);
			default -> null;
		};
	}

	public enum UsernameOwnerType{
		USER,
		GROUP,
		APPLICATION
	}

	public record UsernameResolutionResult(UsernameOwnerType type, int localID){}

	// Types for objects that group links can point to, stored in the `group_links` database table
	// They are FourCCs for easier extensibility
	public enum ObjectType{
		USER('U', 'S', 'E', 'R'),
		GROUP('G', 'R', 'U', 'P'),
		POST('P', 'O', 'S', 'T'),
		MESSAGE('D', 'M', 'S', 'G'),
		PHOTO_ALBUM('P', 'A', 'L', 'B'),
		PHOTO('P', 'H', 'T', 'O'),
		COMMENT('C', 'M', 'N', 'T'),
		USER_STATUS('U', 'S', 'T', 'A'),
		GROUP_STATUS('G', 'S', 'T', 'A'),
		BOARD_TOPIC('B', 'T', 'O', 'P'),
		API_APPLICATION('A', 'P', 'P', 'L');

		public final int id;

		ObjectType(char... id){
			this.id=((int)id[0] << 24) | ((int)id[1] << 16) | ((int)id[2] << 8) | (int)id[3];
		}

		public static ObjectType fromID(int id){
			for(ObjectType t:values()){
				if(t.id==id)
					return t;
			}
			throw new IllegalArgumentException("Unknown object type ID '"+decodeFourCC(id)+"'");
		}
	}

	public record ObjectTypeAndID(ObjectType type, long id){
		public int idInt(){
			return (int)id;
		}

		public static ObjectTypeAndID fromResultSet(ResultSet res) throws SQLException{
			return new ObjectTypeAndID(ObjectType.fromID(res.getInt("object_type")), res.getLong("object_id"));
		}
	}
}
