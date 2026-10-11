package smithereen.controllers;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.sql.SQLException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import smithereen.ApplicationContext;
import smithereen.Config;
import smithereen.LruCache;
import smithereen.exceptions.BadRequestException;
import smithereen.exceptions.InternalServerErrorException;
import smithereen.exceptions.ObjectNotFoundException;
import smithereen.exceptions.UserActionNotAllowedException;
import smithereen.exceptions.UserErrorException;
import smithereen.model.Group;
import smithereen.model.PaginatedList;
import smithereen.model.User;
import smithereen.model.board.BoardTopic;
import smithereen.model.board.BoardTopicsSortOrder;
import smithereen.model.comments.Comment;
import smithereen.model.feed.NewsfeedEntry;
import smithereen.model.groups.GroupFeatureState;
import smithereen.storage.BoardStorage;
import smithereen.storage.CommentStorage;
import smithereen.text.FormattedTextFormat;
import spark.utils.StringUtils;

public class BoardController{
	private static final Logger LOG=LoggerFactory.getLogger(BoardController.class);
	private final ApplicationContext context;

	private final LruCache<Long, BoardTopic> topicCache=new LruCache<>(1000);

	public BoardController(ApplicationContext context){
		this.context=context;
	}

	public PaginatedList<BoardTopic> getTopicsIgnoringPrivacy(Group group, int offset, int count, BoardTopicsSortOrder order){
		try{
			return BoardStorage.getGroupTopics(group.id, offset, count, order);
		}catch(SQLException x){
			throw new InternalServerErrorException(x);
		}
	}

	public PaginatedList<BoardTopic> getPinnedTopicsIgnoringPrivacy(Group group, int offset, int count){
		try{
			return BoardStorage.getGroupPinnedTopics(group.id, offset, count);
		}catch(SQLException x){
			throw new InternalServerErrorException(x);
		}
	}

	public BoardTopic getTopicIgnoringPrivacy(long id){
		BoardTopic topic=topicCache.get(id);
		if(topic!=null)
			return topic;
		try{
			topic=BoardStorage.getTopic(id);
			if(topic==null)
				throw new ObjectNotFoundException();
			topicCache.put(id, topic);
			return topic;
		}catch(SQLException x){
			throw new InternalServerErrorException(x);
		}
	}

	public BoardTopic getTopic(User self, long id){
		BoardTopic topic=getTopicIgnoringPrivacy(id);
		Group group=context.getGroupsController().getGroupOrThrow(topic.groupID);
		context.getPrivacyController().enforceUserAccessToGroupContent(self, group);
		if(group.boardState==GroupFeatureState.DISABLED)
			throw new UserActionNotAllowedException("err_access_content");
		return topic;
	}

	public Map<Long, BoardTopic> getTopicsIgnoringPrivacy(Collection<Long> ids){
		if(ids.isEmpty())
			return Map.of();
		if(ids.size()==1){
			long id=ids.iterator().next();
			return Map.of(id, getTopicIgnoringPrivacy(id));
		}
		HashMap<Long, BoardTopic> topics=new HashMap<>();
		Set<Long> remainingIDs=new HashSet<>(ids);
		for(long id:ids){
			BoardTopic topic=topicCache.get(id);
			if(topic!=null){
				topics.put(id, topic);
				remainingIDs.remove(id);
			}
		}
		if(remainingIDs.isEmpty())
			return topics;
		try{
			Map<Long, BoardTopic> moreTopics=BoardStorage.getTopics(remainingIDs);
			topics.putAll(moreTopics);
			for(BoardTopic topic:moreTopics.values()){
				topicCache.put(topic.id, topic);
			}
			return topics;
		}catch(SQLException x){
			throw new InternalServerErrorException(x);
		}
	}

	public boolean canCreateTopics(User self, Group group){
		return context.getPrivacyController().canUserAccessGroupContent(self, group) && group.boardState!=GroupFeatureState.DISABLED
				&& !(group.boardState==GroupFeatureState.ENABLED_RESTRICTED && !context.getGroupsController().getMemberAdminLevel(group, self).isAtLeast(Group.AdminLevel.MODERATOR))
				&& !context.getPrivacyController().isUserBlocked(self, group);
	}

	public void enforceTopicCreationPermission(User self, Group group){
		if(!canCreateTopics(self, group))
			throw new UserActionNotAllowedException();
	}

	public boolean canPostInTopic(User self, BoardTopic topic){
		Group group=context.getGroupsController().getGroupOrThrow(topic.groupID);
		return group.boardState!=GroupFeatureState.DISABLED
				&& !topic.isClosed
				&& context.getPrivacyController().canUserAccessGroupContent(self, group)
				&& !context.getPrivacyController().isUserBlocked(self, group);
	}

