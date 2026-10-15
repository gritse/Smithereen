package smithereen.controllers;

import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import smithereen.ApplicationContext;
import smithereen.Config;
import smithereen.model.objects.Actor;
import smithereen.exceptions.BadRequestException;
import smithereen.exceptions.InaccessibleGroupException;
import smithereen.exceptions.InaccessibleProfileException;
import smithereen.exceptions.InternalServerErrorException;
import smithereen.exceptions.UserActionNotAllowedException;
import smithereen.exceptions.UserContentUnavailableException;
import smithereen.exceptions.UserErrorException;
import smithereen.model.Account;
import smithereen.model.PaginatedList;
import smithereen.model.friends.FriendshipStatus;
import smithereen.model.Group;
import smithereen.model.MessagesPrivacyGrant;
import smithereen.model.OwnedContentObject;
import smithereen.model.OwnerAndAuthor;
import smithereen.model.Post;
import smithereen.model.PrivacySetting;
import smithereen.model.User;
import smithereen.model.UserPrivacySettingKey;
import smithereen.model.admin.UserRole;
import smithereen.model.board.BoardTopic;
import smithereen.model.comments.Comment;
import smithereen.model.comments.CommentableContentObject;
import smithereen.model.feed.FriendsNewsfeedTypeFilter;
import smithereen.model.friends.FriendList;
import smithereen.model.groups.GroupFeatureState;
import smithereen.model.photos.Photo;
import smithereen.model.photos.PhotoAlbum;
import smithereen.model.viewmodel.PostViewModel;
import smithereen.storage.GroupStorage;
import smithereen.storage.MailStorage;
import smithereen.storage.PhotoStorage;
import smithereen.storage.UserStorage;
import smithereen.text.TextProcessor;
import spark.Request;
import spark.utils.StringUtils;

public class PrivacyController{
	private static final Logger LOG=LoggerFactory.getLogger(PrivacyController.class);
	private final ApplicationContext context;

	public PrivacyController(ApplicationContext context){
		this.context=context;
	}

	public void enforceObjectPrivacy(@Nullable User self, @NotNull OwnedContentObject object){
		OwnerAndAuthor oaa=context.getWallController().getContentAuthorAndOwner(object);
		if(oaa.owner() instanceof User user)
			enforceUserProfileAccess(self, user);
		else if(oaa.owner() instanceof Group group)
			enforceUserAccessToGroupProfile(self, group);
		if(object instanceof Post post){
			if(post.ownerID<0){
				Group group=context.getGroupsController().getGroupOrThrow(-post.ownerID);
				enforceUserAccessToGroupContent(self, group);
				if(group.wallState==GroupFeatureState.DISABLED)
					throw new UserActionNotAllowedException("err_access_content");
			}else if(post.ownerID!=post.authorID){
				if(post.getReplyLevel()==0){
					enforceUserPrivacy(self, context.getUsersController().getUserOrThrow(post.ownerID), UserPrivacySettingKey.WALL_OTHERS_POSTS);
				}else{
					Post top=context.getWallController().getPostOrThrow(post.replyKey.getFirst());
					if(top.ownerID!=top.authorID){
						enforceUserPrivacy(self, context.getUsersController().getUserOrThrow(top.ownerID), UserPrivacySettingKey.WALL_OTHERS_POSTS);
					}
				}
			}
			enforcePostPrivacy(self, post);
		}else if(object instanceof Photo photo){
			context.getPhotosController().getAlbum(photo.albumID, self); // This also checks privacy
		}else if(object instanceof Comment comment){
			CommentableContentObject parent=context.getCommentsController().getCommentParentIgnoringPrivacy(comment);
			enforceObjectPrivacy(self, parent);
		}
	}

	public boolean canUserAccessGroupContent(@Nullable User self, @NotNull Group group){
		if(group.accessType!=Group.AccessType.OPEN){
			if(self==null)
				return false;
			Group.MembershipState state=context.getGroupsController().getUserMembershipState(group, self);
			return state==Group.MembershipState.MEMBER || state==Group.MembershipState.TENTATIVE_MEMBER;
		}
		return true;
	}

	public void enforceUserAccessToGroupContent(@Nullable User self, @NotNull Group group){
		if(!canUserAccessGroupContent(self, group))
			throw new UserActionNotAllowedException();
	}

