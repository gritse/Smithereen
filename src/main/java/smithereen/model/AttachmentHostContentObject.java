package smithereen.model;

import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

import smithereen.model.attachments.Attachment;
import smithereen.model.media.MediaFileRef;

public sealed interface AttachmentHostContentObject permits MailMessage, PostLikeObject{
	@NotNull
	List<MediaFileRef> getAttachments();

	void setAttachments(@NotNull List<MediaFileRef> attachments);

	String getPhotoListID();

	/**
	 * @return the attachments as view models, one per media kind
	 */
	default List<Attachment> getProcessedAttachments(){
		ArrayList<Attachment> result=new ArrayList<>();
		for(MediaFileRef ref:getAttachments()){
			if(ref==null)
				continue;
			Attachment att=Attachment.forMedia(ref);
			if(att!=null)
				result.add(att);
		}
		return result;
	}

	default String getSerializedAttachments(){
		return MediaFileRef.serializeList(getAttachments());
	}
}
