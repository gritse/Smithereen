package smithereen.model.media;

public enum MediaFileType{
	IMAGE_PHOTO,
	IMAGE_AVATAR,
	IMAGE_GRAFFITI,
	IMAGE_GROUP_LINK_THUMB,
	USER_EXPORT_ARCHIVE,
	/**
	 * Animated GIF or WebP, stored as animated WebP
	 */
	IMAGE_ANIMATED;

	public String getFileExtension(){
		return switch(this){
			case USER_EXPORT_ARCHIVE -> "zip";
			default -> "webp";
		};
	}

	public MediaKind kind(){
		return switch(this){
			case USER_EXPORT_ARCHIVE -> MediaKind.ARCHIVE;
			case IMAGE_ANIMATED -> MediaKind.ANIMATED_IMAGE;
			default -> MediaKind.STILL_IMAGE;
		};
	}

	public String getMimeType(){
		return switch(this){
			case USER_EXPORT_ARCHIVE -> "application/zip";
			default -> "image/webp";
		};
	}
}
