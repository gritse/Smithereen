package smithereen.model.attachments;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import smithereen.model.SizedImage;
import smithereen.model.media.MediaFileRef;
import smithereen.model.media.Video;
import smithereen.util.TranslatableEnum;

/**
 * View model of one attachment of a post, comment or message. One subclass per media kind.
 */
public abstract sealed class Attachment permits PhotoAttachment, VideoAttachment{

	@Nullable
	public String mediaType;

	@Nullable
	public String description;

	public abstract Type getType();

	/**
	 * Builds the view model for a resolved media file reference.
	 * @return null if the kind of file cannot be shown as an attachment
	 */
	@Nullable
	public static Attachment forMedia(@NotNull MediaFileRef ref){
		return switch(ref.media()){
			case SizedImage image -> {
				PhotoAttachment att=ref.isGraffiti() ? new GraffitiAttachment() : new PhotoAttachment();
				att.image=image;
				att.photoID=ref.photoID;
				att.description=ref.name;
				att.setBlurHash(image.getBlurHash());
				if(ref.fileRecord!=null)
					att.mediaType=ref.fileRecord.id().type().getMimeType();
				yield att;
			}
			case Video video -> {
				VideoAttachment att=new VideoAttachment();
				att.video=video;
				att.description=ref.name;
				att.setBlurHash(video.getBlurHash());
				att.mediaType=ref.fileRecord!=null ? ref.fileRecord.id().type().getMimeType() : null;
				yield att;
			}
		};
	}

	public enum Type implements TranslatableEnum<Type>{
		PHOTO,
		VIDEO;

		@Override
		public String getLangKey(){
			return switch(this){
				case PHOTO -> "content_type_photo";
				case VIDEO -> "content_type_video";
			};
		}
	}
}