	public void enforceUserAccessToGroupProfile(@Nullable User self, @NotNull Group group){
		if(Config.demoMode && self==null)
			throw new InaccessibleGroupException(group);
		switch(group.banStatus){
			case NONE -> {}
			case SUSPENDED -> {
				if(!canAccessBannedGroups(self))
					throw new UserErrorException(group.isEvent() ? "event_banned" : "group_banned");
			}
			case HIDDEN -> {
				if(self==null)
					throw new InaccessibleGroupException(group);
			}
			case SELF_DEACTIVATED -> {
				if(!canAccessBannedGroups(self))
					throw new UserErrorException(group.isEvent() ? "event_deactivated" : "group_deactivated");
			}
		}
		if(group.banInfo!=null && group.banInfo.suspendedOnRemoteServer() && !canAccessBannedGroups(self))
			throw new UserErrorException(group.isEvent() ? "event_banned" : "group_banned");
		// For closed groups, the profile is still accessible by everyone.
		// For private groups, the profile is only accessible if you're a member or have a pending invite.
		if(group.accessType==Group.AccessType.PRIVATE){
			if(self==null)
				throw new UserActionNotAllowedException(group.isEvent() ? "event_private_no_access" : "group_private_no_access");
			Group.MembershipState state=context.getGroupsController().getUserMembershipState(group, self);
			if(state!=Group.MembershipState.MEMBER && state!=Group.MembershipState.TENTATIVE_MEMBER && state!=Group.MembershipState.INVITED)
				throw new UserActionNotAllowedException(group.isEvent() ? "event_private_no_access" : "group_private_no_access");
		}
	}

	public void updateUserPrivacySettings(@NotNull User self, @NotNull Map<UserPrivacySettingKey, PrivacySetting> settings, @Nullable EnumSet<FriendsNewsfeedTypeFilter> feedTypes){
		try{
			// Collect user IDs to check their friendship statuses
			Set<Integer> friendIDs=new HashSet<>();
			for(PrivacySetting ps:settings.values()){
				friendIDs.addAll(ps.allowUsers);
				friendIDs.addAll(ps.exceptUsers);
			}
			if(!friendIDs.isEmpty()){
				friendIDs=UserStorage.intersectWithFriendIDs(self.id, friendIDs);
			}
			// Remove any user IDs that are not friends
			for(PrivacySetting ps:settings.values()){
				ps.allowUsers.removeIf(Predicate.not(friendIDs::contains));
				ps.exceptUsers.removeIf(Predicate.not(friendIDs::contains));
			}

			boolean friendListsUsed=false;
			for(PrivacySetting ps:settings.values()){
				if(!ps.allowLists.isEmpty() || !ps.exceptLists.isEmpty()){
					friendListsUsed=true;
					break;
				}
			}

			if(friendListsUsed){
				populatePrivacySettingsFriendListUsers(self, settings.values());
			}

			if(self.privacySettings.equals(settings) && Objects.equals(feedTypes, self.newsTypesToShow))
				return;

			UserStorage.setPrivacySettings(self, settings);
			self.privacySettings=settings;
			if(feedTypes!=null && feedTypes.equals(EnumSet.complementOf(EnumSet.of(FriendsNewsfeedTypeFilter.POSTS)))){
				feedTypes=null;
			}
			if(!Objects.equals(self.newsTypesToShow, feedTypes)){
				self.newsTypesToShow=feedTypes;
				UserStorage.updateExtendedFields(self, self.serializeProfileFields());
			}
			// Cached friends feeds filter posts by privacy settings (e.g. who can see others' posts on this user's wall), so they must be rebuilt
			context.getNewsfeedController().clearFriendsFeedCache();
		}catch(SQLException x){
			throw new InternalServerErrorException(x);
		}
	}

	public boolean checkUserPrivacy(@Nullable User self, @NotNull User owner, @NotNull UserPrivacySettingKey key){
		// Unconditionally deny any active actions to blocked users
		if(!key.isForViewing() && self!=null && (isUserBlocked(self, owner) || (false)))
			return false;
		boolean r=checkUserPrivacy(self, owner, owner.getPrivacySetting(key));
		if(key==UserPrivacySettingKey.PRIVATE_MESSAGES && !r && self!=null){
			try{
				MessagesPrivacyGrant grant=MailStorage.getPrivacyGrant(owner.id, self.id);
				if(grant!=null && grant.isValid()){
					return true;
				}
			}catch(SQLException x){
				throw new InternalServerErrorException(x);
			}
		}
		return r;
	}

