package smithereen.media.sniffing;

import java.io.File;

import smithereen.media.FFmpeg;
import smithereen.media.MediaValidationException;
import smithereen.model.media.MediaKind;

/**
 * Recognizes anything ffprobe can read as a video, or as audio when there is no video stream. Passes everything on
 * when ffmpeg is not installed.
 */
public final class AudioVideoDecoder extends MediaDecoder {
	public AudioVideoDecoder(MediaDecoder next) {
		super(next);
	}

	@Override
	protected MediaKind tryDecode(File file) {
		if (!FFmpeg.isAvailable())
			return null;

		try {
			return FFmpeg.probe(file).hasVideo() ? MediaKind.VIDEO : MediaKind.AUDIO;
		} catch (MediaValidationException x) {
			return null;
		}
	}
}
