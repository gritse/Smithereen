package smithereen.media.sniffing;

import java.io.File;

import smithereen.media.FFmpeg;
import smithereen.media.MediaValidationException;
import smithereen.model.media.MediaKind;

/**
 * The end of the chain: no decoder could read the file, so it is rejected with the error that fits what the client said it was.
 */
public final class RejectingDecoder extends MediaDecoder {
	private final String declaredMimeType;

	/**
	 * @param declaredMimeType the Content-Type the client sent, never null
	 */
	public RejectingDecoder(String declaredMimeType) {
		super(null);
		this.declaredMimeType = declaredMimeType;
	}

	@Override
	protected MediaKind tryDecode(File file) throws MediaValidationException {
		boolean declaredVideo = declaredMimeType.startsWith("video/");
		boolean declaredAudio = declaredMimeType.startsWith("audio/");
		boolean declaredImage = declaredMimeType.startsWith("image/");

		if (declaredVideo && !FFmpeg.isAvailable())
			throw new MediaValidationException(415, "err_file_upload_video_unavailable");

		if (declaredAudio && !FFmpeg.isAvailable())
			throw new MediaValidationException(415, "err_file_upload_audio_unavailable");

		if (declaredImage || declaredVideo || declaredAudio)
			throw MediaValidationException.unreadable();

		throw MediaValidationException.unsupportedFormat();
	}
}
