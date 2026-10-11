package smithereen.model;

import java.net.URI;

import smithereen.model.comments.Comment;
import smithereen.model.notifications.Notification;
import smithereen.model.photos.Photo;

public sealed interface LikeableContentObject extends OwnedContentObject permits Photo, Post, Comment{
	LikeObjectType getLikeObjectType();
	Notification.ObjectType getObjectTypeForLikeNotifications();
	URI getActivityPubID();
}