	public boolean checkUserPrivacy(@Nullable User self, @NotNull User owner, @NotNull PrivacySetting setting){
		// Logged-out users are only allowed for "everyone"
		if(self==null)
			return setting.baseRule==PrivacySetting.Rule.EVERYONE;
		// You can always do everything with objects you own
		if(self.id==owner.id)
			return true;
		// Denied users are always denied regardless of the base rule
		if(setting.exceptUsers.contains(self.id) || setting.exceptListUsers.contains(self.id))
			return false;

		if(isUserBlocked(self, owner) && setting.baseRule!=PrivacySetting.Rule.EVERYONE)
			return false;

		// Allowed users are always allowed
		if(setting.allowUsers.contains(self.id) || setting.allowListUsers.contains(self.id))
			return true;

		return switch(setting.baseRule){
			case EVERYONE -> true;
			case NONE -> false;
			case FRIENDS -> context.getFriendsController().getSimpleFriendshipStatus(self, owner)==FriendshipStatus.FRIENDS;
			case FOLLOWERS -> {
				FriendshipStatus status=context.getFriendsController().getSimpleFriendshipStatus(self, owner);
				yield status==FriendshipStatus.FOLLOWING || status==FriendshipStatus.FRIENDS;
			}
			case FOLLOWING -> {
				FriendshipStatus status=context.getFriendsController().getSimpleFriendshipStatus(self, owner);
				yield status==FriendshipStatus.FOLLOWED_BY || status==FriendshipStatus.FRIENDS;
			}
			case FRIENDS_OF_FRIENDS -> context.getFriendsController().getMutualFriendsCount(self, owner)>0;
		};
	}

	public void enforceUserPrivacy(@Nullable User self, @NotNull User owner, @NotNull UserPrivacySettingKey key){
		if(!checkUserPrivacy(self, owner, key))
			throw key.isForViewing() ? new UserContentUnavailableException() : new UserActionNotAllowedException();
	}

	public void enforceUserPrivacy(@Nullable User self, @NotNull User owner, @NotNull PrivacySetting setting, boolean forViewing){
		if(!forViewing && self!=null && isUserBlocked(self, owner))
			throw new UserActionNotAllowedException();
		if(!checkUserPrivacy(self, owner, setting))
			throw forViewing ? new UserContentUnavailableException() : new UserActionNotAllowedException();
	}

	public boolean isUserBlocked(User self, Actor target){
		try{
			// TODO cache
			if(target instanceof User user){
				return UserStorage.isUserBlocked(user.id, self.id);
			}else if(target instanceof Group group){
				return GroupStorage.isUserBlocked(group.id, self.id);
			}
			return false;
		}catch(SQLException x){
			throw new InternalServerErrorException(x);
		}
	}

	public boolean checkPostPrivacy(@Nullable User self, Post post){
		if(post.privacy==Post.Privacy.PUBLIC)
			return true;
		if(self==null)
			return false;
		if(post.privacy==Post.Privacy.FOLLOWERS_AND_MENTIONED && post.mentionedUserIDs.contains(self.id))
			return true;
		FriendshipStatus status=context.getFriendsController().getSimpleFriendshipStatus(self, context.getUsersController().getUserOrThrow(post.authorID));
		return switch(post.privacy){
			case FOLLOWERS_ONLY, FOLLOWERS_AND_MENTIONED -> status==FriendshipStatus.FOLLOWING || status==FriendshipStatus.FRIENDS;
			case FRIENDS_ONLY -> status==FriendshipStatus.FRIENDS;
			case PUBLIC -> throw new IllegalStateException(); // unreachable
		};
	}

	public void enforcePostPrivacy(@Nullable User self, Post post){
		if(!checkPostPrivacy(self, post))
			throw new UserContentUnavailableException();
	}

	public void filterPostsForPerPostPrivacy(@Nullable User self, Collection<Post> posts){
		// TODO optimize this to avoid querying the same friendship states multiple times
		posts.removeIf(post->!checkPostPrivacy(self, post));
	}

