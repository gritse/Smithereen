package smithereen.model.media;

/**
 * Whether a media file is ready to be served. Stored as the ordinal, so the order is fixed.
 */
public enum MediaFileStatus{
	READY,
	/**
	 * Uploaded and waiting for or undergoing transcoding on the processing queue. The file is not stored yet.
	 */
	PROCESSING,
	/**
	 * Transcoding failed. Nothing to serve; the record stays until the content referencing it is deleted.
	 */
	FAILED
}
