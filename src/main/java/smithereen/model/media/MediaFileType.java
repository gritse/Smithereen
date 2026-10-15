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
	IMAGE_ANIMATED,
	/**
	 * Transcoded to H.264 and AAC in MP4
	 */
	VIDEO;

	public String getFileExtension(){
		return switch(this){
			case USER_EXPORT_ARCHIVE -> "zip";
			case VIDEO -> "mp4";
			default -> "webp";
		};
	}

	public MediaKind kind(){
		return switch(this){
			case USER_EXPORT_ARCHIVE -> MediaKind.ARCHIVE;
			case IMAGE_ANIMATED -> MediaKind.ANIMATED_IMAGE;
			case VIDEO -> MediaKind.VIDEO;
			default -> MediaKind.STILL_IMAGE;
		};
	}

	public String getMimeType(){
		return switch(this){
			case USER_EXPORT_ARCHIVE -> "application/zip";
			case VIDEO -> "video/mp4";
			default -> "image/webp";
		};
	}
}