	public void filterPostViewModelsForPerPostPrivacy(@Nullable User self, Collection<PostViewModel> posts){
		posts.removeIf(post->!checkPostPrivacy(self, post.post));
	}

	public void filterPostsForOwnerPrivacy(@Nullable User self, Collection<Post> posts){
		HashSet<Integer> groupIDs=new HashSet<>(), userIDsForWallToWallPosts=new HashSet<>(), userIDsForProfileAccess=new HashSet<>();
		for(Post post:posts){
			if(post.ownerID<0){
				groupIDs.add(-post.ownerID);
			}else{
				userIDsForProfileAccess.add(post.ownerID);
				if(post.isWallToWall()){
					userIDsForWallToWallPosts.add(post.ownerID);
				}
			}
		}
		Map<Integer, Group> groups=context.getGroupsController().getGroupsByIdAsMap(groupIDs);
		Map<Integer, User> users=context.getUsersController().getUsers(Stream.of(userIDsForWallToWallPosts, userIDsForProfileAccess).flatMap(Set::stream).collect(Collectors.toSet()));
		HashSet<Integer> accessibleGroups=new HashSet<>(), accessibleUserProfiles=new HashSet<>(), accessibleUserWalls=new HashSet<>();
		for(Group g:groups.values()){
			if(canUserAccessGroupContent(self, g) && g.wallState!=GroupFeatureState.DISABLED)
				accessibleGroups.add(g.id);
		}
		for(int id:userIDsForWallToWallPosts){
			User user=users.get(id);
			if(user==null)
				continue;
			if(checkUserPrivacy(self, user, UserPrivacySettingKey.WALL_OTHERS_POSTS))
				accessibleUserWalls.add(id);
		}
		for(int id:userIDsForProfileAccess){
			User user=users.get(id);
			if(user==null)
				continue;
			if(canAccessUserProfile(self, user))
				accessibleUserProfiles.add(id);
		}
		posts.removeIf(p->{
			if(p.ownerID<0){
				return !accessibleGroups.contains(-p.ownerID);
			}else{
				return !accessibleUserProfiles.contains(p.ownerID) || (p.isWallToWall() && !accessibleUserWalls.contains(p.ownerID));
			}
		});
	}

	private boolean canAccessBannedProfiles(@Nullable User self){
		if(self!=null){
			Account account=context.getUsersController().getAccountForUser(self);
			if(account.roleID!=0)
				return Config.userRoles.get(account.roleID).hasPermission(UserRole.Permission.MANAGE_USERS);
		}
		return false;
	}

	private boolean canAccessBannedGroups(@Nullable User self){
		if(self!=null){
			Account account=context.getUsersController().getAccountForUser(self);
			if(account.roleID!=0)
				return Config.userRoles.get(account.roleID).hasPermission(UserRole.Permission.MANAGE_GROUPS);
		}
		return false;
	}

	public void enforceUserProfileAccess(@Nullable User self, User target){
		if(Config.demoMode && self==null)
			throw new InaccessibleProfileException(target);
		switch(target.banStatus){
			case NONE -> {}
			case FROZEN, SUSPENDED -> {
				if(!canAccessBannedProfiles(self))
					throw new UserErrorException("profile_banned");
			}
			case HIDDEN -> {
				if(self==null)
					throw new InaccessibleProfileException(target);
			}
			case SELF_DEACTIVATED -> {
				if(!canAccessBannedProfiles(self))
					throw new UserErrorException("profile_deactivated");
			}
		}
		if(target.banInfo!=null && target.banInfo.suspendedOnRemoteServer() && !canAccessBannedProfiles(self))
			throw new UserErrorException("profile_banned");
	}

	public boolean canAccessUserProfile(@Nullable User self, User target){
		if(Config.demoMode && self==null)
			return false;
		switch(target.banStatus){
			case NONE -> {}
			case FROZEN, SUSPENDED, SELF_DEACTIVATED -> {
				if(!canAccessBannedProfiles(self))
					return false;
			}
			case HIDDEN -> {
				if(self==null)
					return false;
			}
		}
		return target.banInfo==null || !target.banInfo.suspendedOnRemoteServer() || canAccessBannedProfiles(self);
	}