	public BoardTopic createTopic(User self, Group group, String title, @NotNull String textSource, @NotNull FormattedTextFormat sourceFormat,
								  @Nullable String contentWarning, @NotNull List<String> attachmentIDs, @NotNull Map<String, String> attachAltTexts, String guid){
		if(StringUtils.isNotEmpty(guid)){
			CommentsController.CommentGuidInfo guidInfo=context.getCommentsController().guids.get(guid);
			if(guidInfo!=null && guidInfo.time().isAfter(Instant.now().minus(1, ChronoUnit.HOURS))){
				try{
					return getTopicIgnoringPrivacy(guidInfo.commentID());
				}catch(ObjectNotFoundException ignore){}
			}
		}
		enforceTopicCreationPermission(self, group);
		try{
			if(textSource.trim().isEmpty() && attachmentIDs.isEmpty())
				throw new BadRequestException("Empty comment");
			long id=BoardStorage.createTopic(group.id, title, self.id);
			BoardTopic topic=BoardStorage.getTopic(id);

			Comment comment=context.getCommentsController().createComment(self, topic, null, textSource, sourceFormat, contentWarning, attachmentIDs, attachAltTexts, true);
			topic.numComments=1;
			topic.firstCommentID=comment.id;
			BoardStorage.setTopicFirstCommentID(id, comment.id);

			context.getNewsfeedController().putFriendsFeedEntry(self, id, NewsfeedEntry.Type.BOARD_TOPIC);
			context.getNewsfeedController().putGroupsFeedEntry(group, id, NewsfeedEntry.Type.BOARD_TOPIC);



			topicCache.put(id, topic);
			if(StringUtils.isNotEmpty(guid)){
				HashMap<String, CommentsController.CommentGuidInfo> guids=context.getCommentsController().guids;
				synchronized(guids){
					guids.put(guid, new CommentsController.CommentGuidInfo(id, Instant.now()));
				}
			}
			return topic;
		}catch(SQLException x){
			throw new InternalServerErrorException(x);
		}
	}

	public void deleteTopic(User self, BoardTopic topic){
		Group group=context.getGroupsController().getGroupOrThrow(topic.groupID);
		if(topic.authorID!=self.id){
			context.getGroupsController().enforceUserAdminLevel(group, self, Group.AdminLevel.MODERATOR);
		}else if(context.getPrivacyController().isUserBlocked(self, group)){
			throw new UserActionNotAllowedException();
		}
		deleteTopicWithFederation(group, topic);
	}

	void deleteTopicWithFederation(Group group, BoardTopic topic){

		deleteTopic(topic);
	}

	public void deleteTopic(BoardTopic topic){
		topicCache.remove(topic.id);
		try{
			BoardStorage.deleteTopic(topic.id);
			context.getCommentsController().deleteCommentsForObject(topic);
		}catch(SQLException x){
			throw new InternalServerErrorException(x);
		}
		context.getNewsfeedController().deleteFriendsFeedEntriesForObject(topic.id, NewsfeedEntry.Type.BOARD_TOPIC);
		context.getNewsfeedController().deleteGroupsFeedEntriesForObject(topic.id, NewsfeedEntry.Type.BOARD_TOPIC);
	}

	public void renameTopic(User self, BoardTopic topic, String title){
		Group group=context.getGroupsController().getGroupOrThrow(topic.groupID);
		if(topic.authorID!=self.id){
			context.getGroupsController().enforceUserAdminLevel(group, self, Group.AdminLevel.MODERATOR);
		}else if(context.getPrivacyController().isUserBlocked(self, group)){
			throw new UserActionNotAllowedException();
		}
		title=title.trim();
		String oldTitle=topic.title;
		renameTopic(topic, title);
		topic.title=title;

	}

	void renameTopic(BoardTopic topic, String title){
		try{
			BoardStorage.renameTopic(topic.id, title);
		}catch(SQLException x){
			throw new InternalServerErrorException(x);
		}
		topicCache.put(topic.id, topic);
	}

	public void setTopicClosed(User self, BoardTopic topic, boolean closed){
		Group group=context.getGroupsController().getGroupOrThrow(topic.groupID);
		context.getGroupsController().enforceUserAdminLevel(group, self, Group.AdminLevel.MODERATOR);
		setTopicClosed(topic, closed);
		topic.isClosed=closed;
	}

	void setTopicClosed(BoardTopic topic, boolean closed){
		try{
			BoardStorage.setTopicClosed(topic.id, closed);
		}catch(SQLException x){
			throw new InternalServerErrorException(x);
		}
		topicCache.put(topic.id, topic);
	}

	public void setTopicPinned(User self, BoardTopic topic, boolean pinned){
		Group group=context.getGroupsController().getGroupOrThrow(topic.groupID);
		context.getGroupsController().enforceUserAdminLevel(group, self, Group.AdminLevel.MODERATOR);
		setTopicPinned(topic, pinned);
		topic.isPinned=pinned;
		topic.pinnedAt=pinned ? Instant.now() : null;
	}

	void setTopicPinned(BoardTopic topic, boolean pinned){
		try{
			BoardStorage.setTopicPinned(topic.id, pinned);
		}catch(SQLException x){
			throw new InternalServerErrorException(x);
		}
		topicCache.put(topic.id, topic);
	}

	void setTopicPinned(BoardTopic topic, Instant pinnedAt){
		try{
			BoardStorage.setTopicPinned(topic.id, pinnedAt);
		}catch(SQLException x){
			throw new InternalServerErrorException(x);
		}
		topicCache.put(topic.id, topic);
	}

	public Map<Long, Comment> getFirstOrLastComments(Collection<Long> topicIDs, boolean first, boolean needAttachments){
		try{
			return CommentStorage.getFirstOrLastBoardTopicComments(topicIDs, first, needAttachments);
		}catch(SQLException x){
			throw new InternalServerErrorException(x);
		}
	}
}
