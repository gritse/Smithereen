package smithereen.model;

import com.google.gson.JsonParser;

import org.jetbrains.annotations.NotNull;

import java.net.URI;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Set;

import smithereen.Utils;
import smithereen.model.media.MediaFileRef;
import smithereen.model.comments.Comment;
import smithereen.storage.DatabaseUtils;
import smithereen.text.TextProcessor;
import spark.utils.StringUtils;

public abstract sealed class PostLikeObject implements OwnedContentObject, AttachmentHostContentObject permits Post, Comment{
	public int authorID;
	// userID or -groupID
	public int ownerID;
	public String text;
	public List<MediaFileRef> attachments;
	public Instant createdAt;
	public String contentWarning;
	public Instant updatedAt;
	public Set<Integer> mentionedUserIDs=Set.of();
	public int replyCount, immediateReplyCount;
	public boolean deleted;

	protected void fillFromResultSet(ResultSet res) throws SQLException{
		ownerID=res.getInt("owner_user_id");
		if(res.wasNull())
			ownerID=-res.getInt("owner_group_id");
		replyCount=res.getInt("reply_count");
		immediateReplyCount=res.getInt("immediate_reply_count");

		authorID=res.getInt("author_id");
		if(res.wasNull()){
			deleted=true;
			return;
		}

		text=res.getString("text");

		String att=res.getString("attachments");
		if(att!=null){
			try{
				attachments=MediaFileRef.parseList(JsonParser.parseString(att));
			}catch(Exception ignore){}
		}

		createdAt=DatabaseUtils.getInstant(res, "created_at");
		contentWarning=res.getString("content_warning");
		updatedAt=DatabaseUtils.getInstant(res, "updated_at");
		mentionedUserIDs=Utils.deserializeIntSet(res.getBytes("mentions"));
	}

	public boolean hasContentWarning(){
		return contentWarning!=null;
	}

	public String getContentWarning(){
		return contentWarning;
	}

	public abstract int getReplyLevel();

	public boolean isDeleted(){
		return deleted;
	}

	public String serializeAttachments(){
		if(attachments==null)
			return null;
		return MediaFileRef.serializeListCompact(attachments).toString();
	}

	public abstract URI getInternalURL();

	public String getShortTitle(){
		return getShortTitle(100);
	}

	public String getShorterTitle(){
		return getShortTitle(50);
	}

	public String getShortTitle(int maxLen){
		if(StringUtils.isNotEmpty(contentWarning)){
			return TextProcessor.truncateOnWordBoundary(contentWarning, maxLen);
		}
		if(StringUtils.isNotEmpty(text)){
			return TextProcessor.truncateOnWordBoundary(text, maxLen);
		}
		return "";
	}

	@Override
	public int getOwnerID(){
		return ownerID;
	}

	@Override
	public int getAuthorID(){
		return authorID;
	}

	@Override
	public abstract long getObjectID();

	@Override
	@NotNull
	public List<MediaFileRef> getAttachments(){
		return attachments==null ? List.of() : attachments;
	}

	@Override
	public void setAttachments(@NotNull List<MediaFileRef> attachments){
		this.attachments=attachments;
	}

	@Override
	public abstract String getPhotoListID();
}
