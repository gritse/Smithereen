package smithereen.media;

import java.io.File;
import java.io.IOException;

import smithereen.model.media.MediaKind;

/**
 * Turns an uploaded file into the file that is stored. One implementation per {@link MediaKind}.
 */
public interface MediaProcessor{
	MediaKind kind();

	/**
	 * @param input the uploaded file, which the caller deletes afterwards
	 * @throws MediaValidationException if the file is rejected
	 * @throws IOException if processing fails for a reason that is not the user's fault
	 */
	ProcessedMedia process(File input, ProcessingOptions options) throws IOException, MediaValidationException;

	static MediaProcessor forKind(MediaKind kind){
		return switch(kind){
			case STILL_IMAGE -> new StillImageProcessor();
			case ANIMATED_IMAGE -> new AnimatedImageProcessor();
			case VIDEO -> new VideoProcessor();
			case ARCHIVE -> throw new IllegalArgumentException("Archives are not uploaded");
		};
	}
}
