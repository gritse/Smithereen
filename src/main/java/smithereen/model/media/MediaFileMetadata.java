package smithereen.model.media;

public interface MediaFileMetadata{
	int width();
	int height();
	String blurhash();
	float[] cropRegion();
	int duration();

	/**
	 * @return true if this is an animated image (stored as an animated WebP)
	 */
	default boolean animated(){
		return false;
	}

	static Class<? extends MediaFileMetadata> classForType(MediaFileType type){
		return switch(type){
			case IMAGE_PHOTO, IMAGE_AVATAR, IMAGE_GRAFFITI, IMAGE_GROUP_LINK_THUMB -> ImageMetadata.class;
			case USER_EXPORT_ARCHIVE -> UserDataArchiveMetadata.class;
		};
	}
}
