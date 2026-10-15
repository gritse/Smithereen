package smithereen.api.model;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

import smithereen.api.ApiCallContext;
import smithereen.model.AttachmentHostContentObject;
import smithereen.model.MailMessage;
import smithereen.model.OwnedContentObject;
import smithereen.model.PostLikeObject;
import smithereen.model.SizedImage;
import smithereen.model.attachments.Attachment;
import smithereen.model.attachments.GraffitiAttachment;
import smithereen.model.attachments.PhotoAttachment;
import smithereen.model.attachments.VideoAttachment;
import smithereen.model.photos.Photo;

public class ApiAttachment{
	public String type;

	public ApiPhoto photo;
	public Graffiti graffiti;
	public Video video;
	public ApiPoll poll;

	public ApiAttachment(Attachment att, ApiCallContext actx, AttachmentHostContentObject parent, Map<Long, Photo> photos){
		switch(att){
			case GraffitiAttachment ga -> {
				type="graffiti";
				graffiti=new Graffiti(ga.image.getApiUriForSizeAndFormat(SizedImage.Type.PHOTO_ORIGINAL, SizedImage.Format.PNG).toString(),
						ga.image.getApiUriForSizeAndFormat(SizedImage.Type.PHOTO_THUMB_MEDIUM, actx.imageFormat).toString(),
						ga.getWidth(), ga.getHeight());
			}
			case VideoAttachment va -> {
				type="video";
				SizedImage poster=va.video.getPoster();
				video=new Video(va.video.isReady() ? Objects.toString(va.video.getFileURL(), null) : null, va.getWidth(), va.getHeight(), va.video.getDuration(),
						poster!=null ? poster.getApiUriForSizeAndFormat(SizedImage.Type.PHOTO_MEDIUM, actx.imageFormat).toString() : null,
						va.video.getStatus().toString().toLowerCase(), va.description);
			}
			case PhotoAttachment pa -> {
				type="photo";
				if(pa.photoID!=0 && photos.containsKey(pa.photoID)){
					this.photo=new ApiPhoto(photos.get(pa.photoID), actx, null, null);
				}else{
					OwnedContentObject ownedParent=(OwnedContentObject) parent;
					Instant createdAt=switch(parent){
						case PostLikeObject post -> post.createdAt;
						case MailMessage msg -> msg.createdAt;
					};
					this.photo=new ApiPhoto(pa, ownedParent.getOwnerID(), ownedParent.getAuthorID(), createdAt, actx);
				}
			}
		}
	}

	public ApiAttachment(){}

	public record Graffiti(String url, String previewUrl, int width, int height){}

	/**
	 * @param url the MP4, null until the video is ready
	 * @param status "ready", "processing" or "failed"
	 */
	public record Video(String url, int width, int height, int duration, String posterUrl, String status, String description){}
}
