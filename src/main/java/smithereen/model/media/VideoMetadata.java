package smithereen.model.media;

/**
 * @param width width of the transcoded video
 * @param duration in seconds
 * @param posterFileID the still image shown before playback, a media file of its own referenced through {@link MediaFileReferenceType#VIDEO_POSTER}
 * @param blurhash the poster's blurhash
 * @param frameRate frames per second of the uploaded file, 0 if unknown; the transcode caps it at 30
 */
public record VideoMetadata(int width, int height, int duration, long posterFileID, String blurhash, double frameRate) implements MediaFileMetadata{
	@Override
	public float[] cropRegion(){
		return null;
	}
}
