package smithereen.model.photos;

import com.google.gson.JsonObject;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.net.URI;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Set;

import smithereen.model.LikeObjectType;
import smithereen.Config;
import smithereen.Utils;
import smithereen.model.media.MediaFileRef;
import smithereen.model.BlurHashable;
import smithereen.model.LikeableContentObject;
import smithereen.model.ObfuscatedObjectIDType;
import smithereen.model.OwnedContentObject;
import smithereen.model.SizedImage;
import smithereen.model.attachments.SizedAttachment;
import smithereen.model.comments.CommentParentObjectID;
import smithereen.model.comments.CommentableContentObject;
import smithereen.model.comments.CommentableObjectType;
import smithereen.model.media.PhotoViewerInlineData;
import smithereen.model.notifications.Notification;
import smithereen.model.reports.ReportableContentObject;
import smithereen.model.reports.ReportableContentObjectID;
import smithereen.model.reports.ReportableContentObjectType;
import smithereen.model.reports.ReportedPhoto;
import smithereen.storage.DatabaseUtils;
import smithereen.util.JsonObjectBuilder;
import smithereen.util.UriBuilder;
import smithereen.util.XTEA;

public sealed class Photo implements SizedAttachment, OwnedContentObject, LikeableContentObject, CommentableContentObject, ReportableContentObject, BlurHashable permits ReportedPhoto{
	public long id;
	public int ownerID;
	public int authorID;
	public long albumID;
	public long localFileID;

	@NotNull
	public String description="";

	public Instant createdAt;
	public PhotoMetadata metadata;
	public int displayOrder;

	public SizedImage image;

	public static Photo fromResultSet(ResultSet res) throws SQLException{
		Photo p=new Photo();
		p.id=res.getLong("id");
		p.ownerID=res.getInt("owner_id");
		p.authorID=res.getInt("author_id");
		p.albumID=res.getLong("album_id");
		p.localFileID=res.getLong("local_file_id");
		p.description=res.getString("description");
		p.createdAt=DatabaseUtils.getInstant(res, "created_at");
		String meta=res.getString("metadata");
		if(meta!=null)
			p.metadata=Utils.gson.fromJson(meta, PhotoMetadata.class);
		p.displayOrder=res.getInt("display_order");
		return p;
	}

	@Override
	public String getURL(){
		return "/photos/"+Utils.encodeLong(XTEA.obfuscateObjectID(id, ObfuscatedObjectIDType.PHOTO));
	}

	public String getAbsoluteURL(){
		return Config.localURI(getURL()).toString();
	}

	@NotNull
	public String getIdString(){
		return Utils.encodeLong(XTEA.obfuscateObjectID(id, ObfuscatedObjectIDType.PHOTO));
	}

	@Override
	public int getWidth(){
		return image.getOriginalDimensions().width;
	}

	@Override
	public int getHeight(){
		return image.getOriginalDimensions().height;
	}

	@Override
	public boolean isSizeKnown(){
		return true;
	}

	@Override
	public LikeObjectType getLikeObjectType(){
		return LikeObjectType.PHOTO;
	}

	@Override
	public Notification.ObjectType getObjectTypeForLikeNotifications(){
		return Notification.ObjectType.PHOTO;
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
	public long getObjectID(){
		return id;
	}

	@Override
	@NotNull
	public URI getActivityPubID(){
		return UriBuilder.local().path("photos", getIdString()).build();
	}

	@Nullable
	@Override
	public String getBlurHash(){
		if(image!=null)
			return image.getBlurHash();
		return metadata.blurhash;
	}

	@Override
	public void setBlurHash(@Nullable String blurHash){
		if(image!=null){
			image.setBlurHash(blurHash);
		}else{
			metadata.blurhash=blurHash;
		}
	}

	public String getSinglePhotoListID(){
		return "single/"+getIdString();
	}

	public PhotoViewerInlineData getSinglePhotoViewerData(){
		return new PhotoViewerInlineData(0, getSinglePhotoListID(), image.getURLsForPhotoViewer());
	}

	@Override
	public CommentParentObjectID getCommentParentID(){
		return new CommentParentObjectID(CommentableObjectType.PHOTO, id);
	}

	@Override
	public JsonObject serializeForReport(int targetID, Set<Long> outFileIDs){
		if(authorID!=targetID && ownerID!=targetID)
			return null;
		JsonObjectBuilder jb=new JsonObjectBuilder()
				.add("type", "photo")
				.add("id", id)
				.add("owner", ownerID)
				.add("author", authorID)
				.add("album", albumID)
				.add("created_at", createdAt.getEpochSecond())
				.add("description", description)
				.add("meta", Utils.gson.toJsonTree(metadata));
		if(localFileID!=0){
			outFileIDs.add(localFileID);
			jb.add("file_id", localFileID);
		}
		return jb.build();
	}

	@Override
	public void fillFromReport(int reportID, JsonObject jo){
		id=jo.get("id").getAsLong();
		ownerID=jo.get("owner").getAsInt();
		authorID=jo.get("author").getAsInt();
		albumID=jo.get("album").getAsLong();
		createdAt=Instant.ofEpochSecond(jo.get("created_at").getAsLong());
		metadata=Utils.gson.fromJson(jo.get("meta"), PhotoMetadata.class);
		if(jo.has("description"))
			description=jo.get("description").getAsString();
		if(jo.has("file_id"))
			localFileID=jo.get("file_id").getAsLong();
	}

	@Override
	public ReportableContentObjectID getReportableObjectID(){
		return new ReportableContentObjectID(ReportableContentObjectType.PHOTO, id);
	}
}
