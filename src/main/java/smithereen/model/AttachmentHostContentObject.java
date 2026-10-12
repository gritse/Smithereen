package smithereen.model;

import com.google.gson.JsonArray;

import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

import smithereen.activitypub.objects.ActivityPubObject;
import smithereen.activitypub.objects.Document;
import smithereen.activitypub.objects.Image;
import smithereen.activitypub.objects.LocalImage;
import smithereen.model.attachments.Attachment;
import smithereen.model.attachments.GraffitiAttachment;
import smithereen.model.attachments.PhotoAttachment;
import smithereen.storage.MediaStorageUtils;
import spark.utils.StringUtils;

public sealed interface AttachmentHostContentObject permits MailMessage, PostLikeObject{
	@NotNull
	List<ActivityPubObject> getAttachments();

	void setAttachments(@NotNull List<ActivityPubObject> attachments);

	String getPhotoListID();

	default List<Attachment> getProcessedAttachments(){
		ArrayList<Attachment> result=new ArrayList<>();
		for(ActivityPubObject o:getAttachments()){
			String mediaType=o.mediaType==null ? "" : o.mediaType;
			if(o instanceof Image || mediaType.startsWith("image/")){
				PhotoAttachment att=o instanceof Image img && img.isGraffiti ? new GraffitiAttachment() : new PhotoAttachment();
				att.mediaType=o.mediaType;
				if(StringUtils.isNotEmpty(o.name))
					att.description=o.name;
				if(o instanceof LocalImage li){
					att.image=li;
					att.photoID=li.photoID;
				}else{
					continue;
				}
				if(o instanceof Document doc){
					String blurHash=doc.getBlurHash();
					if(StringUtils.isNotEmpty(blurHash))
						att.setBlurHash(blurHash);
				}
				result.add(att);
			}
		}
		return result;
	}

	default String getSerializedAttachments(){
		List<ActivityPubObject> attachObjects=getAttachments();
		if(attachObjects!=null && !attachObjects.isEmpty()){
			if(attachObjects.size()==1){
				return MediaStorageUtils.serializeAttachment(attachObjects.getFirst()).toString();
			}else{
				JsonArray ar=new JsonArray();
				for(ActivityPubObject o:attachObjects){
					ar.add(MediaStorageUtils.serializeAttachment(o));
				}
				return ar.toString();
			}
		}
		return null;
	}
}
