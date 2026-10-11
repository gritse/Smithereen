package smithereen.model.comments;

import java.net.URI;

import smithereen.model.OwnedContentObject;
import smithereen.model.board.BoardTopic;
import smithereen.model.photos.Photo;

public sealed interface CommentableContentObject extends OwnedContentObject, CommentReplyParent permits Photo, BoardTopic{
	CommentParentObjectID getCommentParentID();
	String getURL();
	URI getActivityPubID();
}
