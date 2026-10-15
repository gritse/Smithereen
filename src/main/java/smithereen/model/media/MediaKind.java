package smithereen.model.media;

/**
 * What a stored media file is, from the point of view of code that processes, serves and renders it.
 * Derived from {@link MediaFileType}; carries the capabilities that differ between kinds so that callers
 * ask the kind instead of checking types.
 */
public enum MediaKind{
	/**
	 * A photo, a graffiti, an avatar: stored as WebP, served in several sizes through imgproxy.
	 */
	STILL_IMAGE,
	/**
	 * An animated GIF or WebP: stored as animated WebP, served as the stored file because imgproxy would drop the animation.
	 */
	ANIMATED_IMAGE,
	/**
	 * Transcoded to MP4 on the processing queue after the upload; served as the stored file, with a poster frame that is a still image.
	 */
	VIDEO,
	/**
	 * A user data export: not media at all, but stored through the same mechanism.
	 */
	ARCHIVE;

	/**
	 * @return whether imgproxy makes resized and converted variants of this kind
	 */
	public boolean hasResizedVariants(){
		return this==STILL_IMAGE;
	}

	/**
	 * @return whether the user may rotate the image after uploading it (album photos, avatars)
	 */
	public boolean canRotate(){
		return this==STILL_IMAGE;
	}

	/**
	 * @return whether this kind can be shown in the tiled attachment layout, i.e. has a width and a height
	 */
	public boolean isSized(){
		return this==STILL_IMAGE || this==ANIMATED_IMAGE || this==VIDEO;
	}

	/**
	 * @return whether files of this kind are stored with status PROCESSING and finished on the {@link smithereen.media.MediaProcessingQueue}
	 */
	public boolean needsAsyncProcessing(){
		return this==VIDEO;
	}

	public boolean isImage(){
		return this==STILL_IMAGE || this==ANIMATED_IMAGE;
	}
}