	void populatePrivacySettingsFriendListUsers(User self, Collection<PrivacySetting> settings){
		try{
			Map<Integer, BitSet> friendsWithLists=UserStorage.getAllFriendsWithLists(self.id);
			Map<Integer, Set<Integer>> friendsInLists=new HashMap<>();
			Set<Integer> validListIDs=context.getFriendsController().getFriendLists(self).stream().map(FriendList::id).collect(Collectors.toSet());

			friendsWithLists.forEach((id, lists)->{
				lists.stream().forEach(lid->friendsInLists.computeIfAbsent(lid+1, _k->new HashSet<>()).add(id));
			});

			for(PrivacySetting ps:settings){
				ps.allowLists.removeIf(lid->!validListIDs.contains(lid) && lid<FriendList.FIRST_PUBLIC_LIST_ID);
				ps.exceptLists.removeIf(lid->!validListIDs.contains(lid) && lid<FriendList.FIRST_PUBLIC_LIST_ID);
				if(!ps.allowLists.isEmpty()){
					ps.allowListUsers=ps.allowLists.stream().map(friendsInLists::get).filter(Objects::nonNull).flatMap(Set::stream).collect(Collectors.toSet());
				}
				if(!ps.exceptLists.isEmpty()){
					ps.exceptListUsers=ps.exceptLists.stream().map(friendsInLists::get).filter(Objects::nonNull).flatMap(Set::stream).collect(Collectors.toSet());
				}
			}
		}catch(SQLException x){
			throw new InternalServerErrorException(x);
		}
	}

	void updatePrivacySettingsAffectedByFriendListChanges(User owner, Set<Integer> listIDs){
		try{
			HashSet<UserPrivacySettingKey> affectedUserSettings=new HashSet<>();
			ArrayList<PhotoAlbum> affectedPhotoAlbums=new ArrayList<>();

			owner.privacySettings.forEach((key, setting)->{
				for(int listID:listIDs){
					if(setting.allowLists.contains(listID) || setting.exceptLists.contains(listID)){
						affectedUserSettings.add(key);
						break;
					}
				}
			});

			List<PhotoAlbum> albums=PhotoStorage.getAllAlbums(owner.id);
			for(PhotoAlbum album:albums){
				for(int listID:listIDs){
					if(album.viewPrivacy.allowLists.contains(listID) || album.viewPrivacy.exceptLists.contains(listID)
							|| album.commentPrivacy.allowLists.contains(listID) || album.commentPrivacy.exceptLists.contains(listID)){
						affectedPhotoAlbums.add(album);
						break;
					}
				}
			}

			if(affectedUserSettings.isEmpty() && affectedPhotoAlbums.isEmpty())
				return;

			ArrayList<PrivacySetting> settingsToBeUpdated=new ArrayList<>();
			for(UserPrivacySettingKey key:affectedUserSettings){
				settingsToBeUpdated.add(owner.privacySettings.get(key));
			}
			HashMap<Long, PrivacySetting> albumOldViewSettings=new HashMap<>();
			for(PhotoAlbum album:affectedPhotoAlbums){
				settingsToBeUpdated.add(album.viewPrivacy);
				settingsToBeUpdated.add(album.commentPrivacy);
				albumOldViewSettings.put(album.id, new PrivacySetting(album.viewPrivacy));
			}
			for(PrivacySetting ps:settingsToBeUpdated){
				ps.allowListUsers=Set.of();
				ps.exceptListUsers=Set.of();
			}

			populatePrivacySettingsFriendListUsers(owner, settingsToBeUpdated);

			if(!affectedUserSettings.isEmpty()){
				UserStorage.setPrivacySettings(owner, owner.privacySettings);
			}
			for(PhotoAlbum album:affectedPhotoAlbums){
				PhotoStorage.updateUserAlbumPrivacy(album.id, album.viewPrivacy, album.commentPrivacy);
			}
		}catch(SQLException x){
			throw new InternalServerErrorException(x);
		}
	}

	public PaginatedList<User> getBlockedUsers(User self, int offset, int count){
		try{
			return UserStorage.getBlockedUsers(self.id, offset, count);
		}catch(SQLException x){
			throw new InternalServerErrorException(x);
		}
	}

}
