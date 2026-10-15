package smithereen.model.media;

public interface MediaFileMetadata{
	int width();
	int height();
	String blurhash();
	float[] cropRegion();
	int duration();

	static Class<? extends MediaFileMetadata> classForType(MediaFileType type){
		return switch(type){
			case IMAGE_PHOTO, IMAGE_AVATAR, IMAGE_GRAFFITI, IMAGE_GROUP_LINK_THUMB, IMAGE_ANIMATED -> ImageMetadata.class;
			case USER_EXPORT_ARCHIVE -> UserDataArchiveMetadata.class;
			case VIDEO -> VideoMetadata.class;
		};
	}
}
